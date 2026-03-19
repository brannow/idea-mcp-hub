package com.github.brannow.phpstormmcp.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XBreakpointManager
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class BreakpointToolsTest {

    // -- State description --

    data class BpState(
        val id: Long,
        val file: String,          // relative: "src/index.php"
        val line: Int,             // 1-based
        val enabled: Boolean = true,
        val condition: String? = null,
        val logExpression: String? = null,
        val suspend: Boolean = true,
        val method: Boolean = false,
    )

    // -- Mock infrastructure --

    @Suppress("UNCHECKED_CAST")
    private fun buildService(
        breakpoints: List<BpState>,
        knownFiles: Set<String> = breakpoints.map { it.file }.toSet()
    ): BreakpointService {
        val mockBreakpoints = mutableListOf<XLineBreakpoint<*>>()
        val virtualFiles = mutableMapOf<String, VirtualFile>()

        // Create VirtualFile mocks for known files
        for (file in knownFiles) {
            val vf = mockk<VirtualFile>()
            every { vf.path } returns "/project/$file"
            every { vf.url } returns "file:///project/$file"
            virtualFiles[file] = vf
        }

        // Create XLineBreakpoint mocks
        for (state in breakpoints) {
            val bp = createMockBreakpoint(state, virtualFiles[state.file]!!)
            mockBreakpoints.add(bp)
        }

        val manager = mockk<XBreakpointManager>()
        every { manager.allBreakpoints } answers { mockBreakpoints.toTypedArray() as Array<XBreakpoint<*>> }
        every { manager.isDefaultBreakpoint(any()) } returns false
        every { manager.removeBreakpoint(any()) } answers {
            mockBreakpoints.remove(firstArg())
        }

        val mockType = mockk<XLineBreakpointType<*>>(relaxed = true)
        every { mockType.id } returns "php-line"

        // addLineBreakpoint: use relaxed mock, then set answer via slot
        @Suppress("UNCHECKED_CAST")
        val typedManager = manager as XBreakpointManager
        @Suppress("UNCHECKED_CAST")
        every {
            typedManager.addLineBreakpoint(
                any<XLineBreakpointType<XBreakpointProperties<*>>>(),
                any<String>(),
                any<Int>(),
                any()
            )
        } answers {
            val fileUrl = arg<String>(1)
            val zeroLine = arg<Int>(2)
            val vf = virtualFiles.values.first { it.url == fileUrl }
            val relativePath = vf.path.removePrefix("/project/")
            val newId = System.nanoTime()
            val newState = BpState(newId, relativePath, zeroLine + 1)
            val newBp = createMockBreakpoint(newState, vf)
            mockBreakpoints.add(newBp)
            newBp as XLineBreakpoint<XBreakpointProperties<*>>
        }

        val project = mockk<Project>()
        every { project.basePath } returns "/project"

        val service = BreakpointService(project)
        service.platform = object : BreakpointService.Platform {
            override fun getBreakpointManager() = manager
            override fun findLineBreakpointType() = mockType
            override fun resolveFile(path: String): VirtualFile? {
                // Try exact relative match
                virtualFiles[path]?.let { return it }
                // Try stripping project prefix
                val rel = path.removePrefix("/project/")
                return virtualFiles[rel]
            }
            override fun getLineCount(file: VirtualFile): Int = FILE_LINE_COUNT
            override fun isLibrary(file: VirtualFile): Boolean = file.path.contains("/vendor/")
            override fun <T> readAction(action: () -> T): T = action()
            override fun <T> runOnEdt(action: () -> T): T = action()

            // Exception breakpoint stubs — no exception breakpoints in existing tests
            override fun findExceptionBreakpointType(): XBreakpointType<*, *>? = null
            override fun searchPhpClasses(input: String): List<PhpClassInfo> = emptyList()
            override fun isExceptionClass(fqcn: String): Boolean = false
            override fun getExceptionClassName(breakpoint: XBreakpoint<*>): String? = null
            override fun addExceptionBreakpoint(fqcn: String): XBreakpoint<*> =
                throw IllegalStateException("Not available in test")
        }
        return service
    }

    private fun mockExpression(value: String): XExpression {
        val expr = mockk<XExpression>()
        every { expr.expression } returns value
        return expr
    }

    @Suppress("UNCHECKED_CAST")
    private fun createMockBreakpoint(state: BpState, vFile: VirtualFile): XLineBreakpoint<*> {
        val bp = mockk<XLineBreakpoint<*>>(relaxed = true)

        every { bp.timeStamp } returns state.id
        every { bp.line } returns (state.line - 1)

        // Mutable state for properties that can be changed via update
        var enabled = state.enabled
        var condition: String? = state.condition
        var logExpr: String? = state.logExpression
        var policy = if (state.suspend) SuspendPolicy.ALL else SuspendPolicy.NONE

        every { bp.isEnabled } answers { enabled }
        every { bp.isEnabled = any() } answers { enabled = firstArg() }

        every { bp.setCondition(any()) } answers { condition = firstArg() }
        every { bp.conditionExpression } answers { condition?.let { mockExpression(it) } }

        every { bp.setLogExpression(any()) } answers { logExpr = firstArg() }
        every { bp.logExpressionObject } answers { logExpr?.let { mockExpression(it) } }

        every { bp.suspendPolicy } answers { policy }
        every { bp.suspendPolicy = any() } answers { policy = firstArg() }

        val pos = mockk<XSourcePosition>()
        every { pos.file } returns vFile
        every { pos.line } returns (state.line - 1)
        every { bp.sourcePosition } returns pos

        val typeId = if (state.method) "php-method" else "php-line"
        val bpType = mockk<XLineBreakpointType<*>>()
        every { bpType.id } returns typeId
        every { bp.type } returns bpType as XLineBreakpointType<Nothing>

        return bp
    }

    private fun resultText(result: CallToolResult): String =
        (result.content.first() as TextContent).text!!

    private fun isError(result: CallToolResult): Boolean =
        result.isError ?: false

    // ========================================================================
    // breakpoint_list
    // ========================================================================

    data class ListCase(
        val name: String,
        val breakpoints: List<BpState>,
        val knownFiles: Set<String>? = null,
        val fileFilter: String? = null,
        val activeLocation: Pair<String, Int>? = null,
        val expectedOutput: String,
        val isError: Boolean = false,
    ) {
        override fun toString() = name
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("listCases")
    fun breakpoint_list(case: ListCase) {
        val files = case.knownFiles ?: case.breakpoints.map { it.file }.toSet()
        val service = buildService(case.breakpoints, files)
        val result = handleBreakpointList(service, case.fileFilter, case.activeLocation)
        assertEquals(case.expectedOutput, resultText(result))
        assertEquals(case.isError, isError(result))
    }

    // ========================================================================
    // breakpoint_add
    // ========================================================================

    data class AddCase(
        val name: String,
        val breakpoints: List<BpState>,
        val knownFiles: Set<String>? = null,
        val location: String?,
        val condition: String? = null,
        val logExpression: String? = null,
        val suspend: Boolean = true,
        val expectedPattern: String,   // use {ID} as placeholder for generated ID
        val isError: Boolean = false,
    ) {
        override fun toString() = name
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("addCases")
    fun breakpoint_add(case: AddCase) {
        val files = case.knownFiles ?: case.breakpoints.map { it.file }.toSet()
        val service = buildService(case.breakpoints, files)
        try {
            val result = handleBreakpointAdd(service, case.location, case.condition, case.logExpression, case.suspend)
            val text = resultText(result)
            if (case.expectedPattern.contains("{ID}")) {
                // Extract the generated ID from output and substitute
                val idMatch = Regex("^#(\\d+)").find(text)
                val actual = if (idMatch != null) {
                    text.replace(idMatch.groupValues[1], "{ID}")
                } else text
                assertEquals(case.expectedPattern, actual)
            } else {
                assertEquals(case.expectedPattern, text)
            }
            assertEquals(case.isError, isError(result))
        } catch (e: Exception) {
            assertEquals(case.expectedPattern, e.message)
            assertEquals(true, case.isError)
        }
    }

    // ========================================================================
    // breakpoint_update
    // ========================================================================

    data class UpdateCase(
        val name: String,
        val breakpoints: List<BpState>,
        val id: String?,
        val enabled: Boolean? = null,
        val condition: String? = null,
        val logExpression: String? = null,
        val suspend: Boolean? = null,
        val expectedOutput: String,
        val isError: Boolean = false,
    ) {
        override fun toString() = name
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("updateCases")
    fun breakpoint_update(case: UpdateCase) {
        val service = buildService(case.breakpoints)
        val result = handleBreakpointUpdate(service, case.id, case.enabled, case.condition, case.logExpression, case.suspend)
        assertEquals(case.expectedOutput, resultText(result))
        assertEquals(case.isError, isError(result))
    }

    // ========================================================================
    // breakpoint_remove
    // ========================================================================

    data class RemoveCase(
        val name: String,
        val breakpoints: List<BpState>,
        val knownFiles: Set<String>? = null,
        val ids: List<String>? = null,
        val all: Boolean = false,
        val expectedOutput: String,
        val isError: Boolean = false,
    ) {
        override fun toString() = name
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("removeCases")
    fun breakpoint_remove(case: RemoveCase) {
        val files = case.knownFiles ?: case.breakpoints.map { it.file }.toSet()
        val service = buildService(case.breakpoints, files)
        try {
            val result = handleBreakpointRemove(service, case.ids, case.all)
            assertEquals(case.expectedOutput, resultText(result))
            assertEquals(case.isError, isError(result))
        } catch (e: AmbiguousBreakpointException) {
            val output = ambiguousResponse("", e.breakpoints)
            assertEquals(case.expectedOutput, resultText(output))
            assertEquals(true, case.isError)
        }
    }

    companion object {
        private const val FILE_LINE_COUNT = 30


        // Common breakpoint states for reuse
        private val BP_INDEX_5 = BpState(100, "src/index.php", 5)
        private val BP_INDEX_10 = BpState(101, "src/index.php", 10)
        private val BP_WORLD_13 = BpState(102, "src/WorldClass.php", 13)
        private val BP_WORLD_13_COND = BpState(103, "src/WorldClass.php", 13, condition = "\$foo === ''")
        private val BP_WORLD_13_DISABLED = BpState(104, "src/WorldClass.php", 13, enabled = false)
        private val BP_VENDOR = BpState(105, "vendor/lib/Helper.php", 8)
        private val BP_METHOD = BpState(106, "src/Service.php", 20, method = true)
        private val BP_LOG = BpState(107, "src/index.php", 15, logExpression = "\$request->getUri()")
        private val BP_NO_SUSPEND = BpState(108, "src/index.php", 20, suspend = false)
        private val BP_ALL_ANNOTATIONS = BpState(109, "vendor/lib/Debug.php", 5, enabled = false, condition = "\$x > 0", logExpression = "\$x", suspend = false)

        // ================================================================
        // breakpoint_list cases
        // ================================================================

        @JvmStatic
        fun listCases() = listOf(
            // --- No breakpoints ---
            ListCase(
                name = "no breakpoints, no filter",
                breakpoints = emptyList(),
                expectedOutput = "No breakpoints in project",
            ),
            ListCase(
                name = "no breakpoints, with filter, file exists",
                breakpoints = emptyList(),
                knownFiles = setOf("src/index.php"),
                fileFilter = "src/index.php",
                expectedOutput = "No breakpoints in src/index.php",
            ),
            ListCase(
                name = "no breakpoints, with filter, file does not exist",
                breakpoints = emptyList(),
                fileFilter = "src/nonExistent.php",
                expectedOutput = "File 'src/nonExistent.php' not found",
            ),

            // --- Single breakpoint ---
            ListCase(
                name = "one breakpoint, no filter",
                breakpoints = listOf(BP_INDEX_5),
                expectedOutput = "#100 src/index.php:5",
            ),
            ListCase(
                name = "one breakpoint, matching filter",
                breakpoints = listOf(BP_INDEX_5),
                fileFilter = "index",
                expectedOutput = "#100 src/index.php:5",
            ),
            ListCase(
                name = "one breakpoint, non-matching filter, file exists",
                breakpoints = listOf(BP_INDEX_5),
                knownFiles = setOf("src/index.php", "src/other.php"),
                fileFilter = "src/other.php",
                expectedOutput = "No breakpoints in src/other.php",
            ),

            // --- Multiple breakpoints, different lines ---
            ListCase(
                name = "multiple breakpoints, different files",
                breakpoints = listOf(BP_INDEX_5, BP_WORLD_13),
                expectedOutput = "#100 src/index.php:5\n#102 src/WorldClass.php:13",
            ),
            ListCase(
                name = "multiple breakpoints, same file different lines",
                breakpoints = listOf(BP_INDEX_5, BP_INDEX_10),
                expectedOutput = "#100 src/index.php:5\n#101 src/index.php:10",
            ),

            // --- Same-line grouping ---
            ListCase(
                name = "same line, multiple breakpoints → grouped",
                breakpoints = listOf(BP_WORLD_13, BP_WORLD_13_COND, BP_WORLD_13_DISABLED),
                expectedOutput = "src/WorldClass.php:13 (multi-breakpoint-line)\n" +
                    " - #102\n" +
                    " - #103 (condition: \$foo === '')\n" +
                    " - #104 (disabled)",
            ),
            ListCase(
                name = "mixed: some grouped, some flat",
                breakpoints = listOf(BP_INDEX_5, BP_WORLD_13, BP_WORLD_13_COND),
                expectedOutput = "#100 src/index.php:5\n" +
                    "src/WorldClass.php:13 (multi-breakpoint-line)\n" +
                    " - #102\n" +
                    " - #103 (condition: \$foo === '')",
            ),

            // --- Annotations ---
            ListCase(
                name = "disabled breakpoint",
                breakpoints = listOf(BP_WORLD_13_DISABLED),
                expectedOutput = "#104 src/WorldClass.php:13 (disabled)",
            ),
            ListCase(
                name = "conditional breakpoint",
                breakpoints = listOf(BP_WORLD_13_COND),
                expectedOutput = "#103 src/WorldClass.php:13 (condition: \$foo === '')",
            ),
            ListCase(
                name = "vendor breakpoint",
                breakpoints = listOf(BP_VENDOR),
                expectedOutput = "#105 vendor/lib/Helper.php:8 (vendor)",
            ),
            ListCase(
                name = "method breakpoint",
                breakpoints = listOf(BP_METHOD),
                expectedOutput = "#106 src/Service.php:20 (method)",
            ),
            ListCase(
                name = "log expression breakpoint",
                breakpoints = listOf(BP_LOG),
                expectedOutput = "#107 src/index.php:15 (log: \$request->getUri())",
            ),
            ListCase(
                name = "no-suspend breakpoint",
                breakpoints = listOf(BP_NO_SUSPEND),
                expectedOutput = "#108 src/index.php:20 (no suspend)",
            ),
            ListCase(
                name = "all annotations combined",
                breakpoints = listOf(BP_ALL_ANNOTATIONS),
                expectedOutput = "#109 vendor/lib/Debug.php:5 (vendor, disabled, condition: \$x > 0, log: \$x, no suspend)",
            ),

            // --- Filter with results ---
            ListCase(
                name = "filter matches subset",
                breakpoints = listOf(BP_INDEX_5, BP_WORLD_13, BP_VENDOR),
                fileFilter = "src/",
                expectedOutput = "#100 src/index.php:5\n#102 src/WorldClass.php:13",
            ),

            // --- Active breakpoint ---
            ListCase(
                name = "active breakpoint — single on line",
                breakpoints = listOf(BP_INDEX_5, BP_WORLD_13),
                activeLocation = "src/index.php" to 5,
                expectedOutput = "#100 src/index.php:5 (active)\n#102 src/WorldClass.php:13",
            ),
            ListCase(
                name = "active — multi-breakpoint, all suspending → active on header only",
                breakpoints = listOf(BP_INDEX_5, BP_WORLD_13, BP_WORLD_13_COND),
                activeLocation = "src/WorldClass.php" to 13,
                expectedOutput = "#100 src/index.php:5\n" +
                    "src/WorldClass.php:13 (active, multi-breakpoint-line)\n" +
                    " - #102\n" +
                    " - #103 (condition: \$foo === '')",
            ),
            ListCase(
                name = "no active session — no annotation",
                breakpoints = listOf(BP_INDEX_5),
                activeLocation = null,
                expectedOutput = "#100 src/index.php:5",
            ),
            ListCase(
                name = "active location doesn't match any breakpoint",
                breakpoints = listOf(BP_INDEX_5, BP_WORLD_13),
                activeLocation = "src/other.php" to 42,
                expectedOutput = "#100 src/index.php:5\n#102 src/WorldClass.php:13",
            ),
        )

        // ================================================================
        // breakpoint_add cases
        // ================================================================

        @JvmStatic
        fun addCases() = listOf(
            // --- Input validation ---
            AddCase(
                name = "missing location",
                breakpoints = emptyList(),
                location = null,
                expectedPattern = "'location' is required",
                isError = true,
            ),
            AddCase(
                name = "malformed location: no colon",
                breakpoints = emptyList(),
                location = "srcindexphp15",
                expectedPattern = "Invalid location format. Expected file:line, e.g. \"src/index.php:15\"",
                isError = true,
            ),
            AddCase(
                name = "malformed location: no line number",
                breakpoints = emptyList(),
                location = "src/index.php:",
                expectedPattern = "Invalid location format. Expected file:line, e.g. \"src/index.php:15\"",
                isError = true,
            ),
            AddCase(
                name = "malformed location: line is not a number",
                breakpoints = emptyList(),
                location = "src/index.php:abc",
                expectedPattern = "Invalid location format. Expected file:line, e.g. \"src/index.php:15\"",
                isError = true,
            ),
            AddCase(
                name = "file not found",
                breakpoints = emptyList(),
                location = "src/nonExistent.php:10",
                expectedPattern = "File not found: src/nonExistent.php",
                isError = true,
            ),
            AddCase(
                name = "line beyond end of file",
                breakpoints = emptyList(),
                knownFiles = setOf("src/index.php"),
                location = "src/index.php:99",
                expectedPattern = "Line 99 is beyond end of file (src/index.php has $FILE_LINE_COUNT lines)",
                isError = true,
            ),
            AddCase(
                name = "negative line number",
                breakpoints = emptyList(),
                knownFiles = setOf("src/index.php"),
                location = "src/index.php:-1",
                expectedPattern = "Invalid location format. Expected file:line, e.g. \"src/index.php:15\"",
                isError = true,
            ),
            AddCase(
                name = "line zero",
                breakpoints = emptyList(),
                knownFiles = setOf("src/index.php"),
                location = "src/index.php:0",
                expectedPattern = "Invalid location format. Expected file:line, e.g. \"src/index.php:15\"",
                isError = true,
            ),

            // --- Successful add ---
            AddCase(
                name = "add to empty project",
                breakpoints = emptyList(),
                knownFiles = setOf("src/index.php"),
                location = "src/index.php:15",
                expectedPattern = "#{ID} src/index.php:15",
            ),
            AddCase(
                name = "add to line with no existing breakpoints",
                breakpoints = listOf(BP_INDEX_5),
                location = "src/index.php:15",
                expectedPattern = "#{ID} src/index.php:15",
            ),
            AddCase(
                name = "add to line with existing breakpoints → context",
                breakpoints = listOf(BP_WORLD_13, BP_WORLD_13_COND),
                location = "src/WorldClass.php:13",
                expectedPattern = "#{ID} src/WorldClass.php:13\n\n" +
                    "src/WorldClass.php:13 (multi-breakpoint-line)\n" +
                    " - #102\n" +
                    " - #103 (condition: \$foo === '')\n" +
                    " - #{ID} (new)",
            ),
            AddCase(
                name = "add with condition",
                breakpoints = emptyList(),
                knownFiles = setOf("src/index.php"),
                location = "src/index.php:15",
                condition = "\$count > 10",
                expectedPattern = "#{ID} src/index.php:15 (condition: \$count > 10)",
            ),
            AddCase(
                name = "add with log expression",
                breakpoints = emptyList(),
                knownFiles = setOf("src/index.php"),
                location = "src/index.php:15",
                logExpression = "\$request->getUri()",
                expectedPattern = "#{ID} src/index.php:15 (log: \$request->getUri())",
            ),
            AddCase(
                name = "add with no suspend",
                breakpoints = emptyList(),
                knownFiles = setOf("src/index.php"),
                location = "src/index.php:15",
                suspend = false,
                expectedPattern = "#{ID} src/index.php:15 (no suspend)",
            ),
            AddCase(
                name = "add with all options",
                breakpoints = emptyList(),
                knownFiles = setOf("src/index.php"),
                location = "src/index.php:15",
                condition = "\$x > 0",
                logExpression = "\$x",
                suspend = false,
                expectedPattern = "#{ID} src/index.php:15 (condition: \$x > 0, log: \$x, no suspend)",
            ),
        )

        // ================================================================
        // breakpoint_update cases
        // ================================================================

        @JvmStatic
        fun updateCases() = listOf(
            // --- Input validation ---
            UpdateCase(
                name = "missing id",
                breakpoints = emptyList(),
                id = null,
                expectedOutput = "'id' is required",
                isError = true,
            ),

            // --- Not found ---
            UpdateCase(
                name = "wrong ID and no changes → reports ID problem",
                breakpoints = listOf(BP_INDEX_5),
                id = "#999",
                expectedOutput = "Breakpoint '#999' not found, current breakpoints:\n\n" +
                    "#100 src/index.php:5",
                isError = true,
            ),
            UpdateCase(
                name = "not found by #ID, empty project",
                breakpoints = emptyList(),
                id = "#999",
                enabled = true,
                expectedOutput = "Breakpoint '#999' not found, no breakpoints in project",
                isError = true,
            ),
            UpdateCase(
                name = "not found by #ID, has breakpoints → shows current",
                breakpoints = listOf(BP_INDEX_5, BP_WORLD_13),
                id = "#999",
                enabled = true,
                expectedOutput = "Breakpoint '#999' not found, current breakpoints:\n\n" +
                    "#100 src/index.php:5\n#102 src/WorldClass.php:13",
                isError = true,
            ),
            UpdateCase(
                name = "not found by #ID, has matching breakpoints → shows matching",
                breakpoints = listOf(BP_INDEX_5, BP_WORLD_13),
                id = "#999",
                enabled = true,
                expectedOutput = "Breakpoint '#999' not found, current breakpoints:\n\n" +
                    "#100 src/index.php:5\n#102 src/WorldClass.php:13",
                isError = true,
            ),
            UpdateCase(
                name = "not found by file:line",
                breakpoints = listOf(BP_INDEX_5),
                id = "src/index.php:99",
                enabled = true,
                expectedOutput = "Breakpoint 'src/index.php:99' not found, current breakpoints:\n\n" +
                    "#100 src/index.php:5",
                isError = true,
            ),

            // --- Found by #ID ---
            UpdateCase(
                name = "found by #ID, no changes → error",
                breakpoints = listOf(BP_INDEX_5),
                id = "#100",
                expectedOutput = "No changes specified. Use enabled, condition, log_expression, or suspend to update.",
                isError = true,
            ),
            UpdateCase(
                name = "found by ID without hash",
                breakpoints = listOf(BP_INDEX_5),
                id = "100",
                enabled = true,
                expectedOutput = "#100 src/index.php:5",
            ),
            UpdateCase(
                name = "disable breakpoint",
                breakpoints = listOf(BP_INDEX_5),
                id = "#100",
                enabled = false,
                expectedOutput = "#100 src/index.php:5 (disabled)",
            ),
            UpdateCase(
                name = "enable disabled breakpoint",
                breakpoints = listOf(BP_WORLD_13_DISABLED),
                id = "#104",
                enabled = true,
                expectedOutput = "#104 src/WorldClass.php:13",
            ),
            UpdateCase(
                name = "set condition",
                breakpoints = listOf(BP_INDEX_5),
                id = "#100",
                condition = "\$count > 10",
                expectedOutput = "#100 src/index.php:5 (condition: \$count > 10)",
            ),
            UpdateCase(
                name = "clear condition (empty string)",
                breakpoints = listOf(BP_WORLD_13_COND),
                id = "#103",
                condition = "",
                expectedOutput = "#103 src/WorldClass.php:13",
            ),
            UpdateCase(
                name = "set log expression",
                breakpoints = listOf(BP_INDEX_5),
                id = "#100",
                logExpression = "\$request",
                expectedOutput = "#100 src/index.php:5 (log: \$request)",
            ),
            UpdateCase(
                name = "clear log expression",
                breakpoints = listOf(BP_LOG),
                id = "#107",
                logExpression = "",
                expectedOutput = "#107 src/index.php:15",
            ),
            UpdateCase(
                name = "set no-suspend",
                breakpoints = listOf(BP_INDEX_5),
                id = "#100",
                suspend = false,
                expectedOutput = "#100 src/index.php:5 (no suspend)",
            ),
            UpdateCase(
                name = "restore suspend",
                breakpoints = listOf(BP_NO_SUSPEND),
                id = "#108",
                suspend = true,
                expectedOutput = "#108 src/index.php:20",
            ),

            // --- Found by file:line ---
            UpdateCase(
                name = "found by file:line (unique)",
                breakpoints = listOf(BP_INDEX_5),
                id = "src/index.php:5",
                enabled = false,
                expectedOutput = "#100 src/index.php:5 (disabled)",
            ),

            // --- Ambiguous file:line ---
            UpdateCase(
                name = "ambiguous file:line → guidance",
                breakpoints = listOf(BP_WORLD_13, BP_WORLD_13_COND, BP_WORLD_13_DISABLED),
                id = "src/WorldClass.php:13",
                enabled = false,
                expectedOutput = "src/WorldClass.php:13 (multi-breakpoint-line)\n" +
                    " - #102\n" +
                    " - #103 (condition: \$foo === '')\n" +
                    " - #104 (disabled)\n\n" +
                    "Choose a breakpoint via #ID or remove other breakpoints first.",
                isError = true,
            ),
        )

        // ================================================================
        // breakpoint_remove cases
        // ================================================================

        @JvmStatic
        fun removeCases() = listOf(
            // --- Input validation ---
            RemoveCase(
                name = "no params → error",
                breakpoints = emptyList(),
                expectedOutput = "Specify breakpoint(s) to remove or use all=true to remove all breakpoints",
                isError = true,
            ),

            // --- Remove all ---
            RemoveCase(
                name = "all=true, empty project",
                breakpoints = emptyList(),
                all = true,
                expectedOutput = "No breakpoints in project",
            ),
            RemoveCase(
                name = "all=true, has breakpoints",
                breakpoints = listOf(BP_INDEX_5, BP_WORLD_13),
                all = true,
                expectedOutput = "#100 src/index.php:5\n#102 src/WorldClass.php:13",
            ),

            // --- Remove by #ID ---
            RemoveCase(
                name = "by #ID, only breakpoint",
                breakpoints = listOf(BP_INDEX_5),
                ids = listOf("#100"),
                expectedOutput = "#100 src/index.php:5",
            ),
            RemoveCase(
                name = "by ID without hash",
                breakpoints = listOf(BP_INDEX_5),
                ids = listOf("100"),
                expectedOutput = "#100 src/index.php:5",
            ),
            RemoveCase(
                name = "by #ID, others remain",
                breakpoints = listOf(BP_INDEX_5, BP_WORLD_13),
                ids = listOf("#100"),
                expectedOutput = "#100 src/index.php:5\n\n1 breakpoint(s) remaining:\n#102 src/WorldClass.php:13",
            ),

            // --- Remove by file:line ---
            RemoveCase(
                name = "by file:line, unique",
                breakpoints = listOf(BP_INDEX_5, BP_WORLD_13),
                ids = listOf("src/index.php:5"),
                expectedOutput = "#100 src/index.php:5\n\n1 breakpoint(s) remaining:\n#102 src/WorldClass.php:13",
            ),

            // --- Remove by file path (purge) ---
            RemoveCase(
                name = "by file path, purges all in file",
                breakpoints = listOf(BP_INDEX_5, BP_INDEX_10, BP_WORLD_13),
                ids = listOf("src/index.php"),
                expectedOutput = "#100 src/index.php:5\n#101 src/index.php:10\n\n1 breakpoint(s) remaining:\n#102 src/WorldClass.php:13",
            ),

            // --- Not found ---
            RemoveCase(
                name = "not found #ID, empty project",
                breakpoints = emptyList(),
                ids = listOf("#999"),
                expectedOutput = "Breakpoint '#999' not found, no breakpoints in project",
                isError = true,
            ),
            RemoveCase(
                name = "not found #ID, has breakpoints → shows current",
                breakpoints = listOf(BP_INDEX_5),
                ids = listOf("#999"),
                expectedOutput = "Breakpoint '#999' not found, current breakpoints:\n\n#100 src/index.php:5",
                isError = true,
            ),
            RemoveCase(
                name = "not found by substring, has matching breakpoints → shows matching",
                breakpoints = listOf(BP_INDEX_5, BP_WORLD_13),
                ids = listOf("#999"),
                expectedOutput = "Breakpoint '#999' not found, current breakpoints:\n\n" +
                    "#100 src/index.php:5\n#102 src/WorldClass.php:13",
                isError = true,
            ),

            // --- Partial success ---
            RemoveCase(
                name = "partial: one found, one not found",
                breakpoints = listOf(BP_INDEX_5, BP_WORLD_13),
                ids = listOf("#100", "#999"),
                expectedOutput = "#100 src/index.php:5\n\n1 breakpoint(s) remaining:\n#102 src/WorldClass.php:13\n\n" +
                    "Breakpoint '#999' not found, current breakpoints:\n\n#102 src/WorldClass.php:13",
            ),

            // --- Comma-separated multiple ---
            RemoveCase(
                name = "multiple IDs comma-separated",
                breakpoints = listOf(BP_INDEX_5, BP_INDEX_10, BP_WORLD_13),
                ids = listOf("#100", "#101"),
                expectedOutput = "#100 src/index.php:5\n#101 src/index.php:10\n\n1 breakpoint(s) remaining:\n#102 src/WorldClass.php:13",
            ),

            // --- Ambiguous file:line ---
            RemoveCase(
                name = "ambiguous file:line → guidance",
                breakpoints = listOf(BP_WORLD_13, BP_WORLD_13_COND),
                ids = listOf("src/WorldClass.php:13"),
                expectedOutput = "src/WorldClass.php:13 (multi-breakpoint-line)\n" +
                    " - #102\n" +
                    " - #103 (condition: \$foo === '')\n\n" +
                    "Choose a breakpoint via #ID or remove other breakpoints first.",
                isError = true,
            ),

            // --- Vendor breakpoint ---
            RemoveCase(
                name = "remove vendor breakpoint by ID",
                breakpoints = listOf(BP_VENDOR, BP_INDEX_5),
                ids = listOf("#105"),
                expectedOutput = "#105 vendor/lib/Helper.php:8 (vendor)\n\n1 breakpoint(s) remaining:\n#100 src/index.php:5",
            ),

            // --- Method breakpoint ---
            RemoveCase(
                name = "remove method breakpoint by ID",
                breakpoints = listOf(BP_METHOD, BP_INDEX_5),
                ids = listOf("#106"),
                expectedOutput = "#106 src/Service.php:20 (method)\n\n1 breakpoint(s) remaining:\n#100 src/index.php:5",
            ),
        )
    }
}
