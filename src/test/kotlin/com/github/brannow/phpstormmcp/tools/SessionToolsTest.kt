package com.github.brannow.phpstormmcp.tools

import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XSourcePosition
import com.intellij.openapi.vfs.VirtualFile
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class SessionToolsTest {

    // -- Test infrastructure --

    data class SessionState(
        val id: String,
        val name: String,
        val suspended: Boolean = true,
        val stopped: Boolean = false,
        val file: String? = null,
        val line: Int? = null,
        val active: Boolean = false,
    )

    data class Case(
        val name: String,
        val sessions: List<SessionState>,
        val expectedOutput: String,
        val isError: Boolean = false,
    )

    data class StopCase(
        val name: String,
        val sessions: List<SessionState>,
        val sessionId: String? = null,
        val all: Boolean = false,
        val expectedOutput: String,
        val isError: Boolean = false,
    )

    private fun buildService(sessions: List<SessionState>): SessionService {
        val mockSessions = mutableMapOf<SessionState, XDebugSession>()

        for (state in sessions) {
            val session = mockk<XDebugSession>(relaxed = true)
            every { session.sessionName } returns state.name
            every { session.isSuspended } returns state.suspended
            var stopped = state.stopped
            every { session.isStopped } answers { stopped }
            every { session.stop() } answers { stopped = true }

            if (state.file != null && state.line != null) {
                val vFile = mockk<VirtualFile>()
                every { vFile.path } returns "/project/${state.file}"
                val pos = mockk<XSourcePosition>()
                every { pos.file } returns vFile
                every { pos.line } returns (state.line - 1)
                every { session.currentPosition } returns pos
            } else {
                every { session.currentPosition } returns null
            }

            mockSessions[state] = session
        }

        val activeSession = sessions.firstOrNull { it.active }?.let { mockSessions[it] }

        val manager = mockk<XDebuggerManager>()
        every { manager.debugSessions } returns mockSessions.values.toTypedArray()
        every { manager.currentSession } returns activeSession

        val project = mockk<Project>()
        every { project.basePath } returns "/project"

        val service = SessionService(project)
        service.platform = object : SessionService.Platform {
            override fun getDebuggerManager() = manager
            override fun sessionId(session: XDebugSession) =
                mockSessions.entries.first { it.value === session }.key.id
            override fun <T> readAction(action: () -> T): T = action()
            override fun runOnEdt(action: () -> Unit) = action()
        }
        return service
    }

    private fun resultText(result: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult): String =
        (result.content.first() as TextContent).text!!

    // ========================================================================
    // session_list
    // ========================================================================

    @ParameterizedTest(name = "{0}")
    @MethodSource("sessionListCases")
    fun session_list(case: Case) {
        val service = buildService(case.sessions)
        val result = handleSessionList(service)
        assertEquals(case.expectedOutput, resultText(result))
        assertEquals(case.isError, result.isError ?: false)
    }

    // ========================================================================
    // session_stop
    // ========================================================================

    @ParameterizedTest(name = "{0}")
    @MethodSource("sessionStopCases")
    fun session_stop(case: StopCase) {
        val service = buildService(case.sessions)
        try {
            val result = handleSessionStop(service, case.sessionId, case.all)
            assertEquals(case.expectedOutput, resultText(result))
            assertEquals(case.isError, result.isError ?: false)
        } catch (e: SessionNotFoundException) {
            // Mirror the handler's catch logic
            val output = if (e.activeSessions.isEmpty()) {
                "Session '${e.requestedId}' not found, no sessions in project"
            } else {
                "Session '${e.requestedId}' not found, current sessions:\n\n${formatSessionList(e.activeSessions)}"
            }
            assertEquals(case.expectedOutput, output)
            assertEquals(true, case.isError)
        } catch (e: Exception) {
            assertEquals(case.expectedOutput, e.message ?: "Unknown error")
            assertEquals(true, case.isError)
        }
    }

    companion object {

        // -- session_list cases --

        @JvmStatic
        fun sessionListCases() = listOf(
            Case(
                name = "no sessions",
                sessions = emptyList(),
                expectedOutput = "No sessions in project",
            ),
            Case(
                name = "one paused session (active)",
                sessions = listOf(
                    SessionState("111", "index.php", suspended = true, file = "src/index.php", line = 5, active = true)
                ),
                expectedOutput = "#111 \"index.php\" at src/index.php:5 (active)",
            ),
            Case(
                name = "one running session",
                sessions = listOf(
                    SessionState("222", "worker.php", suspended = false, file = null, active = true)
                ),
                expectedOutput = "#222 \"worker.php\" [running] (active)",
            ),
            Case(
                name = "multiple sessions mixed state",
                sessions = listOf(
                    SessionState("111", "index.php", suspended = true, file = "src/index.php", line = 15, active = true),
                    SessionState("222", "test.php", suspended = false, active = false),
                ),
                expectedOutput = "#111 \"index.php\" at src/index.php:15 (active)\n#222 \"test.php\" [running]",
            ),
            Case(
                name = "stopped sessions are filtered out",
                sessions = listOf(
                    SessionState("111", "index.php", suspended = true, file = "src/index.php", line = 5, active = true),
                    SessionState("222", "old.php", stopped = true),
                ),
                expectedOutput = "#111 \"index.php\" at src/index.php:5 (active)",
            ),
        )

        // -- session_stop cases --

        @JvmStatic
        fun sessionStopCases() = listOf(
            StopCase(
                name = "no params, no sessions",
                sessions = emptyList(),
                expectedOutput = "No sessions in project",
            ),
            StopCase(
                name = "no params, one session → stops it",
                sessions = listOf(
                    SessionState("111", "index.php", suspended = true, file = "src/index.php", line = 5, active = true)
                ),
                expectedOutput = "#111 \"index.php\" [stopped] at src/index.php:5",
            ),
            StopCase(
                name = "no params, multiple sessions → stops active",
                sessions = listOf(
                    SessionState("111", "index.php", suspended = true, file = "src/index.php", line = 5, active = true),
                    SessionState("222", "test.php", suspended = true, file = "src/test.php", line = 10, active = false),
                ),
                expectedOutput = "#111 \"index.php\" [stopped] at src/index.php:5\n\n1 session(s) remaining:\n#222 \"test.php\" at src/test.php:10",
            ),
            StopCase(
                name = "by ID",
                sessions = listOf(
                    SessionState("111", "index.php", suspended = true, file = "src/index.php", line = 5, active = true)
                ),
                sessionId = "111",
                expectedOutput = "#111 \"index.php\" [stopped] at src/index.php:5",
            ),
            StopCase(
                name = "by ID with hash prefix",
                sessions = listOf(
                    SessionState("111", "index.php", suspended = true, file = "src/index.php", line = 5, active = true)
                ),
                sessionId = "#111",
                expectedOutput = "#111 \"index.php\" [stopped] at src/index.php:5",
            ),
            StopCase(
                name = "not found ID, has active sessions",
                sessions = listOf(
                    SessionState("111", "index.php", suspended = true, file = "src/index.php", line = 5, active = true)
                ),
                sessionId = "999",
                expectedOutput = "Session '999' not found, current sessions:\n\n#111 \"index.php\" at src/index.php:5 (active)",
                isError = true,
            ),
            StopCase(
                name = "not found ID, no sessions",
                sessions = emptyList(),
                sessionId = "999",
                expectedOutput = "Session '999' not found, no sessions in project",
                isError = true,
            ),
            StopCase(
                name = "all=true, no sessions",
                sessions = emptyList(),
                all = true,
                expectedOutput = "No sessions in project",
            ),
            StopCase(
                name = "all=true, multiple sessions",
                sessions = listOf(
                    SessionState("111", "index.php", suspended = true, file = "src/index.php", line = 5, active = true),
                    SessionState("222", "test.php", suspended = true, file = "src/test.php", line = 10, active = false),
                ),
                all = true,
                expectedOutput = "#111 \"index.php\" [stopped] at src/index.php:5\n#222 \"test.php\" [stopped] at src/test.php:10",
            ),
        )
    }
}
