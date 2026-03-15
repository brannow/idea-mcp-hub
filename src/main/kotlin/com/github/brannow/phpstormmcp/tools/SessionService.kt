package com.github.brannow.phpstormmcp.tools

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.CompletableFuture

@Serializable
data class SessionInfo(
    val id: String,
    val name: String,
    val status: String,
    val currentFile: String? = null,
    val currentLine: Int? = null,
    val active: Boolean = false
)

@Service(Service.Level.PROJECT)
class SessionService(private val project: Project) {

    private fun getDebuggerManager() = XDebuggerManager.getInstance(project)

    private fun sessionId(session: XDebugSession): String =
        System.identityHashCode(session).toString()

    private fun sessionStatus(session: XDebugSession): String = when {
        session.isStopped -> "stopped"
        session.isSuspended -> "paused"
        else -> "running"
    }

    private fun toProjectRelativePath(absolutePath: String): String {
        val basePath = project.basePath ?: return absolutePath
        return if (absolutePath.startsWith(basePath)) {
            absolutePath.removePrefix(basePath).removePrefix("/")
        } else {
            absolutePath
        }
    }

    private fun toSessionInfo(session: XDebugSession, currentSession: XDebugSession?): SessionInfo {
        val position = session.currentPosition
        return SessionInfo(
            id = sessionId(session),
            name = session.sessionName,
            status = sessionStatus(session),
            currentFile = position?.file?.path?.let { toProjectRelativePath(it) },
            currentLine = position?.line?.let { it + 1 }, // 0-based → 1-based
            active = session === currentSession
        )
    }

    fun listSessions(): List<SessionInfo> {
        val manager = getDebuggerManager()
        val currentSession = manager.currentSession
        return manager.debugSessions
            .filter { !it.isStopped }
            .map { toSessionInfo(it, currentSession) }
    }

    fun stopSession(sessionId: String): SessionInfo {
        val manager = getDebuggerManager()
        val session = findSessionById(sessionId)
            ?: throw IllegalArgumentException("Session not found: $sessionId")

        val info = toSessionInfo(session, manager.currentSession)
        val future = CompletableFuture<Unit>()

        ApplicationManager.getApplication().invokeLater {
            session.stop()
            future.complete(Unit)
        }

        future.get()
        return info.copy(status = "stopped")
    }

    fun stopAllSessions(): Int {
        val sessions = getDebuggerManager().debugSessions.filter { !it.isStopped }
        if (sessions.isEmpty()) return 0

        val future = CompletableFuture<Int>()

        ApplicationManager.getApplication().invokeLater {
            var count = 0
            for (session in sessions) {
                session.stop()
                count++
            }
            future.complete(count)
        }

        return future.get()
    }

    fun stopSmart(sessionId: String?): Pair<String, Int> {
        if (sessionId != null) {
            val info = stopSession(sessionId)
            return Pair("Stopped session '${info.name}'", 1)
        }

        val sessions = getDebuggerManager().debugSessions.filter { !it.isStopped }
        return when {
            sessions.isEmpty() -> throw IllegalStateException("No active debug sessions")
            sessions.size == 1 -> {
                val info = stopSession(sessionId(sessions.first()))
                Pair("Stopped session '${info.name}'", 1)
            }
            else -> throw IllegalArgumentException(
                "Multiple active sessions (${sessions.size}). Specify session_id or use all=true. " +
                    "Sessions: ${sessions.joinToString { "'${it.sessionName}' (${sessionId(it)})" }}"
            )
        }
    }

    private fun findSessionById(id: String): XDebugSession? {
        return getDebuggerManager().debugSessions.firstOrNull {
            sessionId(it) == id
        }
    }

    companion object {
        val json = Json { prettyPrint = true }

        fun getInstance(project: Project): SessionService {
            return project.getService(SessionService::class.java)
        }
    }
}
