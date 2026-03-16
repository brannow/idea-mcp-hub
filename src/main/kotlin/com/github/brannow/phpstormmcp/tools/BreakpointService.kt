package com.github.brannow.phpstormmcp.tools

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XBreakpointManager
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import java.util.concurrent.CompletableFuture

data class BreakpointInfo(
    val id: String,
    val file: String,
    val line: Int,
    val enabled: Boolean,
    val condition: String? = null,
    val logExpression: String? = null,
    val suspend: Boolean = true,
    val method: Boolean = false,
    val vendor: Boolean = false
)

data class AddBreakpointResult(
    val breakpoint: BreakpointInfo,
    val existingBreakpoints: List<BreakpointInfo> = emptyList()
)

data class RemoveBreakpointsResult(
    val removed: List<BreakpointInfo>,
    val notFound: List<String> = emptyList()
)

class AmbiguousBreakpointException(
    val breakpoints: List<BreakpointInfo>
) : IllegalArgumentException()

class BreakpointNotFoundException(
    val query: String
) : IllegalArgumentException()

@Service(Service.Level.PROJECT)
open class BreakpointService(private val project: Project) {

    internal open fun getBreakpointManager(): XBreakpointManager =
        XDebuggerManager.getInstance(project).breakpointManager

    internal open fun findLineBreakpointType(): XLineBreakpointType<*>? {
        return XDebuggerUtil.getInstance().lineBreakpointTypes.firstOrNull {
            it.id.contains("php", ignoreCase = true)
        }
    }

    internal open fun resolveFile(path: String): VirtualFile? {
        LocalFileSystem.getInstance().findFileByPath(path)?.let { return it }
        val basePath = project.basePath ?: return null
        val resolved = "$basePath/$path"
        return LocalFileSystem.getInstance().findFileByPath(resolved)
    }

    internal open fun <T> runOnEdt(action: () -> T): T {
        val future = CompletableFuture<T>()
        ApplicationManager.getApplication().invokeLater {
            future.complete(action())
        }
        return future.get()
    }

    fun listBreakpoints(fileFilter: String? = null): List<BreakpointInfo> {
        val manager = getBreakpointManager()
        return manager.allBreakpoints
            .filterIsInstance<XLineBreakpoint<*>>()
            .filter { !manager.isDefaultBreakpoint(it) }
            .mapNotNull { bp -> toBreakpointInfo(bp) }
            .filter { fileFilter == null || it.file.contains(fileFilter) }
    }

    fun addBreakpoint(
        file: String,
        line: Int,
        condition: String? = null,
        logExpression: String? = null,
        suspend: Boolean = true
    ): AddBreakpointResult {
        val type = findLineBreakpointType()
            ?: throw IllegalStateException("PHP line breakpoint type not available. Is the PHP plugin active?")

        val virtualFile = resolveFile(file)
            ?: throw IllegalArgumentException("File not found: $file")

        val fileUrl = virtualFile.url
        val zeroBasedLine = line - 1

        val existing = findBreakpointsAtLine(virtualFile, zeroBasedLine)

        val info = runOnEdt {
            @Suppress("UNCHECKED_CAST")
            val bp = getBreakpointManager().addLineBreakpoint(
                type as XLineBreakpointType<Nothing>,
                fileUrl,
                zeroBasedLine,
                null
            )

            if (condition != null) {
                bp.setCondition(condition)
            }
            if (logExpression != null) {
                bp.setLogExpression(logExpression)
            }
            if (!suspend) {
                bp.suspendPolicy = SuspendPolicy.NONE
            }

            toBreakpointInfo(bp)!!
        }

        return AddBreakpointResult(
            breakpoint = info,
            existingBreakpoints = existing
        )
    }

    fun updateBreakpoint(
        id: String,
        enabled: Boolean? = null,
        condition: String? = null,
        logExpression: String? = null,
        suspend: Boolean? = null
    ): BreakpointInfo {
        val bp = findBreakpointById(id)
            ?: throw BreakpointNotFoundException(id)

        return runOnEdt {
            if (enabled != null) {
                bp.isEnabled = enabled
            }
            if (condition != null) {
                bp.setCondition(condition.ifEmpty { null })
            }
            if (logExpression != null) {
                bp.setLogExpression(logExpression.ifEmpty { null })
            }
            if (suspend != null) {
                bp.suspendPolicy = if (suspend) SuspendPolicy.ALL else SuspendPolicy.NONE
            }

            toBreakpointInfo(bp)!!
        }
    }

    fun removeBreakpoints(ids: List<String>? = null): RemoveBreakpointsResult {
        val manager = getBreakpointManager()

        if (ids.isNullOrEmpty()) {
            val allBps = manager.allBreakpoints
                .filterIsInstance<XLineBreakpoint<*>>()
                .filter { !manager.isDefaultBreakpoint(it) }
            val infos = allBps.mapNotNull { toBreakpointInfo(it) }

            if (allBps.isNotEmpty()) {
                runOnEdt { allBps.forEach { manager.removeBreakpoint(it) } }
            }

            return RemoveBreakpointsResult(removed = infos)
        }

        // Resolve each ID — can be numeric, file:line, or file-only (purge)
        val toRemove = mutableListOf<XLineBreakpoint<*>>()
        val toRemoveInfos = mutableListOf<BreakpointInfo>()
        val notFound = mutableListOf<String>()

        for (id in ids) {
            val resolved = resolveForRemoval(id)
            if (resolved.isEmpty()) {
                notFound.add(id)
            } else {
                for (bp in resolved) {
                    if (bp !in toRemove) {
                        toRemove.add(bp)
                        toBreakpointInfo(bp)?.let { toRemoveInfos.add(it) }
                    }
                }
            }
        }

        if (toRemove.isNotEmpty()) {
            runOnEdt { toRemove.forEach { manager.removeBreakpoint(it) } }
        }

        return RemoveBreakpointsResult(removed = toRemoveInfos, notFound = notFound)
    }

