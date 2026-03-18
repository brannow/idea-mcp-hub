package com.github.brannow.phpstormmcp.tools

import com.github.brannow.phpstormmcp.statusbar.McpActivityLog
import com.intellij.openapi.project.Project
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal fun handleSessionList(service: SessionService): CallToolResult {
    val sessions = service.listSessions()
    return if (sessions.isEmpty()) {
        ok("No sessions in project")
    } else {
        ok(formatSessionList(sessions))
    }
}

internal fun handleSessionStop(service: SessionService, sessionId: String?, all: Boolean): CallToolResult {
    if (all) {
        val stopped = service.stopAllSessions()
        return if (stopped.isEmpty()) {
            ok("No sessions in project")
        } else {
            ok(formatSessionList(stopped))
        }
    }

    // No explicit ID → stop the active/first session (no-op if none exist)
    if (sessionId == null) {
        val sessions = service.listSessions()
        if (sessions.isEmpty()) return ok("No sessions in project")
    }

    val info = service.stopSmart(sessionId)
    val remaining = service.listSessions()
    return if (remaining.isEmpty()) {
        ok(formatSession(info))
    } else {
        ok("${formatSession(info)}\n\n${remaining.size} session(s) remaining:\n${formatSessionList(remaining)}")
    }
}

fun Server.registerSessionTools(project: Project) {
    val service = SessionService.getInstance(project)
    val activityLog = McpActivityLog.getInstance(project)

    // --- session_list ---
    addTool(
        name = "session_list",
        description = "List active debug sessions with their current position and status.",
        toolAnnotations = ToolAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = false,
            openWorldHint = false,
        ),
        inputSchema = ToolSchema(
            properties = buildJsonObject {},
            required = emptyList()
        )
    ) { _ ->
        activityLog.log("session_list")
        try {
            handleSessionList(service)
        } catch (e: Exception) {
            err(e.message ?: "Unknown error")
        }
    }

    // --- session_stop ---
    addTool(
        name = "session_stop",
        description = "Stop one or more debug sessions.",
        toolAnnotations = ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = true,
            idempotentHint = false,
            openWorldHint = false,
        ),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("session_id") {
                    put("type", "string")
                    put("description", "ID of the session to stop (from session_list). Omit to stop the active session.")
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
            handleSessionStop(service, sessionId, all)
        } catch (e: SessionNotFoundException) {
            if (e.activeSessions.isEmpty()) {
                err("Session '${e.requestedId}' not found, no sessions in project")
            } else {
                err("Session '${e.requestedId}' not found, current sessions:\n\n${formatSessionList(e.activeSessions)}")
            }
        } catch (e: Exception) {
            err(e.message ?: "Unknown error")
        }
    }
}
