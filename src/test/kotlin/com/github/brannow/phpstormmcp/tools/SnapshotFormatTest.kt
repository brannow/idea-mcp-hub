package com.github.brannow.phpstormmcp.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class SnapshotFormatTest {

    data class Case(
        val name: String,
        val session: SessionInfo?,
        val source: SourceContext?,
        val variables: List<VariableInfo>?,
        val frames: List<FrameInfo>?,
        val expected: String,
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `formatSnapshot`(case: Case) {
        val result = formatSnapshot(case.session, case.source, case.variables, case.frames)
        assertEquals(case.expected, result)
    }

    companion object {
        private val sampleSession = SessionInfo(
            id = "12345",
            name = "index.php",
            status = "paused",
            currentFile = "src/WorldClass.php",
            currentLine = 22,
            active = true
        )

        private val sampleSource = SourceContext(
            file = "src/WorldClass.php",
            line = 22,
            methodName = "fooBar",
            className = "\\Brannow\\Sandbox\\WorldClass",
            formattedSource = " 20         \$engine = new TypedPatternEngine();\n" +
                    " 21         \$result = \$engine->match('PAGE55');\n" +
                    "→22         return \$bar;"
        )

        private val sampleVariables = listOf(
            VariableInfo("this", "Brannow\\Sandbox\\WorldClass", "", true),
            VariableInfo("foo", "string", "\"foo\"", false),
            VariableInfo("bar", "string", "\"foo-bar\"", false),
        )

        private val sampleFrames = listOf(
            FrameInfo(0, "src/WorldClass.php", 22, "WorldClass->fooBar()"),
            FrameInfo(1, "src/WorldClass.php", 11, "WorldClass->foo()"),
            FrameInfo(2, "src/index.php", 8, "{main}()"),
        )

        @JvmStatic
        fun cases() = listOf(
            Case(
                name = "full snapshot",
                session = sampleSession,
                source = sampleSource,
                variables = sampleVariables,
                frames = sampleFrames,
                expected = "#12345 \"index.php\" at src/WorldClass.php:22 (active)\n\n" +
                        "\\Brannow\\Sandbox\\WorldClass::fooBar() — src/WorldClass.php:22\n\n" +
                        " 20         \$engine = new TypedPatternEngine();\n" +
                        " 21         \$result = \$engine->match('PAGE55');\n" +
                        "→22         return \$bar;\n\n" +
                        "\$this = {Brannow\\Sandbox\\WorldClass}\n" +
                        "\$foo = {string} \"foo\"\n" +
                        "\$bar = {string} \"foo-bar\"\n\n" +
                        "→#0 WorldClass->fooBar() at src/WorldClass.php:22\n" +
                        " #1 WorldClass->foo() at src/WorldClass.php:11\n" +
                        " #2 {main}() at src/index.php:8"
            ),
            Case(
                name = "source + variables only (no stack)",
                session = sampleSession,
                source = sampleSource,
                variables = sampleVariables,
                frames = null,
                expected = "#12345 \"index.php\" at src/WorldClass.php:22 (active)\n\n" +
                        "\\Brannow\\Sandbox\\WorldClass::fooBar() — src/WorldClass.php:22\n\n" +
                        " 20         \$engine = new TypedPatternEngine();\n" +
                        " 21         \$result = \$engine->match('PAGE55');\n" +
                        "→22         return \$bar;\n\n" +
                        "\$this = {Brannow\\Sandbox\\WorldClass}\n" +
                        "\$foo = {string} \"foo\"\n" +
                        "\$bar = {string} \"foo-bar\""

            ),
            Case(
                name = "variables only",
                session = sampleSession,
                source = null,
                variables = sampleVariables,
                frames = null,
                expected = "#12345 \"index.php\" at src/WorldClass.php:22 (active)\n\n" +
                        "\$this = {Brannow\\Sandbox\\WorldClass}\n" +
                        "\$foo = {string} \"foo\"\n" +
                        "\$bar = {string} \"foo-bar\""

            ),
            Case(
                name = "stacktrace only",
                session = sampleSession,
                source = null,
                variables = null,
                frames = sampleFrames,
                expected = "#12345 \"index.php\" at src/WorldClass.php:22 (active)\n\n" +
                        "→#0 WorldClass->fooBar() at src/WorldClass.php:22\n" +
                        " #1 WorldClass->foo() at src/WorldClass.php:11\n" +
                        " #2 {main}() at src/index.php:8"
            ),
            Case(
                name = "session only — no include parts",
                session = sampleSession,
                source = null,
                variables = null,
                frames = null,
                expected = "#12345 \"index.php\" at src/WorldClass.php:22 (active)"
            ),
        )
    }
}