    /**
     * Resolves an ID to breakpoints for removal. Supports:
     * - Numeric timestamp ID → single breakpoint
     * - file:line → single breakpoint (throws AmbiguousBreakpointException if multiple)
     * - file path (no line) → all breakpoints in that file (purge)
     */
    private fun resolveForRemoval(id: String): List<XLineBreakpoint<*>> {
        val cleanId = sanitizeId(id)

        // Try as numeric timestamp
        val timestamp = cleanId.toLongOrNull()
        if (timestamp != null) {
            val bp = getBreakpointManager().allBreakpoints
                .filterIsInstance<XLineBreakpoint<*>>()
                .firstOrNull { it.timeStamp == timestamp }
            return listOfNotNull(bp)
        }

        // Try as file:line
        val colonIndex = cleanId.lastIndexOf(':')
        if (colonIndex > 0) {
            val filePart = cleanId.substring(0, colonIndex)
            val linePart = cleanId.substring(colonIndex + 1).toIntOrNull()
            if (linePart != null) {
                val virtualFile = resolveFile(filePart) ?: return emptyList()
                val zeroBasedLine = linePart - 1
                val matches = getBreakpointManager().allBreakpoints
                    .filterIsInstance<XLineBreakpoint<*>>()
                    .filter { it.sourcePosition?.file?.path == virtualFile.path && it.line == zeroBasedLine }

                if (matches.size > 1) {
                    throw AmbiguousBreakpointException(matches.mapNotNull { toBreakpointInfo(it) })
                }
                return matches
            }
        }

        // Try as exact file path — purge all breakpoints in this file
        val virtualFile = resolveFile(cleanId)
        if (virtualFile != null) {
            val manager = getBreakpointManager()
            return manager.allBreakpoints
                .filterIsInstance<XLineBreakpoint<*>>()
                .filter { !manager.isDefaultBreakpoint(it) }
                .filter { it.sourcePosition?.file?.path == virtualFile.path }
        }

        // Fallback: substring match on file paths (same as breakpoint_list filter)
        val manager = getBreakpointManager()
        return manager.allBreakpoints
            .filterIsInstance<XLineBreakpoint<*>>()
            .filter { !manager.isDefaultBreakpoint(it) }
            .filter { bp ->
                val path = bp.sourcePosition?.file?.path?.let { toProjectRelativePath(it) }
                path != null && path.contains(cleanId)
            }
    }

    private fun findBreakpointsAtLine(virtualFile: VirtualFile, zeroBasedLine: Int): List<BreakpointInfo> {
        val manager = getBreakpointManager()
        return manager.allBreakpoints
            .filterIsInstance<XLineBreakpoint<*>>()
            .filter { !manager.isDefaultBreakpoint(it) }
            .filter { it.sourcePosition?.file?.path == virtualFile.path && it.line == zeroBasedLine }
            .mapNotNull { toBreakpointInfo(it) }
    }

    private fun sanitizeId(id: String): String = id.trimStart('#').trim()

    private fun findBreakpointById(id: String): XLineBreakpoint<*>? {
        val cleanId = sanitizeId(id)
        val timestamp = cleanId.toLongOrNull()
        if (timestamp != null) {
            return getBreakpointManager().allBreakpoints
                .filterIsInstance<XLineBreakpoint<*>>()
                .firstOrNull { it.timeStamp == timestamp }
        }

        val colonIndex = cleanId.lastIndexOf(':')
        if (colonIndex > 0) {
            val filePart = cleanId.substring(0, colonIndex)
            val linePart = cleanId.substring(colonIndex + 1).toIntOrNull() ?: return null
            val virtualFile = resolveFile(filePart) ?: return null
            val zeroBasedLine = linePart - 1

            val matches = getBreakpointManager().allBreakpoints
                .filterIsInstance<XLineBreakpoint<*>>()
                .filter { it.sourcePosition?.file?.path == virtualFile.path && it.line == zeroBasedLine }

            return when {
                matches.isEmpty() -> null
                matches.size == 1 -> matches.first()
                else -> throw AmbiguousBreakpointException(matches.mapNotNull { toBreakpointInfo(it) })
            }
        }

        return null
    }

    fun fileExists(path: String): Boolean = resolveFile(path) != null

    private fun toProjectRelativePath(absolutePath: String): String {
        val basePath = project.basePath ?: return absolutePath
        return if (absolutePath.startsWith(basePath)) {
            absolutePath.removePrefix(basePath).removePrefix("/")
        } else {
            absolutePath
        }
    }

    private fun toBreakpointInfo(bp: XLineBreakpoint<*>): BreakpointInfo? {
        val pos = bp.sourcePosition ?: return null
        val relativePath = toProjectRelativePath(pos.file.path)
        return BreakpointInfo(
            id = bp.timeStamp.toString(),
            file = relativePath,
            line = bp.line + 1,
            enabled = bp.isEnabled,
            condition = bp.conditionExpression?.expression,
            logExpression = bp.logExpressionObject?.expression,
            suspend = bp.suspendPolicy != SuspendPolicy.NONE,
            method = bp.type.id.contains("method", ignoreCase = true),
            vendor = relativePath.startsWith("vendor/") || pos.file.path.contains("/vendor/")
        )
    }

    companion object {
        fun getInstance(project: Project): BreakpointService {
            return project.getService(BreakpointService::class.java)
        }
    }
}
