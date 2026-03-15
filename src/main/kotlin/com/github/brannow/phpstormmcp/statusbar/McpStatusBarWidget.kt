package com.github.brannow.phpstormmcp.statusbar

import com.github.brannow.phpstormmcp.server.McpServerService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidget.MultipleTextValuesPresentation
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.*
import javax.swing.border.CompoundBorder

class McpStatusBarWidget(private val project: Project) : StatusBarWidget, MultipleTextValuesPresentation {

    private var statusBar: StatusBar? = null

    override fun ID(): String = McpStatusWidgetFactory.WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun dispose() {}

    override fun getTooltipText(): String {
        val state = McpServerState.getInstance(project)
        return when (state.status) {
            McpServerState.Status.RUNNING -> "MCP Server: Running (${state.connectedClients} client(s))"
            McpServerState.Status.STOPPED -> "MCP Server: Stopped"
        }
    }

    override fun getSelectedValue(): String {
        val state = McpServerState.getInstance(project)
        return when (state.status) {
            McpServerState.Status.RUNNING -> "MCP"
            McpServerState.Status.STOPPED -> "MCP"
        }
    }

    override fun getIcon(): Icon {
        val state = McpServerState.getInstance(project)
        return when (state.status) {
            McpServerState.Status.RUNNING -> McpIcons.StatusActive
            McpServerState.Status.STOPPED -> McpIcons.StatusInactive
        }
    }

    override fun getPopup(): JBPopup {
        val state = McpServerState.getInstance(project)
        val activityLog = McpActivityLog.getInstance(project)

        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            preferredSize = Dimension(340, 300)
        }

        // --- Header: Status info ---
        val statusPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyBottom(8)

            val statusText = when (state.status) {
                McpServerState.Status.RUNNING -> "Running"
                McpServerState.Status.STOPPED -> "Stopped"
            }
            add(createInfoRow("Status:", statusText))
            add(Box.createVerticalStrut(2))
            add(createInfoRow("Transport:", state.transport))
            add(Box.createVerticalStrut(2))
            add(createInfoRow("Clients:", state.connectedClients.toString()))
        }
        panel.add(statusPanel, BorderLayout.NORTH)

        // --- Activity log ---
        val logPanel = JPanel(BorderLayout()).apply {
            border = CompoundBorder(
                JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1, 0, 0, 0),
                JBUI.Borders.emptyTop(6)
            )
        }

        val logLabel = JBLabel("Activity Log").apply {
            font = UIUtil.getLabelFont().deriveFont(UIUtil.getFontSize(UIUtil.FontSize.SMALL))
            foreground = UIUtil.getLabelDisabledForeground()
            border = JBUI.Borders.emptyBottom(4)
        }
        logPanel.add(logLabel, BorderLayout.NORTH)

        val logListModel = DefaultListModel<String>()
        for (entry in activityLog.recentEntries.asReversed()) {
            logListModel.addElement("[${entry.timestamp}] ${entry.message}")
        }
        if (logListModel.isEmpty) {
            logListModel.addElement("No activity yet")
        }

        val logList = JList(logListModel).apply {
            font = UIUtil.getLabelFont().deriveFont(UIUtil.getFontSize(UIUtil.FontSize.MINI))
            selectionMode = ListSelectionModel.SINGLE_SELECTION
        }
        val scrollPane = JBScrollPane(logList).apply {
            border = JBUI.Borders.empty()
        }
        logPanel.add(scrollPane, BorderLayout.CENTER)

        panel.add(logPanel, BorderLayout.CENTER)

        // --- Footer: Start/Stop button ---
        val buttonPanel = JPanel(BorderLayout()).apply {
            border = CompoundBorder(
                JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1, 0, 0, 0),
                JBUI.Borders.emptyTop(8)
            )
        }

        val toggleButton = JButton(
            when (state.status) {
                McpServerState.Status.RUNNING -> "Stop Server"
                McpServerState.Status.STOPPED -> "Start Server"
            }
        )
        buttonPanel.add(toggleButton, BorderLayout.CENTER)

        panel.add(buttonPanel, BorderLayout.SOUTH)

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, null)
            .setTitle("PhpStorm MCP")
            .setFocusable(true)
            .setRequestFocus(true)
            .setMovable(false)
            .setResizable(false)
            .createPopup()

        toggleButton.addActionListener {
            val mcpService = McpServerService.getInstance(project)
            when (state.status) {
                McpServerState.Status.RUNNING -> mcpService.stop()
                McpServerState.Status.STOPPED -> mcpService.start()
            }
            popup.cancel()
        }

        return popup
    }

    private fun createInfoRow(label: String, value: String): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 20)
            alignmentX = Component.LEFT_ALIGNMENT

            add(JBLabel(label).apply {
                foreground = UIUtil.getLabelDisabledForeground()
                preferredSize = Dimension(70, 16)
            }, BorderLayout.WEST)

            add(JBLabel(value).apply {
                font = font.deriveFont(java.awt.Font.BOLD)
            }, BorderLayout.CENTER)
        }
    }
}
