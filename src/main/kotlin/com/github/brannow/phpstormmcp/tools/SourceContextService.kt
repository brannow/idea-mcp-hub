package com.github.brannow.phpstormmcp.tools

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass

/**
 * Source context around a debug position — orientation for the agent,
 * not a code viewer. Shows ±5 lines with the current line marked.
 */

data class SourceContext(
    val file: String,
    val line: Int,
    val methodName: String? = null,
    val className: String? = null,
    val isLibrary: Boolean = false,
    val formattedSource: String
)

@Service(Service.Level.PROJECT)
class SourceContextService(private val project: Project) {

    internal interface Platform {
        fun <T> readAction(action: () -> T): T
        fun getDocument(file: VirtualFile): Document?
        fun findMethodName(file: VirtualFile, offset: Int): String?
        fun findClassName(file: VirtualFile, offset: Int): String?
        fun isLibrary(file: VirtualFile): Boolean
    }

    internal var platform: Platform = object : Platform {
        override fun <T> readAction(action: () -> T): T =
            ReadAction.compute<T, Throwable> { action() }

        override fun getDocument(file: VirtualFile): Document? =
            FileDocumentManager.getInstance().getDocument(file)

        override fun findMethodName(file: VirtualFile, offset: Int): String? {
            val psiFile = PsiManager.getInstance(project).findFile(file) ?: return null
            val element = psiFile.findElementAt(offset) ?: return null
            val method = PsiTreeUtil.getParentOfType(element, Method::class.java, Function::class.java)
            return when (method) {
                is Method -> method.name
                is Function -> method.name
                else -> null
            }
        }

        override fun findClassName(file: VirtualFile, offset: Int): String? {
            val psiFile = PsiManager.getInstance(project).findFile(file) ?: return null
            val element = psiFile.findElementAt(offset) ?: return null
            val phpClass = PsiTreeUtil.getParentOfType(element, PhpClass::class.java)
            return phpClass?.fqn
        }

        override fun isLibrary(file: VirtualFile): Boolean =
            ProjectFileIndex.getInstance(project).isInLibrary(file)
    }

    companion object {
        private const val CONTEXT_LINES = 5

        fun getInstance(project: Project): SourceContextService =
            project.getService(SourceContextService::class.java)

        /**
         * Pure function: compute which lines to extract.
         * Returns (startLine, endLine) both 0-based inclusive.
         */
        fun computeRange(currentLine0: Int, lineCount: Int, context: Int = CONTEXT_LINES): Pair<Int, Int> {
            val start = maxOf(0, currentLine0 - context)
            val end = minOf(lineCount - 1, currentLine0 + context)
            return start to end
        }

        /**
         * Pure function: format source lines with line numbers and current-line marker.
         * Lines are 0-based indexed, currentLine0 is the 0-based current line.
         */
        fun formatSource(lines: List<String>, startLine0: Int, currentLine0: Int): String {
            val maxLineNum = startLine0 + lines.size
            val gutterWidth = maxLineNum.toString().length
            return lines.mapIndexed { idx, text ->
                val lineNum = startLine0 + idx + 1  // 1-based for display
                val marker = if (startLine0 + idx == currentLine0) "→" else " "
                val num = lineNum.toString().padStart(gutterWidth)
                "$marker$num $text"
            }.joinToString("\n")
        }
    }

    /**
     * Get source context around a position.
     * @param file the source file
     * @param line 1-based line number
     */
    fun getSourceContext(file: VirtualFile, line: Int): SourceContext {
        val relativePath = toProjectRelativePath(file.path)
        val line0 = line - 1

        // All document, PSI, and index access wrapped in a single read action
        return platform.readAction {
            val library = platform.isLibrary(file)

            val document = platform.getDocument(file)
                ?: return@readAction SourceContext(
                    file = relativePath,
                    line = line,
                    isLibrary = library,
                    formattedSource = "(file content not available)"
                )

            if (document.lineCount == 0) {
                return@readAction SourceContext(
                    file = relativePath,
                    line = line,
                    isLibrary = library,
                    formattedSource = "(empty file)"
                )
            }

            val clampedLine0 = line0.coerceIn(0, document.lineCount - 1)
            val (startLine0, endLine0) = computeRange(clampedLine0, document.lineCount)
            val offset = document.getLineStartOffset(clampedLine0)

            val methodName = platform.findMethodName(file, offset)
            val className = platform.findClassName(file, offset)

            val lines = (startLine0..endLine0).map { lineIdx ->
                val startOffset = document.getLineStartOffset(lineIdx)
                val endOffset = document.getLineEndOffset(lineIdx)
                document.getText(com.intellij.openapi.util.TextRange(startOffset, endOffset))
            }

            val formatted = formatSource(lines, startLine0, clampedLine0)

            SourceContext(
                file = relativePath,
                line = line,
                methodName = methodName,
                className = className,
                isLibrary = library,
                formattedSource = formatted
            )
        }
    }

    private fun toProjectRelativePath(absolutePath: String): String {
        val basePath = project.basePath ?: return absolutePath
        return if (absolutePath.startsWith(basePath)) {
            absolutePath.removePrefix(basePath).removePrefix("/")
        } else {
            absolutePath
        }
    }
}
