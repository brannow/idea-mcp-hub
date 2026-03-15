package com.github.brannow.phpstormmcp.statusbar

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.WindowManager
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class McpServerState {

    enum class Status {
        STOPPED,
        RUNNING
    }

    var status: Status = Status.STOPPED
        private set

    var transport: String = "Not configured"
        private set

    var connectedClients: Int = 0
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun start(project: Project, transport: String) {
        this.status = Status.RUNNING
        this.transport = transport
        McpActivityLog.getInstance(project).log("MCP server started ($transport)")
        notifyListeners()
        updateWidget(project)
    }

    fun stop(project: Project) {
        this.status = Status.STOPPED
        this.connectedClients = 0
        McpActivityLog.getInstance(project).log("MCP server stopped")
        notifyListeners()
        updateWidget(project)
    }

    fun clientConnected(project: Project) {
        connectedClients++
        McpActivityLog.getInstance(project).log("Client connected (total: $connectedClients)")
        notifyListeners()
        updateWidget(project)
    }

    fun clientDisconnected(project: Project) {
        connectedClients = (connectedClients - 1).coerceAtLeast(0)
        McpActivityLog.getInstance(project).log("Client disconnected (total: $connectedClients)")
        notifyListeners()
        updateWidget(project)
    }

    fun logToolCall(project: Project, toolName: String) {
        McpActivityLog.getInstance(project).log("Tool called: $toolName")
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it() }
    }

    private fun updateWidget(project: Project) {
        val statusBar: StatusBar? = WindowManager.getInstance().getStatusBar(project)
        statusBar?.updateWidget(McpStatusWidgetFactory.WIDGET_ID)
    }

    companion object {
        fun getInstance(project: Project): McpServerState {
            return project.getService(McpServerState::class.java)
        }
    }
}
