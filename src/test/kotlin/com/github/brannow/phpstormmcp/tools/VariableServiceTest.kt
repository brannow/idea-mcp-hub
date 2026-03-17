package com.github.brannow.phpstormmcp.tools

import com.intellij.openapi.project.Project
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueContainer
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class VariableServiceTest {

    data class VarState(
        val name: String,
        val type: String?,
        val value: String,
        val hasChildren: Boolean = false
    )

    data class Case(
        val name: String,
        val variables: List<VarState>,
        val expectedVariables: List<VariableInfo>,
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `getVariables`(case: Case) {
        val service = buildService(case)
        val frame = mockk<XStackFrame>()
        val result = service.getVariables(frame)
        assertEquals(case.expectedVariables, result)
    }

    // --- formatVariables tests ---

    data class FormatCase(
        val name: String,
        val variables: List<VariableInfo>,
        val expected: String,
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("formatCases")
    fun `formatVariables`(case: FormatCase) {
        assertEquals(case.expected, formatVariables(case.variables))
    }

    // --- filterGlobals tests ---

    data class FilterCase(
        val name: String,
        val variables: List<VariableInfo>,
        val includeGlobals: Boolean,
        val expectedNames: List<String>,
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("filterCases")
    fun `filterGlobals`(case: FilterCase) {
        val result = filterGlobals(case.variables, case.includeGlobals)
        assertEquals(case.expectedNames, result.map { it.name })
    }

    // --- formatVariable tests ---

    data class FormatVarCase(
        val name: String,
        val variable: VariableInfo,
        val expected: String,
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("formatVarCases")
    fun `formatVariable`(case: FormatVarCase) {
        assertEquals(case.expected, formatVariable(case.variable))
    }

    // --- parsePath tests ---

    data class ParsePathCase(
        val name: String,
        val path: String,
        val expected: List<String>,
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("parsePathCases")
    fun `parsePath`(case: ParsePathCase) {
        assertEquals(case.expected, VariableService.parsePath(case.path))
    }

    // --- matchesSegment tests ---

    data class MatchCase(
        val name: String,
        val childName: String,
        val segment: String,
        val expected: Boolean,
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("matchCases")
    fun `matchesSegment`(case: MatchCase) {
        assertEquals(case.expected, VariableService.matchesSegment(case.childName, case.segment))
    }

    // --- getVariableDetail tests ---

    data class DetailCase(
        val name: String,
        val tree: Map<String, MockVar>,  // name → MockVar (recursive tree)
        val path: String,
        val depth: Int = 1,
        val expected: VariableNode,
    )

    data class MockVar(
        val type: String?,
        val value: String,
        val hasChildren: Boolean = false,
        val children: Map<String, MockVar>? = null,
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("detailCases")
    fun `getVariableDetail`(case: DetailCase) {
        val service = buildDetailService(case.tree)
        val frame = mockk<XStackFrame>()
        val result = service.getVariableDetail(frame, case.path, case.depth)
        assertEquals(case.expected, result)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("circularCases")
    fun `getVariableDetail — circular reference`(case: DetailCase) {
        val service = buildDetailService(case.tree)
        val frame = mockk<XStackFrame>()
        val result = service.getVariableDetail(frame, case.path, case.depth)
        assertEquals(case.expected, result)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("circularArrayCases")
    fun `getVariableDetail — arrays don't trigger circular detection`(case: DetailCase) {
        val service = buildDetailService(case.tree)
        val frame = mockk<XStackFrame>()
        val result = service.getVariableDetail(frame, case.path, case.depth)
        assertEquals(case.expected, result)
    }

    @Test
    fun `isObjectType — scalars and arrays are not objects`() {
        listOf("array", "int", "string", "float", "bool", "null", "Array", "STRING").forEach {
            assertEquals(false, VariableService.isObjectType(it), "Expected '$it' to NOT be an object type")
        }
    }

    @Test
    fun `isObjectType — class names are objects`() {
        listOf("SequenceNode", "TypedPatternEngine\\Nodes\\SequenceNode", "ServerRequest", "stdClass").forEach {
            assertEquals(true, VariableService.isObjectType(it), "Expected '$it' to be an object type")
        }
    }

    @Test
    fun `getVariableDetail — explicit path through circular reference works`() {
        // The agent should be able to navigate INTO a circular reference via explicit path.
        // Cycle detection only blocks automatic expansion, not path navigation.
        val tree = mapOf(
            "ast" to MockVar("SequenceNode", "", hasChildren = true, children = mapOf(
                "children" to MockVar("array", "array(1)", hasChildren = true, children = mapOf(
                    "0" to MockVar("LiteralNode", "", hasChildren = true, children = mapOf(
                        "text" to MockVar("string", "\"PAGE\""),
                        "parent" to MockVar("SequenceNode", "", hasChildren = true, children = mapOf(
                            "children" to MockVar("array", "array(1)", hasChildren = true, children = mapOf(
                                "0" to MockVar("LiteralNode", "", hasChildren = true, children = mapOf(
                                    "text" to MockVar("string", "\"PAGE-DEEP\""),
                                )),
                            )),
                        )),
                    )),
                )),
            )),
        )
        val service = buildDetailService(tree)
        val frame = mockk<XStackFrame>()

        // Navigate through the circular reference via explicit path
        val result = service.getVariableDetail(frame, "\$ast.children.0.parent.children.0.text", 0)
        assertEquals(VariableNode("text", "string", "\"PAGE-DEEP\"", false), result)
    }

    @Test
    fun `getVariableDetail — ambiguous inherited property`() {
        val tree = mapOf("node" to MockVar("LiteralNode", "", hasChildren = true, children = mapOf(
            "*AstNode*parent" to MockVar("null", "null"),
            "*NestedAstNode*parent" to MockVar("null", "null"),
            "text" to MockVar("string", "\"PAGE\""),
        )))
        val service = buildDetailService(tree)
        val frame = mockk<XStackFrame>()
        val ex = assertThrows(VariablePathException::class.java) {
            service.getVariableDetail(frame, "\$node.parent")
        }
        assert(ex.message!!.contains("ambiguous")) { "Expected ambiguous error, got: ${ex.message}" }
        assert(ex.message!!.contains("*AstNode*parent")) { "Expected option listed, got: ${ex.message}" }
    }

    @Test
    fun `getVariableDetail — path not found`() {
        val tree = mapOf("foo" to MockVar("string", "\"bar\""))
        val service = buildDetailService(tree)
        val frame = mockk<XStackFrame>()
        val ex = assertThrows(VariablePathException::class.java) {
            service.getVariableDetail(frame, "\$nonexistent")
        }
        assertEquals("'\$nonexistent' not found, available: foo", ex.message)
    }

    // --- formatVariableDetail tests ---

    data class FormatDetailCase(
        val name: String,
        val node: VariableNode,
        val path: String,
        val expected: String,
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("formatDetailCases")
    fun `formatVariableDetail`(case: FormatDetailCase) {
        assertEquals(case.expected, formatVariableDetail(case.node, case.path))
    }

    // -- Infrastructure --

    /**
     * Build a VariableService with a mock tree for variable detail tests.
     * Each XValue mock is keyed by its identity so computeChildren/computePresentation
     * can look up the right MockVar.
     */
    private fun buildDetailService(tree: Map<String, MockVar>): VariableService {
        val project = mockk<Project>()
        every { project.basePath } returns "/project"

        // Map XValue instances to their MockVar data
        val valueMap = mutableMapOf<XValue, MockVar>()

        fun createXValues(vars: Map<String, MockVar>): List<Pair<String, XValue>> {
            return vars.map { (name, mockVar) ->
                val xValue = mockk<XValue>()
                valueMap[xValue] = mockVar
                name to xValue
            }
        }

        // Pre-create root level
        val rootValues = createXValues(tree)

        val service = VariableService(project)
        service.platform = object : VariableService.Platform {
            override fun computeChildren(container: XValueContainer): List<Pair<String, XValue>> {
                if (container is XStackFrame) return rootValues
                val mockVar = valueMap[container as XValue]
                    ?: return emptyList()
                val children = mockVar.children ?: return emptyList()
                return createXValues(children)
            }

            override fun computePresentation(value: XValue): VariableService.VariablePresentation {
                val mockVar = valueMap[value]
                    ?: return VariableService.VariablePresentation(null, "", false)
                return VariableService.VariablePresentation(mockVar.type, mockVar.value, mockVar.hasChildren)
            }
        }
        return service
    }

    private fun buildService(case: Case): VariableService {
        val project = mockk<Project>()
        every { project.basePath } returns "/project"

        val service = VariableService(project)
        service.platform = object : VariableService.Platform {
            override fun computeChildren(container: XValueContainer): List<Pair<String, XValue>> {
                return case.variables.map { v ->
                    val xValue = mockk<XValue>()
                    v.name to xValue
                }
            }

            override fun computePresentation(value: XValue): VariableService.VariablePresentation {
                // Match by position — values come back in same order as case.variables
                val idx = case.variables.indexOfFirst { v ->
                    // Find the first variable whose XValue mock hasn't been consumed yet
                    true
                }
                // Since we can't easily match mocks, use a counter
                return presentationForNext()
            }

            private var presentationIdx = 0
            private fun presentationForNext(): VariableService.VariablePresentation {
                val v = case.variables[presentationIdx++]
                return VariableService.VariablePresentation(v.type, v.value, v.hasChildren)
            }
        }
        return service
    }

    companion object {
        @JvmStatic
        fun cases() = listOf(
            Case(
                name = "mixed variable types",
                variables = listOf(
                    VarState("count", "int", "42"),
                    VarState("name", "string", "\"hello world\""),
                    VarState("items", "array", "array(15)", hasChildren = true),
                    VarState("request", "ServerRequest", "{ServerRequest}", hasChildren = true),
                    VarState("foo", "null", "null"),
                ),
                expectedVariables = listOf(
                    VariableInfo("count", "int", "42", false),
                    VariableInfo("name", "string", "\"hello world\"", false),
                    VariableInfo("items", "array", "array(15)", true),
                    VariableInfo("request", "ServerRequest", "{ServerRequest}", true),
                    VariableInfo("foo", "null", "null", false),
                )
            ),
            Case(
                name = "no variables",
                variables = emptyList(),
                expectedVariables = emptyList()
            ),
            Case(
                name = "single scalar",
                variables = listOf(
                    VarState("x", "int", "1"),
                ),
                expectedVariables = listOf(
                    VariableInfo("x", "int", "1", false),
                )
            ),
        )

        private val mixedWithGlobals = listOf(
            VariableInfo("this", "WorldClass", "", true),
            VariableInfo("foo", "string", "\"bar\"", false),
            VariableInfo("_ENV", null, "", true),
            VariableInfo("_SERVER", null, "", true),
            VariableInfo("_GET", null, "", true),
            VariableInfo("_POST", null, "", true),
            VariableInfo("_SESSION", null, "", true),
            VariableInfo("_COOKIE", null, "", true),
            VariableInfo("_FILES", null, "", true),
            VariableInfo("_REQUEST", null, "", true),
            VariableInfo("GLOBALS", null, "", true),
        )

        @JvmStatic
        fun filterCases() = listOf(
            FilterCase(
                name = "globals hidden by default",
                variables = mixedWithGlobals,
                includeGlobals = false,
                expectedNames = listOf("this", "foo")
            ),
            FilterCase(
                name = "globals shown when requested",
                variables = mixedWithGlobals,
                includeGlobals = true,
                expectedNames = mixedWithGlobals.map { it.name }
            ),
            FilterCase(
                name = "no variables — stays empty",
                variables = emptyList(),
                includeGlobals = false,
                expectedNames = emptyList()
            ),
        )

        @JvmStatic
        fun matchCases() = listOf(
            MatchCase("exact match", "parent", "parent", true),
            MatchCase("dollar prefix", "\$result", "result", true),
            MatchCase("xdebug inherited", "*TypedPatternEngine\\Nodes\\AstNode*parent", "parent", true),
            MatchCase("xdebug inherited short", "*AstNode*regex", "regex", true),
            MatchCase("exact xdebug name", "*AstNode*regex", "*AstNode*regex", true),
            MatchCase("no match", "children", "parent", false),
            MatchCase("partial no match", "parent_id", "parent", false),
            MatchCase("single star not xdebug", "*starred", "starred", false),
        )

        @JvmStatic
        fun formatCases() = listOf(
            FormatCase(
                name = "empty",
                variables = emptyList(),
                expected = "(no variables)"
            ),
            FormatCase(
                name = "mixed types",
                variables = listOf(
                    VariableInfo("count", "int", "42", false),
                    VariableInfo("name", "string", "\"hello\"", false),
                    VariableInfo("items", "array", "array(3)", true),
                    VariableInfo("request", "ServerRequest", "{ServerRequest}", true),
                    VariableInfo("foo", "null", "null", false),
                ),
                expected = "\$count = {int} 42\n" +
                        "\$name = {string} \"hello\"\n" +
                        "\$items = array(3)\n" +
                        "\$request = {ServerRequest}\n" +
                        "\$foo = {null} null"
            ),
        )

        @JvmStatic
        fun parsePathCases() = listOf(
            ParsePathCase("simple variable", "\$engine", listOf("engine")),
            ParsePathCase("nested path", "\$engine.pattern", listOf("engine", "pattern")),
            ParsePathCase("deep path", "\$items.0.name", listOf("items", "0", "name")),
            ParsePathCase("no dollar sign", "engine.pattern", listOf("engine", "pattern")),
            ParsePathCase("empty", "", emptyList()),
            ParsePathCase("just dollar sign", "$", emptyList()),
        )

        @JvmStatic
        fun detailCases(): List<DetailCase> {
            val engineTree = mapOf(
                "engine" to MockVar(
                    "CompiledPattern", "", hasChildren = true,
                    children = mapOf(
                        "pattern" to MockVar("string", "\"PAGE{id:int}\""),
                        "regex" to MockVar("string", "\"/^PAGE(?P<g1>\\d+)\\\$/\""),
                        "ast" to MockVar("SequenceNode", "", hasChildren = true, children = mapOf(
                            "children" to MockVar("array", "array(2)", hasChildren = true),
                        )),
                    )
                ),
                "foo" to MockVar("string", "\"bar\""),
            )

            return listOf(
                DetailCase(
                    name = "expand top-level object depth 1",
                    tree = engineTree,
                    path = "\$engine",
                    depth = 1,
                    expected = VariableNode(
                        "engine", "CompiledPattern", "", true,
                        children = listOf(
                            VariableNode("pattern", "string", "\"PAGE{id:int}\"", false),
                            VariableNode("regex", "string", "\"/^PAGE(?P<g1>\\d+)\\\$/\"", false),
                            VariableNode("ast", "SequenceNode", "", true),
                        )
                    )
                ),
                DetailCase(
                    name = "expand top-level object depth 2",
                    tree = engineTree,
                    path = "\$engine",
                    depth = 2,
                    expected = VariableNode(
                        "engine", "CompiledPattern", "", true,
                        children = listOf(
                            VariableNode("pattern", "string", "\"PAGE{id:int}\"", false),
                            VariableNode("regex", "string", "\"/^PAGE(?P<g1>\\d+)\\\$/\"", false),
                            VariableNode("ast", "SequenceNode", "", true, children = listOf(
                                VariableNode("children", "array", "array(2)", true),
                            )),
                        )
                    )
                ),
                DetailCase(
                    name = "drill into nested property",
                    tree = engineTree,
                    path = "\$engine.ast",
                    depth = 1,
                    expected = VariableNode(
                        "ast", "SequenceNode", "", true,
                        children = listOf(
                            VariableNode("children", "array", "array(2)", true),
                        )
                    )
                ),
                DetailCase(
                    name = "scalar — no expansion",
                    tree = engineTree,
                    path = "\$foo",
                    depth = 1,
                    expected = VariableNode("foo", "string", "\"bar\"", false)
                ),
                // Xdebug inherited property navigation
                DetailCase(
                    name = "navigate through xdebug inherited property name",
                    tree = mapOf(
                        "node" to MockVar("LiteralNode", "", hasChildren = true, children = mapOf(
                            "*TypedPatternEngine\\Nodes\\AstNode*regex" to MockVar("string", "\"PAGE\""),
                            "*TypedPatternEngine\\Nodes\\AstNode*parent" to MockVar("null", "null"),
                            "text" to MockVar("string", "\"PAGE\""),
                        )),
                    ),
                    path = "\$node.regex",
                    depth = 0,
                    expected = VariableNode("regex", "string", "\"PAGE\"", false)
                ),
                DetailCase(
                    name = "navigate with full xdebug property name",
                    tree = mapOf(
                        "node" to MockVar("LiteralNode", "", hasChildren = true, children = mapOf(
                            "*AstNode*regex" to MockVar("string", "\"PAGE\""),
                            "text" to MockVar("string", "\"PAGE\""),
                        )),
                    ),
                    path = "\$node.*AstNode*regex",
                    depth = 0,
                    expected = VariableNode("*AstNode*regex", "string", "\"PAGE\"", false)
                ),
            )
        }

        @JvmStatic
        fun circularCases(): List<DetailCase> {
            // Simulates: SequenceNode.children[0].parent → back to SequenceNode
            val circularTree = mapOf(
                "ast" to MockVar(
                    "SequenceNode", "", hasChildren = true,
                    children = mapOf(
                        "regex" to MockVar("string", "\"PAGE(?P<g1>\\d+)\""),
                        "parent" to MockVar("null", "null"),
                        "children" to MockVar("array", "array(1)", hasChildren = true, children = mapOf(
                            "0" to MockVar("LiteralNode", "", hasChildren = true, children = mapOf(
                                "text" to MockVar("string", "\"PAGE\""),
                                // parent points back to a SequenceNode — cycle!
                                "parent" to MockVar("SequenceNode", "", hasChildren = true, children = mapOf(
                                    "regex" to MockVar("string", "\"PAGE(?P<g1>\\d+)\""),
                                    "children" to MockVar("array", "array(1)", hasChildren = true),
                                )),
                            )),
                        )),
                    )
                ),
            )

            return listOf(
                DetailCase(
                    name = "circular reference detected at depth 3 — parent back-reference",
                    tree = circularTree,
                    path = "\$ast",
                    depth = 5, // would explode without detection
                    expected = VariableNode(
                        "ast", "SequenceNode", "", true,
                        children = listOf(
                            VariableNode("regex", "string", "\"PAGE(?P<g1>\\d+)\"", false),
                            VariableNode("parent", "null", "null", false),
                            VariableNode("children", "array", "array(1)", true, children = listOf(
                                VariableNode("0", "LiteralNode", "", true, children = listOf(
                                    VariableNode("text", "string", "\"PAGE\"", false),
                                    // This SequenceNode is detected as circular — ancestor chain has SequenceNode
                                    VariableNode("parent", "SequenceNode", "", true, children = null, circular = true),
                                )),
                            )),
                        )
                    )
                ),
                DetailCase(
                    name = "depth 1 — no cycle reached yet",
                    tree = circularTree,
                    path = "\$ast",
                    depth = 1,
                    expected = VariableNode(
                        "ast", "SequenceNode", "", true,
                        children = listOf(
                            VariableNode("regex", "string", "\"PAGE(?P<g1>\\d+)\"", false),
                            VariableNode("parent", "null", "null", false),
                            VariableNode("children", "array", "array(1)", true),
                        )
                    )
                ),
            )
        }

        @JvmStatic
        fun circularArrayCases(): List<DetailCase> {
            // Arrays should NOT trigger circular reference detection
            val nestedArrayTree = mapOf(
                "data" to MockVar("array", "array(1)", hasChildren = true, children = mapOf(
                    "0" to MockVar("array", "array(1)", hasChildren = true, children = mapOf(
                        "0" to MockVar("array", "array(1)", hasChildren = true, children = mapOf(
                            "0" to MockVar("string", "\"deep\""),
                        )),
                    )),
                )),
            )

            return listOf(
                DetailCase(
                    name = "nested arrays — no false positive",
                    tree = nestedArrayTree,
                    path = "\$data",
                    depth = 5,
                    expected = VariableNode(
                        "data", "array", "array(1)", true,
                        children = listOf(
                            VariableNode("0", "array", "array(1)", true, children = listOf(
                                VariableNode("0", "array", "array(1)", true, children = listOf(
                                    VariableNode("0", "string", "\"deep\"", false),
                                )),
                            )),
                        )
                    )
                ),
            )
        }

        @JvmStatic
        fun formatDetailCases() = listOf(
            FormatDetailCase(
                name = "scalar with type",
                node = VariableNode("foo", "string", "\"bar\"", false),
                path = "\$foo",
                expected = "\$foo = {string} \"bar\""
            ),
            FormatDetailCase(
                name = "scalar without type",
                node = VariableNode("foo", null, "42", false),
                path = "\$foo",
                expected = "\$foo = 42"
            ),
            FormatDetailCase(
                name = "object with children depth 1",
                node = VariableNode("engine", "CompiledPattern", "", true, children = listOf(
                    VariableNode("pattern", "string", "\"PAGE{id:int}\"", false),
                    VariableNode("regex", "string", "\"/^PAGE/\"", false),
                )),
                path = "\$engine",
                expected = "\$engine = {CompiledPattern}\n" +
                        "  pattern = {string} \"PAGE{id:int}\"\n" +
                        "  regex = {string} \"/^PAGE/\""
            ),
            FormatDetailCase(
                name = "nested depth 2",
                node = VariableNode("engine", "CompiledPattern", "", true, children = listOf(
                    VariableNode("pattern", "string", "\"PAGE\"", false),
                    VariableNode("ast", "SequenceNode", "", true, children = listOf(
                        VariableNode("children", "array", "array(2)", true),
                    )),
                )),
                path = "\$engine",
                expected = "\$engine = {CompiledPattern}\n" +
                        "  pattern = {string} \"PAGE\"\n" +
                        "  ast = {SequenceNode}\n" +
                        "    children = {array} array(2)"
            ),
            FormatDetailCase(
                name = "drilled path",
                node = VariableNode("ast", "SequenceNode", "", true, children = listOf(
                    VariableNode("children", "array", "array(2)", true),
                )),
                path = "\$engine.ast",
                expected = "\$engine.ast = {SequenceNode}\n" +
                        "  children = {array} array(2)"
            ),
            FormatDetailCase(
                name = "circular reference child",
                node = VariableNode("node", "LiteralNode", "", true, children = listOf(
                    VariableNode("text", "string", "\"PAGE\"", false),
                    VariableNode("parent", "SequenceNode", "", true, children = null, circular = true),
                )),
                path = "\$node",
                expected = "\$node = {LiteralNode}\n" +
                        "  text = {string} \"PAGE\"\n" +
                        "  parent = {SequenceNode} (circular reference)"
            ),
        )

        @JvmStatic
        fun formatVarCases() = listOf(
            FormatVarCase(
                name = "scalar int",
                variable = VariableInfo("count", "int", "42", false),
                expected = "\$count = {int} 42"
            ),
            FormatVarCase(
                name = "string value",
                variable = VariableInfo("name", "string", "\"hello\"", false),
                expected = "\$name = {string} \"hello\""
            ),
            FormatVarCase(
                name = "array",
                variable = VariableInfo("items", "array", "array(3)", true),
                expected = "\$items = array(3)"
            ),
            FormatVarCase(
                name = "object",
                variable = VariableInfo("request", "ServerRequest", "{ServerRequest}", true),
                expected = "\$request = {ServerRequest}"
            ),
            FormatVarCase(
                name = "null",
                variable = VariableInfo("foo", "null", "null", false),
                expected = "\$foo = {null} null"
            ),
            FormatVarCase(
                name = "name already has dollar sign",
                variable = VariableInfo("\$bar", "int", "99", false),
                expected = "\$bar = {int} 99"
            ),
            FormatVarCase(
                name = "object — empty value, type as fallback",
                variable = VariableInfo("this", "Brannow\\Sandbox\\WorldClass", "", true),
                expected = "\$this = {Brannow\\Sandbox\\WorldClass}"
            ),
            FormatVarCase(
                name = "empty value, no type",
                variable = VariableInfo("x", null, "", false),
                expected = "\$x = (unknown)"
            ),
        )
    }
}
