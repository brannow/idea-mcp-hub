package com.github.brannow.phpstormmcp.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XSuspendContext
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class StackFrameServiceTest {

    data class FrameState(
        val file: String?,
        val line: Int?,        // 1-based
        val name: String?,     // frame name (from presentation)
        val isLibrary: Boolean = false,
    )

    data class Case(
        val name: String,
        val frames: List<FrameState>,
        val hasActiveStack: Boolean = true,
        val expectedFrames: List<FrameInfo>,
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `getStackFrames`(case: Case) {
        val service = buildService(case)
        val suspendContext = buildSuspendContext(case)
        val result = service.getStackFrames(suspendContext)
        assertEquals(case.expectedFrames, result)
    }

    // --- formatStackTrace tests ---

    data class FormatCase(
        val name: String,
        val frames: List<FrameInfo>,
        val activeDepth: Int = 0,
        val collapseLibrary: Boolean = true,
        val expected: String,
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("formatCases")
    fun `formatStackTrace`(case: FormatCase) {
        assertEquals(case.expected, formatStackTrace(case.frames, case.activeDepth, case.collapseLibrary))
    }

    // -- Infrastructure --

    // Track file→isLibrary mapping for the platform mock
    private val libraryFiles = mutableMapOf<String, Boolean>()

    private fun buildService(case: Case): StackFrameService {
        libraryFiles.clear()
        for (f in case.frames) {
            if (f.file != null) libraryFiles["/project/${f.file}"] = f.isLibrary
        }

        val project = mockk<Project>()
        every { project.basePath } returns "/project"

        val service = StackFrameService(project)
        service.platform = object : StackFrameService.Platform {
            override fun <T> readAction(action: () -> T): T = action()

            override fun toProjectRelativePath(absolutePath: String): String {
                return if (absolutePath.startsWith("/project/")) {
                    absolutePath.removePrefix("/project/")
                } else {
                    absolutePath
                }
            }

            override fun computeFrames(stack: XExecutionStack): List<XStackFrame> {
                // Return all frames except the first (top frame is separate)
                if (case.frames.size <= 1) return emptyList()
                return case.frames.drop(1).map { createMockFrame(it) }
            }

            override fun getFramePresentation(frame: XStackFrame): String? = null

            override fun isLibrary(file: VirtualFile): Boolean =
                libraryFiles[file.path] ?: false
        }
        return service
    }

    private fun buildSuspendContext(case: Case): XSuspendContext {
        val ctx = mockk<XSuspendContext>()

        if (!case.hasActiveStack) {
            every { ctx.activeExecutionStack } returns null
            return ctx
        }

        val stack = mockk<XExecutionStack>()
        every { ctx.activeExecutionStack } returns stack

        if (case.frames.isEmpty()) {
            every { stack.topFrame } returns null
        } else {
            every { stack.topFrame } returns createMockFrame(case.frames.first())
        }

        return ctx
    }

    private fun createMockFrame(state: FrameState): XStackFrame {
        val frame = mockk<XStackFrame>()

        if (state.file != null && state.line != null) {
            val vFile = mockk<VirtualFile>()
            every { vFile.path } returns "/project/${state.file}"
            val pos = mockk<XSourcePosition>()
            every { pos.file } returns vFile
            every { pos.line } returns (state.line - 1)
            every { frame.sourcePosition } returns pos
        } else {
            every { frame.sourcePosition } returns null
        }

        every { frame.equalityObject } returns state.name

        return frame
    }

    companion object {
        @JvmStatic
        fun cases() = listOf(
            Case(
                name = "single frame",
                frames = listOf(
                    FrameState("src/index.php", 5, "main")
                ),
                expectedFrames = listOf(
                    FrameInfo(0, "src/index.php", 5, "main")
                )
            ),
            Case(
                name = "three frames deep",
                frames = listOf(
                    FrameState("src/Service/UserService.php", 42, "authenticate"),
                    FrameState("src/Controller/LoginController.php", 15, "login"),
                    FrameState("src/index.php", 8, "{main}"),
                ),
                expectedFrames = listOf(
                    FrameInfo(0, "src/Service/UserService.php", 42, "authenticate"),
                    FrameInfo(1, "src/Controller/LoginController.php", 15, "login"),
                    FrameInfo(2, "src/index.php", 8, "{main}"),
                )
            ),
            Case(
                name = "no active stack",
                frames = emptyList(),
                hasActiveStack = false,
                expectedFrames = emptyList()
            ),
            Case(
                name = "no top frame",
                frames = emptyList(),
                expectedFrames = emptyList()
            ),
            Case(
                name = "frame without source position",
                frames = listOf(
                    FrameState(null, null, "eval"),
                ),
                expectedFrames = listOf(
                    FrameInfo(0, null, null, "eval")
                )
            ),
            Case(
                name = "frame without name",
                frames = listOf(
                    FrameState("src/index.php", 10, null),
                ),
                expectedFrames = listOf(
                    FrameInfo(0, "src/index.php", 10, null)
                )
            ),
            Case(
                name = "mixed project and library frames",
                frames = listOf(
                    FrameState("vendor/acme/lib/src/Foo.php", 42, "Foo->bar()", isLibrary = true),
                    FrameState("vendor/acme/lib/src/Factory.php", 15, "Factory->create()", isLibrary = true),
                    FrameState("src/WorldClass.php", 20, "WorldClass->fooBar()"),
                    FrameState("src/index.php", 8, "{main}"),
                ),
                expectedFrames = listOf(
                    FrameInfo(0, "vendor/acme/lib/src/Foo.php", 42, "Foo->bar()", isLibrary = true),
                    FrameInfo(1, "vendor/acme/lib/src/Factory.php", 15, "Factory->create()", isLibrary = true),
                    FrameInfo(2, "src/WorldClass.php", 20, "WorldClass->fooBar()"),
                    FrameInfo(3, "src/index.php", 8, "{main}"),
                )
            ),
        )

        @JvmStatic
        fun formatCases() = listOf(
            FormatCase(
                name = "empty",
                frames = emptyList(),
                expected = "(no stack frames)"
            ),
            FormatCase(
                name = "single frame — active by default",
                frames = listOf(FrameInfo(0, "src/index.php", 5, "main")),
                expected = "→#0 main at src/index.php:5"
            ),
            FormatCase(
                name = "three frames — top active",
                frames = listOf(
                    FrameInfo(0, "src/Service/UserService.php", 42, "authenticate"),
                    FrameInfo(1, "src/Controller/LoginController.php", 15, "login"),
                    FrameInfo(2, "src/index.php", 8, "{main}"),
                ),
                expected = "→#0 authenticate at src/Service/UserService.php:42\n" +
                        " #1 login at src/Controller/LoginController.php:15\n" +
                        " #2 {main} at src/index.php:8"
            ),
            FormatCase(
                name = "three frames — middle active (frame navigation)",
                frames = listOf(
                    FrameInfo(0, "src/Service/UserService.php", 42, "authenticate"),
                    FrameInfo(1, "src/Controller/LoginController.php", 15, "login"),
                    FrameInfo(2, "src/index.php", 8, "{main}"),
                ),
                activeDepth = 1,
                expected = " #0 authenticate at src/Service/UserService.php:42\n" +
                        "→#1 login at src/Controller/LoginController.php:15\n" +
                        " #2 {main} at src/index.php:8"
            ),
            FormatCase(
                name = "frame without name",
                frames = listOf(FrameInfo(0, "src/index.php", 10, null)),
                expected = "→#0 (unknown) at src/index.php:10"
            ),
            FormatCase(
                name = "frame without position",
                frames = listOf(FrameInfo(0, null, null, "eval")),
                expected = "→#0 eval at (unknown)"
            ),
            FormatCase(
                name = "2 library frames — not collapsed (below threshold)",
                frames = listOf(
                    FrameInfo(0, "vendor/acme/lib/src/Foo.php", 42, "Foo->bar()", isLibrary = true),
                    FrameInfo(1, "vendor/acme/lib/src/Factory.php", 15, "Factory->create()", isLibrary = true),
                    FrameInfo(2, "src/WorldClass.php", 20, "WorldClass->fooBar()"),
                    FrameInfo(3, "src/index.php", 8, "{main}"),
                ),
                expected = "→#0 Foo->bar() at vendor/acme/lib/src/Foo.php:42 (library)\n" +
                        " #1 Factory->create() at vendor/acme/lib/src/Factory.php:15 (library)\n" +
                        " #2 WorldClass->fooBar() at src/WorldClass.php:20\n" +
                        " #3 {main} at src/index.php:8"
            ),
            FormatCase(
                name = "3+ consecutive library frames — collapsed",
                frames = listOf(
                    FrameInfo(0, "src/Controller.php", 10, "Controller->handle()"),
                    FrameInfo(1, "vendor/fw/Middleware.php", 20, "Middleware->process()", isLibrary = true),
                    FrameInfo(2, "vendor/fw/Dispatcher.php", 30, "Dispatcher->handle()", isLibrary = true),
                    FrameInfo(3, "vendor/fw/Router.php", 40, "Router->dispatch()", isLibrary = true),
                    FrameInfo(4, "vendor/fw/Kernel.php", 50, "Kernel->run()", isLibrary = true),
                    FrameInfo(5, "src/index.php", 5, "{main}"),
                ),
                expected = "→#0 Controller->handle() at src/Controller.php:10\n" +
                        " #1-#4 [4 library frames]\n" +
                        " #5 {main} at src/index.php:5"
            ),
            FormatCase(
                name = "library frames with active frame in middle — not collapsed",
                frames = listOf(
                    FrameInfo(0, "src/Controller.php", 10, "Controller->handle()"),
                    FrameInfo(1, "vendor/fw/Middleware.php", 20, "Middleware->process()", isLibrary = true),
                    FrameInfo(2, "vendor/fw/Dispatcher.php", 30, "Dispatcher->handle()", isLibrary = true),
                    FrameInfo(3, "vendor/fw/Router.php", 40, "Router->dispatch()", isLibrary = true),
                    FrameInfo(4, "src/index.php", 5, "{main}"),
                ),
                activeDepth = 2,
                expected = " #0 Controller->handle() at src/Controller.php:10\n" +
                        " #1 Middleware->process() at vendor/fw/Middleware.php:20 (library)\n" +
                        "→#2 Dispatcher->handle() at vendor/fw/Dispatcher.php:30 (library)\n" +
                        " #3 Router->dispatch() at vendor/fw/Router.php:40 (library)\n" +
                        " #4 {main} at src/index.php:5"
            ),
            FormatCase(
                name = "expand_stack disables collapsing",
                frames = listOf(
                    FrameInfo(0, "src/Controller.php", 10, "Controller->handle()"),
                    FrameInfo(1, "vendor/fw/Middleware.php", 20, "Middleware->process()", isLibrary = true),
                    FrameInfo(2, "vendor/fw/Dispatcher.php", 30, "Dispatcher->handle()", isLibrary = true),
                    FrameInfo(3, "vendor/fw/Router.php", 40, "Router->dispatch()", isLibrary = true),
                    FrameInfo(4, "src/index.php", 5, "{main}"),
                ),
                collapseLibrary = false,
                expected = "→#0 Controller->handle() at src/Controller.php:10\n" +
                        " #1 Middleware->process() at vendor/fw/Middleware.php:20 (library)\n" +
                        " #2 Dispatcher->handle() at vendor/fw/Dispatcher.php:30 (library)\n" +
                        " #3 Router->dispatch() at vendor/fw/Router.php:40 (library)\n" +
                        " #4 {main} at src/index.php:5"
            ),
            FormatCase(
                name = "multiple collapsed groups",
                frames = listOf(
                    FrameInfo(0, "src/Controller.php", 10, "Controller->handle()"),
                    FrameInfo(1, "vendor/fw/A.php", 20, "A->run()", isLibrary = true),
                    FrameInfo(2, "vendor/fw/B.php", 30, "B->run()", isLibrary = true),
                    FrameInfo(3, "vendor/fw/C.php", 40, "C->run()", isLibrary = true),
                    FrameInfo(4, "src/Service.php", 50, "Service->process()"),
                    FrameInfo(5, "vendor/fw/D.php", 60, "D->run()", isLibrary = true),
                    FrameInfo(6, "vendor/fw/E.php", 70, "E->run()", isLibrary = true),
                    FrameInfo(7, "vendor/fw/F.php", 80, "F->run()", isLibrary = true),
                    FrameInfo(8, "src/index.php", 5, "{main}"),
                ),
                expected = "→#0 Controller->handle() at src/Controller.php:10\n" +
                        " #1-#3 [3 library frames]\n" +
                        " #4 Service->process() at src/Service.php:50\n" +
                        " #5-#7 [3 library frames]\n" +
                        " #8 {main} at src/index.php:5"
            ),
        )
    }
}
