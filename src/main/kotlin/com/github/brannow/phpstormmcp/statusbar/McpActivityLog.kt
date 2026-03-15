package com.github.brannow.phpstormmcp.statusbar

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class McpActivityLog {

    data class Entry(
        val timestamp: String,
        val message: String
    )

    private val entries = CopyOnWriteArrayList<Entry>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    val recentEntries: List<Entry>
        get() = entries.toList()

    fun log(message: String) {
        val timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        entries.add(Entry(timestamp, message))
        // Keep only the last 50 entries
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
        listeners.forEach { it() }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    companion object {
        private const val MAX_ENTRIES = 50

        fun getInstance(project: Project): McpActivityLog {
            return project.getService(McpActivityLog::class.java)
        }
    }
}
