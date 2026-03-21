package com.github.brannow.phpstormmcp.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class EvaluateFormatTest {

    // --- formatEvaluationResult ---

    data class EvalCase(
        val name: String,
        val expression: String,
        val node: VariableNode,
        val expected: String,
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("evalCases")
    fun `formatEvaluationResult`(case: EvalCase) {
        assertEquals(case.expected, formatEvaluationResult(case.expression, case.node))
    }

    // --- formatSourceHeader ---

    data class HeaderCase(
        val name: String,
        val source: SourceContext,
        val expected: String,
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("headerCases")
    fun `formatSourceHeader`(case: HeaderCase) {
        assertEquals(case.expected, formatSourceHeader(case.source))
    }

    companion object {

        @JvmStatic
        fun evalCases() = listOf(
            EvalCase(
                name = "scalar int",
                expression = "count(\$items)",
                node = VariableNode("(eval)", "int", "3", false),
                expected = "count(\$items) = {int} 3"
            ),
            EvalCase(
                name = "string result",
                expression = "\$user->getName()",
                node = VariableNode("(eval)", "string", "\"John Doe\"", false),
                expected = "\$user->getName() = {string} \"John Doe\""
            ),
            EvalCase(
                name = "object result depth 0",
                expression = "\$this->repository->findByEmail('test@example.com')",
                node = VariableNode("(eval)", "App\\Entity\\User", "", true),
                expected = "\$this->repository->findByEmail('test@example.com') = {App\\Entity\\User}"
            ),
            EvalCase(
                name = "object result with depth 1",
                expression = "\$user",
                node = VariableNode("(eval)", "App\\Entity\\User", "", true, children = listOf(
                    VariableNode("name", "string", "\"John\"", false),
                    VariableNode("email", "string", "\"john@example.com\"", false),
                    VariableNode("age", "int", "30", false),
                )),
                expected = "\$user = {App\\Entity\\User}\n" +
                        "  name = {string} \"John\"\n" +
                        "  email = {string} \"john@example.com\"\n" +
                        "  age = {int} 30"
            ),
            EvalCase(
                name = "null result",
                expression = "\$config['missing']",
                node = VariableNode("(eval)", "null", "null", false),
                expected = "\$config['missing'] = {null} null"
            ),
            EvalCase(
                name = "bool result",
                expression = "isset(\$config['debug'])",
                node = VariableNode("(eval)", "bool", "true", false),
                expected = "isset(\$config['debug']) = {bool} true"
            ),
            EvalCase(
                name = "array result with depth",
                expression = "array_keys(\$config)",
                node = VariableNode("(eval)", "array", "array(3)", true, children = listOf(
                    VariableNode("0", "string", "\"debug\"", false),
                    VariableNode("1", "string", "\"cache\"", false),
                    VariableNode("2", "string", "\"db\"", false),
                )),
                expected = "array_keys(\$config) = {array} array(3)\n" +
                        "  0 = {string} \"debug\"\n" +
                        "  1 = {string} \"cache\"\n" +
                        "  2 = {string} \"db\""
            ),
        )

        @JvmStatic
        fun headerCases(): List<HeaderCase> {
            // Reuse formatSourceContext's header logic — verify it stays consistent
            val dummySource = " 1  code"
            return listOf(
                HeaderCase(
                    name = "class + method",
                    source = SourceContext("src/Foo.php", 10, "bar", "\\App\\Foo", false, dummySource),
                    expected = "\\App\\Foo::bar() — src/Foo.php:10"
                ),
                HeaderCase(
                    name = "function only",
                    source = SourceContext("src/helpers.php", 5, "doStuff", null, false, dummySource),
                    expected = "doStuff() — src/helpers.php:5"
                ),
                HeaderCase(
                    name = "no method or class",
                    source = SourceContext("src/index.php", 1, null, null, false, dummySource),
                    expected = "src/index.php:1"
                ),
                HeaderCase(
                    name = "library file",
                    source = SourceContext("vendor/lib/Foo.php", 20, "run", "\\Lib\\Foo", true, dummySource),
                    expected = "\\Lib\\Foo::run() — vendor/lib/Foo.php:20 (library)"
                ),
            )
        }
    }
}
