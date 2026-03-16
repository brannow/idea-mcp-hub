package com.github.brannow.phpstormmcp.tools

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import java.util.concurrent.CompletableFuture

data class SessionInfo(
    val id: String,
    val name: String,
    val status: String,
    val currentFile: String? = null,
    val currentLine: Int? = null,
    val active: Boolean = false
)

@Service(Service.Level.PROJECT)
open class SessionService(private val project: Project) {

    internal open fun getDebuggerManager(): XDebuggerManager = XDebuggerManager.getInstance(project)

    internal open fun sessionId(session: XDebugSession): String =
        System.identityHashCode(session).toString()

    internal open fun runOnEdt(action: () -> Unit) {
        val future = CompletableFuture<Unit>()
        ApplicationManager.getApplication().invokeLater {
            action()
            future.complete(Unit)
        }
        future.get()
    }

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
            currentLine = position?.line?.let { it + 1 },
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
        val cleanId = sessionId.trimStart('#').trim()
        val manager = getDebuggerManager()
        val session = findSessionById(cleanId)
            ?: throw SessionNotFoundException(cleanId, listSessions())

        val info = toSessionInfo(session, manager.currentSession)
        runOnEdt { session.stop() }
        return info.copy(status = "stopped")
    }

    fun stopAllSessions(): List<SessionInfo> {
        val manager = getDebuggerManager()
        val currentSession = manager.currentSession
        val sessions = manager.debugSessions.filter { !it.isStopped }
        if (sessions.isEmpty()) return emptyList()

        val infos = sessions.map { toSessionInfo(it, currentSession) }
        runOnEdt { sessions.forEach { it.stop() } }
        return infos.map { it.copy(status = "stopped") }
    }

    fun stopSmart(sessionId: String?): SessionInfo {
        if (sessionId != null) {
            return stopSession(sessionId)
        }

        val manager = getDebuggerManager()
        val sessions = manager.debugSessions.filter { !it.isStopped }
        if (sessions.isEmpty()) {
            throw IllegalStateException("No sessions in project")
        }

        // Prefer active session, fall back to first on the stack
        val target = manager.currentSession?.takeIf { !it.isStopped } ?: sessions.first()
        return stopSession(sessionId(target))
    }

    private fun findSessionById(id: String): XDebugSession? {
        val cleanId = id.trimStart('#').trim()
        return getDebuggerManager().debugSessions.firstOrNull {
            sessionId(it) == cleanId
        }
    }

    companion object {
        fun getInstance(project: Project): SessionService {
            return project.getService(SessionService::class.java)
        }
    }
}

class SessionNotFoundException(
    val requestedId: String,
    val activeSessions: List<SessionInfo>
) : IllegalArgumentException("Session not found: #$requestedId")
