package com.github.brannow.phpstormmcp.tools

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XSuspendContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.Icon

data class FrameInfo(
    val depth: Int,
    val file: String?,
    val line: Int?,
    val name: String?,
    val isLibrary: Boolean = false
)

@Service(Service.Level.PROJECT)
class StackFrameService(private val project: Project) {

    internal interface Platform {
        fun <T> readAction(action: () -> T): T
        fun toProjectRelativePath(absolutePath: String): String
        fun computeFrames(stack: XExecutionStack): List<XStackFrame>
        fun getFramePresentation(frame: XStackFrame): String?
        fun isLibrary(file: VirtualFile): Boolean
    }

    internal var platform: Platform = object : Platform {
        override fun <T> readAction(action: () -> T): T =
            ReadAction.compute<T, Throwable> { action() }

        override fun toProjectRelativePath(absolutePath: String): String {
            val basePath = project.basePath ?: return absolutePath
            return if (absolutePath.startsWith(basePath)) {
                absolutePath.removePrefix(basePath).removePrefix("/")
            } else {
                absolutePath
            }
        }

        override fun computeFrames(stack: XExecutionStack): List<XStackFrame> {
            val future = CompletableFuture<List<XStackFrame>>()
            ApplicationManager.getApplication().invokeLater {
                val collected = mutableListOf<XStackFrame>()
                stack.computeStackFrames(1, object : XExecutionStack.XStackFrameContainer {
                    override fun addStackFrames(frames: MutableList<out XStackFrame>, last: Boolean) {
                        collected.addAll(frames)
                        if (last) future.complete(collected)
                    }

                    override fun errorOccurred(errorMessage: String) {
                        future.completeExceptionally(RuntimeException(errorMessage))
                    }

                    override fun isObsolete(): Boolean = false
                })
            }
            return future.get(5, TimeUnit.SECONDS)
        }

        override fun getFramePresentation(frame: XStackFrame): String? {
            val collector = TextCollector()
            frame.customizePresentation(collector)
            val text = collector.text
            return if (text.isBlank()) null else text
        }

        override fun isLibrary(file: VirtualFile): Boolean =
            ProjectFileIndex.getInstance(project).isInLibrary(file)
    }

    /**
     * Collect raw XStackFrame objects from a suspend context.
     * Returns frames top-down: index 0 = current (top) frame, index N = deepest caller.
     */
    fun getRawFrames(suspendContext: XSuspendContext): List<XStackFrame> {
        val stack = suspendContext.activeExecutionStack ?: return emptyList()

        val topFrame = stack.topFrame
        val remainingFrames = try {
            platform.computeFrames(stack)
        } catch (_: Exception) {
            emptyList()
        }

        val allFrames = mutableListOf<XStackFrame>()
        if (topFrame != null) allFrames.add(topFrame)
        allFrames.addAll(remainingFrames)
        return allFrames
    }

    /**
     * Convert raw XStackFrame objects to FrameInfo metadata.
     */
    fun toFrameInfoList(frames: List<XStackFrame>): List<FrameInfo> {
        return platform.readAction {
            frames.mapIndexed { idx, frame ->
                val pos = frame.sourcePosition
                FrameInfo(
                    depth = idx,
                    file = pos?.file?.path?.let { platform.toProjectRelativePath(it) },
                    line = pos?.line?.let { it + 1 },
                    name = extractFrameName(frame),
                    isLibrary = pos?.file?.let { platform.isLibrary(it) } ?: false
                )
            }
        }
    }

    /**
     * Extract stack frames from a suspend context.
     * Returns frames top-down: index 0 = current (top) frame, index N = deepest caller.
     */
    fun getStackFrames(suspendContext: XSuspendContext): List<FrameInfo> {
        return toFrameInfoList(getRawFrames(suspendContext))
    }

    /**
     * Extract function/method name from a stack frame.
     * Tries equalityObject first, falls back to customizePresentation.
     *
     * PHP/Xdebug presentation format: "WorldClass.php:7, WorldClass->foo()"
     * We extract the part after the comma: "WorldClass->foo()"
     */
    private fun extractFrameName(frame: XStackFrame): String? {
        val eq = frame.equalityObject
        if (eq is String && eq.isNotBlank()) return eq

        val presentation = platform.getFramePresentation(frame) ?: return null
        // PHP/Xdebug format: "file.php:line, ClassName->method()" or "file.php:line, {main}()"
        val commaIdx = presentation.indexOf(',')
        if (commaIdx >= 0) {
            return presentation.substring(commaIdx + 1).trim()
        }
        return presentation
    }

    companion object {
        fun getInstance(project: Project): StackFrameService =
            project.getService(StackFrameService::class.java)
    }
}

/**
 * Minimal ColoredTextContainer that just collects text fragments.
 */
private class TextCollector : ColoredTextContainer {
    private val sb = StringBuilder()
    val text: String get() = sb.toString()

    override fun append(fragment: String, attributes: SimpleTextAttributes) {
        sb.append(fragment)
    }

    override fun append(fragment: String, attributes: SimpleTextAttributes, tag: Any?) {
        sb.append(fragment)
    }

    override fun setIcon(icon: Icon?) {}
    override fun setToolTipText(text: String?) {}
}
