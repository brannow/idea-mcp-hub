package com.github.brannow.phpstormmcp.toolwindow

import com.github.brannow.phpstormmcp.McpServerStateListener
import com.github.brannow.phpstormmcp.server.McpServerService
import com.github.brannow.phpstormmcp.settings.McpSettings
import com.github.brannow.phpstormmcp.settings.McpSettingsConfigurable
import com.github.brannow.phpstormmcp.statusbar.McpActivityLog
import com.github.brannow.phpstormmcp.statusbar.McpIcons
import com.github.brannow.phpstormmcp.statusbar.McpServerState
import com.intellij.icons.AllIcons
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.serialization.json.*
import java.awt.BorderLayout
import java.io.File
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.ListSelectionModel

private const val TOOL_WINDOW_ID = "MCP Hub"

class McpToolWindowPanel(
    private val project: Project,
    private val toolWindow: ToolWindow,
    parentDisposable: Disposable
) : JPanel(BorderLayout()), Disposable {

    private val statusLabel = JBLabel()
    private val logListModel = DefaultListModel<String>()
    private val logList = JBList(logListModel)

    init {
        // --- Toolbar ---
        val actionGroup = DefaultActionGroup().apply {
            add(StartAction(project))
            add(StopAction(project))
            addSeparator()
            add(InstallMcpAction(project))
            addSeparator()
            add(SettingsAction(project))
            add(ClearLogAction(project))
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("McpToolWindow", actionGroup, true)
        toolbar.targetComponent = this

        // --- Status line ---
        val statusPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            add(statusLabel, BorderLayout.CENTER)
        }

        // --- Top: toolbar + status ---
        val topPanel = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(statusPanel, BorderLayout.SOUTH)
        }

        // --- Activity log ---
        logList.apply {
            font = UIUtil.getLabelFont().deriveFont(UIUtil.getFontSize(UIUtil.FontSize.SMALL))
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            emptyText.text = "No activity yet"
        }
        val scrollPane = JBScrollPane(logList).apply {
            border = JBUI.Borders.empty()
        }

        add(topPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)

        // --- Subscribe to state changes ---
        project.messageBus.connect(parentDisposable)
            .subscribe(McpServerStateListener.TOPIC, object : McpServerStateListener {
                override fun stateChanged() {
                    ApplicationManager.getApplication().invokeLater { updateState() }
                }
                override fun logUpdated() {
                    ApplicationManager.getApplication().invokeLater { updateLog() }
                }
            })

        // Initial render
        updateState()
        updateLog()
    }

    private fun pluginVersion(): String {
        return PluginManagerCore.getPlugin(
            PluginId.getId("com.github.brannow.phpstormmcp")
        )?.version ?: "unknown"
    }

    private fun updateState() {
        val state = McpServerState.getInstance(project)
        val version = "v${pluginVersion()}"
        statusLabel.text = when (state.status) {
            McpServerState.Status.RUNNING ->
                "\u25CF  Running on localhost:${state.transport.removePrefix("HTTP :")}  |  Clients: ${state.connectedClients}  |  $version"
            McpServerState.Status.STOPPED ->
                "\u25CB  Stopped  |  $version"
        }
        statusLabel.foreground = when (state.status) {
            McpServerState.Status.RUNNING -> JBUI.CurrentTheme.Link.Foreground.ENABLED
            McpServerState.Status.STOPPED -> UIUtil.getLabelDisabledForeground()
        }

        // Update tool window icon
        val tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
        tw?.setIcon(
            if (state.isRunning) McpIcons.StatusActive else McpIcons.StatusInactive
        )
    }

    private fun updateLog() {
        val entries = McpActivityLog.getInstance(project).recentEntries
        logListModel.clear()
        for (entry in entries.asReversed()) {
            logListModel.addElement("[${entry.timestamp}] ${entry.message}")
        }
    }

    override fun dispose() {}
}

// --- Toolbar Actions ---

private class StartAction(private val project: Project) :
    AnAction("Start Server", "Start the MCP server", AllIcons.Actions.Execute), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        McpServerService.getInstance(project).start()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = !McpServerService.getInstance(project).isRunning
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

private class StopAction(private val project: Project) :
    AnAction("Stop Server", "Stop the MCP server", AllIcons.Actions.Suspend), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        McpServerService.getInstance(project).stop()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = McpServerService.getInstance(project).isRunning
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

private class SettingsAction(private val project: Project) :
    AnAction("Settings", "MCP server settings", AllIcons.General.GearPlain), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, McpSettingsConfigurable::class.java)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

private class InstallMcpAction(private val project: Project) :
    AnAction(null, null, AllIcons.Actions.Download), DumbAware {

    private fun mcpFile(): File? {
        val basePath = project.basePath ?: return null
        return File(basePath, ".mcp.json")
    }

    override fun update(e: AnActionEvent) {
        val exists = mcpFile()?.exists() == true
        e.presentation.text = if (exists) "Update .mcp.json" else "Install .mcp.json"
        e.presentation.description = if (exists)
            "Update MCP Hub entry in .mcp.json" else "Create .mcp.json in the project root"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val mcpFile = mcpFile() ?: return
        val settings = McpSettings.getInstance(project)
        val serverKey = "mcp-hub"
        val serverUrl = "http://127.0.0.1:${settings.port}/mcp"

        val existed = mcpFile.exists()
        val root = if (existed) {
            try {
                Json.parseToJsonElement(mcpFile.readText()).jsonObject.toMutableMap()
            } catch (_: Exception) {
                mutableMapOf()
            }
        } else {
            mutableMapOf()
        }

        // Merge into existing mcpServers, preserving other entries
        val existingServers = root["mcpServers"]
            ?.jsonObject?.toMutableMap() ?: mutableMapOf()

        existingServers[serverKey] = buildJsonObject {
            put("url", serverUrl)
        }

        root["mcpServers"] = JsonObject(existingServers)

        val json = Json { prettyPrint = true }
        mcpFile.writeText(json.encodeToString(JsonObject.serializer(), JsonObject(root)))

        val action = if (existed) "Updated" else "Installed"
        NotificationGroupManager.getInstance()
            .getNotificationGroup("MCP Hub")
            .createNotification(
                "$action .mcp.json",
                "MCP Hub configured at $serverUrl in ${mcpFile.path}",
                NotificationType.INFORMATION
            )
            .notify(project)

        McpActivityLog.getInstance(project).log("$action MCP Hub in .mcp.json at ${mcpFile.path}")
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

private class ClearLogAction(private val project: Project) :
    AnAction("Clear Log", "Clear the activity log", AllIcons.Actions.GC), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        McpActivityLog.getInstance(project).clear()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}
