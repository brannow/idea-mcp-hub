package com.github.brannow.phpstormmcp.statusbar

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object McpIcons {
    @JvmField
    val StatusActive: Icon = IconLoader.getIcon("/icons/mcpStatusActive.svg", McpIcons::class.java)

    @JvmField
    val StatusInactive: Icon = IconLoader.getIcon("/icons/mcpStatusInactive.svg", McpIcons::class.java)
}
