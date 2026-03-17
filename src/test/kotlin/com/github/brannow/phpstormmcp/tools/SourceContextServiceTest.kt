package com.github.brannow.phpstormmcp.tools

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class SourceContextServiceTest {

    // -- Pure function tests: computeRange --

    data class RangeCase(
        val name: String,
        val currentLine0: Int,
        val lineCount: Int,
        val context: Int = 5,
        val expectedStart: Int,
        val expectedEnd: Int
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("rangeCases")
    fun `computeRange`(case: RangeCase) {
        val (start, end) = SourceContextService.computeRange(case.currentLine0, case.lineCount, case.context)
        assertEquals(case.expectedStart, start, "startLine")
        assertEquals(case.expectedEnd, end, "endLine")
    }

    // -- Pure function tests: formatSource --

    data class FormatCase(
        val name: String,
        val lines: List<String>,
        val startLine0: Int,
        val currentLine0: Int,
        val expected: String
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("formatCases")
    fun `formatSource`(case: FormatCase) {
        val result = SourceContextService.formatSource(case.lines, case.startLine0, case.currentLine0)
        assertEquals(case.expected, result)
    }

    // -- Integration tests: getSourceContext with mocked platform --

    data class ContextCase(
        val name: String,
        val filePath: String,
        val line: Int,
        val fileLines: List<String>?,   // null = document not available
        val methodName: String? = null,
        val className: String? = null,
        val isLibrary: Boolean = false,
        val expectedFile: String,
        val expectedMethodName: String? = null,
        val expectedClassName: String? = null,
        val expectedIsLibrary: Boolean = false,
        val expectedSource: String
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("contextCases")
    fun `getSourceContext`(case: ContextCase) {
        val service = buildService(case)
        val vFile = mockk<VirtualFile>()
        every { vFile.path } returns "/project/${case.filePath}"

        val result = service.getSourceContext(vFile, case.line)

        assertEquals(case.expectedFile, result.file)
        assertEquals(case.line, result.line)
        assertEquals(case.expectedMethodName, result.methodName)
        assertEquals(case.expectedClassName, result.className)
        assertEquals(case.expectedIsLibrary, result.isLibrary)
        assertEquals(case.expectedSource, result.formattedSource)
    }

    // -- formatSourceContext tests --

    data class FormatContextCase(
        val name: String,
        val ctx: SourceContext,
        val expected: String
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("formatContextCases")
    fun `formatSourceContext`(case: FormatContextCase) {
        val result = formatSourceContext(case.ctx)
        assertEquals(case.expected, result)
    }

    // -- Test infrastructure --

    private fun buildService(case: ContextCase): SourceContextService {
        val project = mockk<Project>()
        every { project.basePath } returns "/project"

        val service = SourceContextService(project)
        service.platform = object : SourceContextService.Platform {
            override fun <T> readAction(action: () -> T): T = action()
            override fun getDocument(file: VirtualFile): Document? {
                if (case.fileLines == null) return null
                return buildMockDocument(case.fileLines)
            }

            override fun findMethodName(file: VirtualFile, offset: Int): String? = case.methodName
            override fun findClassName(file: VirtualFile, offset: Int): String? = case.className
            override fun isLibrary(file: VirtualFile): Boolean = case.isLibrary
        }
        return service
    }

    private fun buildMockDocument(lines: List<String>): Document {
        val fullText = lines.joinToString("\n")
        val doc = mockk<Document>()
        every { doc.lineCount } returns lines.size
        every { doc.text } returns fullText

        // Compute offsets: each line starts after previous line + \n
        val lineStartOffsets = mutableListOf<Int>()
        var offset = 0
        for (line in lines) {
            lineStartOffsets.add(offset)
            offset += line.length + 1  // +1 for \n
        }

        for (i in lines.indices) {
            every { doc.getLineStartOffset(i) } returns lineStartOffsets[i]
            every { doc.getLineEndOffset(i) } returns lineStartOffsets[i] + lines[i].length
            every {
                doc.getText(TextRange(lineStartOffsets[i], lineStartOffsets[i] + lines[i].length))
            } returns lines[i]
        }

        return doc
    }

    companion object {
        @JvmStatic
        fun rangeCases() = listOf(
            // Normal: middle of file
            RangeCase("middle of file", currentLine0 = 10, lineCount = 30, expectedStart = 5, expectedEnd = 15),
            // Near start: clamp to 0
            RangeCase("near start", currentLine0 = 2, lineCount = 30, expectedStart = 0, expectedEnd = 7),
            // At start: line 0
            RangeCase("at start", currentLine0 = 0, lineCount = 30, expectedStart = 0, expectedEnd = 5),
            // Near end: clamp to last line
            RangeCase("near end", currentLine0 = 28, lineCount = 30, expectedStart = 23, expectedEnd = 29),
            // At end: last line
            RangeCase("at end", currentLine0 = 29, lineCount = 30, expectedStart = 24, expectedEnd = 29),
            // Small file: fewer lines than context window
            RangeCase("small file", currentLine0 = 2, lineCount = 4, expectedStart = 0, expectedEnd = 3),
            // Tiny file: 1 line
            RangeCase("single line file", currentLine0 = 0, lineCount = 1, expectedStart = 0, expectedEnd = 0),
            // Custom context
            RangeCase("custom context ±2", currentLine0 = 5, lineCount = 20, context = 2, expectedStart = 3, expectedEnd = 7),
        )

        @JvmStatic
        fun formatCases() = listOf(
            FormatCase(
                name = "basic with marker",
                lines = listOf("line A", "line B", "line C"),
                startLine0 = 4,
                currentLine0 = 5,
                expected = " 5 line A\n→6 line B\n 7 line C"
            ),
            FormatCase(
                name = "gutter width scales with line numbers",
                lines = listOf("a", "b", "c"),
                startLine0 = 98,
                currentLine0 = 99,
                expected = "  99 a\n→100 b\n 101 c"
            ),
            FormatCase(
                name = "single line",
                lines = listOf("only line"),
                startLine0 = 0,
                currentLine0 = 0,
                expected = "→1 only line"
            ),
            FormatCase(
                name = "marker at first line",
                lines = listOf("first", "second", "third"),
                startLine0 = 0,
                currentLine0 = 0,
                expected = "→1 first\n 2 second\n 3 third"
            ),
            FormatCase(
                name = "marker at last line",
                lines = listOf("first", "second", "third"),
                startLine0 = 7,
                currentLine0 = 9,
                expected = "  8 first\n  9 second\n→10 third"
            ),
        )

        @JvmStatic
        fun contextCases() = listOf(
            ContextCase(
                name = "method in class — middle of file",
                filePath = "src/Service/UserService.php",
                line = 8,
                fileLines = listOf(
                    "<?php",                            // 1
                    "namespace App\\Service;",          // 2
                    "",                                  // 3
                    "class UserService {",               // 4
                    "    public function login() {",     // 5
                    "        \$user = getUser();",       // 6
                    "        if (\$user === null) {",    // 7
                    "            return false;",         // 8  ← current
                    "        }",                         // 9
                    "        return true;",              // 10
                    "    }",                             // 11
                    "}",                                 // 12
                ),
                methodName = "login",
                className = "\\App\\Service\\UserService",
                expectedFile = "src/Service/UserService.php",
                expectedMethodName = "login",
                expectedClassName = "\\App\\Service\\UserService",
                expectedSource = "  3 \n" +
                        "  4 class UserService {\n" +
                        "  5     public function login() {\n" +
                        "  6         \$user = getUser();\n" +
                        "  7         if (\$user === null) {\n" +
                        "→ 8             return false;\n" +
                        "  9         }\n" +
                        " 10         return true;\n" +
                        " 11     }\n" +
                        " 12 }"
            ),
            ContextCase(
                name = "top-level code — no method or class",
                filePath = "src/index.php",
                line = 2,
                fileLines = listOf(
                    "<?php",                 // 1
                    "require 'vendor/autoload.php';", // 2  ← current
                    "\$app = new App();",    // 3
                    "\$app->run();",         // 4
                ),
                expectedFile = "src/index.php",
                expectedSource = " 1 <?php\n" +
                        "→2 require 'vendor/autoload.php';\n" +
                        " 3 \$app = new App();\n" +
                        " 4 \$app->run();"
            ),
            ContextCase(
                name = "near start — clamps to line 1",
                filePath = "src/test.php",
                line = 1,
                fileLines = listOf(
                    "<?php",           // 1  ← current
                    "echo 'hello';",   // 2
                    "echo 'world';",   // 3
                ),
                expectedFile = "src/test.php",
                expectedSource = "→1 <?php\n" +
                        " 2 echo 'hello';\n" +
                        " 3 echo 'world';"
            ),
            ContextCase(
                name = "document not available",
                filePath = "src/binary.bin",
                line = 5,
                fileLines = null,
                expectedFile = "src/binary.bin",
                expectedSource = "(file content not available)"
            ),
            ContextCase(
                name = "empty file",
                filePath = "src/empty.php",
                line = 1,
                fileLines = emptyList(),
                expectedFile = "src/empty.php",
                expectedSource = "(empty file)"
            ),
            ContextCase(
                name = "library code — isLibrary flag set",
                filePath = "vendor/acme/lib/src/Foo.php",
                line = 3,
                fileLines = listOf(
                    "<?php",                         // 1
                    "class Foo {",                   // 2
                    "    public function bar() {}",  // 3  ← current
                    "}",                             // 4
                ),
                methodName = "bar",
                className = "\\Acme\\Foo",
                isLibrary = true,
                expectedFile = "vendor/acme/lib/src/Foo.php",
                expectedMethodName = "bar",
                expectedClassName = "\\Acme\\Foo",
                expectedIsLibrary = true,
                expectedSource = " 1 <?php\n" +
                        " 2 class Foo {\n" +
                        "→3     public function bar() {}\n" +
                        " 4 }"
            ),
        )

        @JvmStatic
        fun formatContextCases() = listOf(
            FormatContextCase(
                name = "full context — class + method",
                ctx = SourceContext(
                    file = "src/Service/UserService.php",
                    line = 42,
                    methodName = "authenticate",
                    className = "\\App\\Service\\UserService",
                    formattedSource = "→42 return false;"
                ),
                expected = "\\App\\Service\\UserService::authenticate() — src/Service/UserService.php:42\n\n→42 return false;"
            ),
            FormatContextCase(
                name = "function only — no class",
                ctx = SourceContext(
                    file = "src/helpers.php",
                    line = 10,
                    methodName = "formatDate",
                    className = null,
                    formattedSource = "→10 return date('Y-m-d');"
                ),
                expected = "formatDate() — src/helpers.php:10\n\n→10 return date('Y-m-d');"
            ),
            FormatContextCase(
                name = "top-level — no method or class",
                ctx = SourceContext(
                    file = "src/index.php",
                    line = 3,
                    methodName = null,
                    className = null,
                    formattedSource = "→3 \$app->run();"
                ),
                expected = "src/index.php:3\n\n→3 \$app->run();"
            ),
            FormatContextCase(
                name = "library code — (library) annotation in header",
                ctx = SourceContext(
                    file = "vendor/acme/lib/src/Foo.php",
                    line = 10,
                    methodName = "bar",
                    className = "\\Acme\\Foo",
                    isLibrary = true,
                    formattedSource = "→10 return \$this->value;"
                ),
                expected = "\\Acme\\Foo::bar() — vendor/acme/lib/src/Foo.php:10 (library)\n\n→10 return \$this->value;"
            ),
        )
    }
}
