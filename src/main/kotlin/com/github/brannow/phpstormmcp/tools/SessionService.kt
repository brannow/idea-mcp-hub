package com.github.brannow.phpstormmcp.tools

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
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
class SessionService(private val project: Project) {

    internal interface Platform {
        fun getDebuggerManager(): XDebuggerManager
        fun sessionId(session: XDebugSession): String
        fun <T> readAction(action: () -> T): T
        fun runOnEdt(action: () -> Unit)
    }

    internal var platform: Platform = object : Platform {
        override fun getDebuggerManager(): XDebuggerManager = XDebuggerManager.getInstance(project)

        override fun sessionId(session: XDebugSession): String =
            System.identityHashCode(session).toString()

        override fun <T> readAction(action: () -> T): T {
            return ReadAction.compute<T, Throwable> { action() }
        }

        override fun runOnEdt(action: () -> Unit) {
            val future = CompletableFuture<Unit>()
            ApplicationManager.getApplication().invokeLater {
                action()
                future.complete(Unit)
            }
            future.get()
        }
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
            id = platform.sessionId(session),
            name = session.sessionName,
            status = sessionStatus(session),
            currentFile = position?.file?.path?.let { toProjectRelativePath(it) },
            currentLine = position?.line?.let { it + 1 },
            active = session === currentSession
        )
    }

    fun listSessions(): List<SessionInfo> = platform.readAction {
        val manager = platform.getDebuggerManager()
        val currentSession = manager.currentSession
        manager.debugSessions
            .filter { !it.isStopped }
            .map { toSessionInfo(it, currentSession) }
    }

    fun stopSession(sessionId: String): SessionInfo {
        val cleanId = sessionId.trimStart('#').trim()
        val (session, info) = platform.readAction {
            val manager = platform.getDebuggerManager()
            val session = findSessionById(cleanId)
                ?: throw SessionNotFoundException(cleanId, listSessions())
            session to toSessionInfo(session, manager.currentSession)
        }
        platform.runOnEdt { session.stop() }
        return info.copy(status = "stopped", active = false)
    }

    fun stopAllSessions(): List<SessionInfo> {
        val (sessions, infos) = platform.readAction {
            val manager = platform.getDebuggerManager()
            val currentSession = manager.currentSession
            val sessions = manager.debugSessions.filter { !it.isStopped }
            sessions to sessions.map { toSessionInfo(it, currentSession) }
        }
        if (sessions.isEmpty()) return emptyList()

        platform.runOnEdt { sessions.forEach { it.stop() } }
        return infos.map { it.copy(status = "stopped", active = false) }
    }

    fun stopSmart(sessionId: String?): SessionInfo {
        if (sessionId != null) {
            return stopSession(sessionId)
        }

        val targetId = platform.readAction {
            val manager = platform.getDebuggerManager()
            val sessions = manager.debugSessions.filter { !it.isStopped }
            if (sessions.isEmpty()) {
                throw IllegalStateException("No sessions in project")
            }

            // Prefer active session, fall back to first on the stack
            val target = manager.currentSession?.takeIf { !it.isStopped } ?: sessions.first()
            platform.sessionId(target)
        }
        return stopSession(targetId)
    }

    private fun findSessionById(id: String): XDebugSession? {
        val cleanId = id.trimStart('#').trim()
        return platform.getDebuggerManager().debugSessions.firstOrNull {
            platform.sessionId(it) == cleanId
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
