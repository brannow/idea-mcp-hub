package com.github.brannow.phpstormmcp.tools

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.CompletableFuture

@Serializable
data class BreakpointInfo(
    val id: String,
    val file: String,
    val line: Int,
    val enabled: Boolean,
    val condition: String? = null,
    val logExpression: String? = null,
    val suspend: Boolean = true
)

@Service(Service.Level.PROJECT)
class BreakpointService(private val project: Project) {

    private fun getBreakpointManager() =
        XDebuggerManager.getInstance(project).breakpointManager

    private fun findLineBreakpointType(): XLineBreakpointType<*>? {
        return XDebuggerUtil.getInstance().lineBreakpointTypes.firstOrNull {
            it.id.contains("php", ignoreCase = true)
        }
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
    ): BreakpointInfo {
        val type = findLineBreakpointType()
            ?: throw IllegalStateException("PHP line breakpoint type not available. Is the PHP plugin active?")

        val virtualFile = resolveFile(file)
            ?: throw IllegalArgumentException("File not found: $file")

        val fileUrl = virtualFile.url
        val zeroBasedLine = line - 1

        val future = CompletableFuture<BreakpointInfo>()

        ApplicationManager.getApplication().invokeLater {
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

            future.complete(toBreakpointInfo(bp)!!)
        }

        return future.get()
    }

    fun updateBreakpoint(
        id: String,
        enabled: Boolean? = null,
        condition: String? = null,
        logExpression: String? = null,
        suspend: Boolean? = null
    ): BreakpointInfo {
        val bp = findBreakpointById(id)
            ?: throw IllegalArgumentException("Breakpoint not found: $id")

        val future = CompletableFuture<BreakpointInfo>()

        ApplicationManager.getApplication().invokeLater {
            if (enabled != null) {
                bp.isEnabled = enabled
            }
            // Use sentinel: explicit empty string clears, null means don't change
            if (condition != null) {
                bp.setCondition(condition.ifEmpty { null })
            }
            if (logExpression != null) {
                bp.setLogExpression(logExpression.ifEmpty { null })
            }
            if (suspend != null) {
                bp.suspendPolicy = if (suspend) SuspendPolicy.ALL else SuspendPolicy.NONE
            }

            future.complete(toBreakpointInfo(bp)!!)
        }

        return future.get()
    }

    fun removeBreakpoints(ids: List<String>? = null): Int {
        val manager = getBreakpointManager()

        val future = CompletableFuture<Int>()

        ApplicationManager.getApplication().invokeLater {
            if (ids.isNullOrEmpty()) {
                // No IDs → remove all
                val breakpoints = manager.allBreakpoints
                    .filter { !manager.isDefaultBreakpoint(it) }
                val count = breakpoints.size
                breakpoints.forEach { manager.removeBreakpoint(it) }
                future.complete(count)
            } else {
                // Remove specific IDs
                var removed = 0
                val notFound = mutableListOf<String>()
                for (id in ids) {
                    val bp = findBreakpointById(id)
                    if (bp != null) {
                        manager.removeBreakpoint(bp)
                        removed++
                    } else {
                        notFound.add(id)
                    }
                }
                if (notFound.isNotEmpty() && removed == 0) {
                    future.completeExceptionally(
                        IllegalArgumentException("Breakpoint(s) not found: ${notFound.joinToString()}")
                    )
                } else {
                    future.complete(removed)
                }
            }
        }

        return future.get()
    }

    private fun findBreakpointById(id: String): XLineBreakpoint<*>? {
        // Try as numeric timestamp ID
        val timestamp = id.toLongOrNull()
        if (timestamp != null) {
            return getBreakpointManager().allBreakpoints
                .filterIsInstance<XLineBreakpoint<*>>()
                .firstOrNull { it.timeStamp == timestamp }
        }

        // Try as file:line reference
        val colonIndex = id.lastIndexOf(':')
        if (colonIndex > 0) {
            val filePart = id.substring(0, colonIndex)
            val linePart = id.substring(colonIndex + 1).toIntOrNull() ?: return null
            val virtualFile = resolveFile(filePart) ?: return null
            val zeroBasedLine = linePart - 1

            return getBreakpointManager().allBreakpoints
                .filterIsInstance<XLineBreakpoint<*>>()
                .firstOrNull {
                    it.sourcePosition?.file?.path == virtualFile.path && it.line == zeroBasedLine
                }
        }

        return null
    }

    private fun resolveFile(path: String): VirtualFile? {
        // Try as absolute path first
        LocalFileSystem.getInstance().findFileByPath(path)?.let { return it }

        // Try as project-relative path
        val basePath = project.basePath ?: return null
        val resolved = "$basePath/$path"
        return LocalFileSystem.getInstance().findFileByPath(resolved)
    }

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
        return BreakpointInfo(
            id = bp.timeStamp.toString(),
            file = toProjectRelativePath(pos.file.path),
            line = bp.line + 1,  // 0-based → 1-based
            enabled = bp.isEnabled,
            condition = bp.conditionExpression?.expression,
            logExpression = bp.logExpressionObject?.expression,
            suspend = bp.suspendPolicy != SuspendPolicy.NONE
        )
    }

    companion object {
        val json = Json { prettyPrint = true }

        fun getInstance(project: Project): BreakpointService {
            return project.getService(BreakpointService::class.java)
        }
    }
}
