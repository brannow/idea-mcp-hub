package com.github.brannow.phpstormmcp.statusbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

class McpStatusWidgetFactory : StatusBarWidgetFactory {

    companion object {
        const val WIDGET_ID = "McpStatusWidget"
    }

    override fun getId(): String = WIDGET_ID

    override fun getDisplayName(): String = "PhpStorm MCP"

    override fun isEnabledByDefault(): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget {
        return McpStatusBarWidget(project)
    }
}
