package com.github.brannow.phpstormmcp.tools

import com.github.brannow.phpstormmcp.statusbar.McpActivityLog
import com.intellij.openapi.project.Project
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

fun Server.registerSessionTools(project: Project) {
    val service = SessionService.getInstance(project)
    val activityLog = McpActivityLog.getInstance(project)

    addTool(
        name = "session_list",
        description = "List active debug sessions with their status (paused/running), current file and line, and which session is active.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {},
            required = emptyList()
        )
    ) { _ ->
        activityLog.log("session_list")
        try {
            val sessions = service.listSessions()
            if (sessions.isEmpty()) {
                CallToolResult(content = listOf(TextContent("No active debug sessions")))
            } else {
                val json = SessionService.json.encodeToString(sessions)
                CallToolResult(content = listOf(TextContent(json)))
            }
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
        }
    }

    addTool(
        name = "session_stop",
        description = "Stop debug session(s). With no arguments: stops the only active session, or errors if multiple exist. Use session_id to target a specific session, or all=true to stop everything.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("session_id") {
                    put("type", "string")
                    put("description", "ID of the session to stop (from session_list). Omit if only one session is active.")
                }
                putJsonObject("all") {
                    put("type", "boolean")
                    put("description", "Set to true to stop all active debug sessions")
                }
            },
            required = emptyList()
        )
    ) { request ->
        val sessionId = request.arguments?.get("session_id")?.jsonPrimitive?.content
        val all = request.arguments?.get("all")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

        activityLog.log("session_stop" + when {
            all -> " (all)"
            sessionId != null -> " ($sessionId)"
            else -> ""
        })

        try {
            if (all) {
                val count = service.stopAllSessions()
                val response = buildJsonObject {
                    put("stopped", count)
                    put("message", if (count > 0) "Stopped $count session(s)" else "No active sessions to stop")
                }
                CallToolResult(content = listOf(TextContent(response.toString())))
            } else {
                val (message, _) = service.stopSmart(sessionId)
                val response = buildJsonObject {
                    put("message", message)
                }
                CallToolResult(content = listOf(TextContent(response.toString())))
            }
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
        }
    }
}
