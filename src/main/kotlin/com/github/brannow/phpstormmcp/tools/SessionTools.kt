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

internal fun handleSessionList(service: SessionService, breakpoints: List<BreakpointInfo> = emptyList()): CallToolResult {
    val sessions = service.listSessions()
    return if (sessions.isEmpty()) {
        ok("No sessions in project")
    } else {
        ok(formatSessionList(sessions, breakpoints))
    }
}

internal fun handleSessionStop(service: SessionService, all: Boolean): CallToolResult {
    if (all) {
        val stopped = service.stopAllSessions()
        AgentSessionTracker.clear()
        return if (stopped.isEmpty()) {
            ok("No sessions in project")
        } else {
            ok(formatSessionList(stopped))
        }
    }

    // Stop the active/first session (no-op if none exist)
    val sessions = service.listSessions()
    if (sessions.isEmpty()) return ok("No sessions in project")

    val info = service.stopSmart(null)
    val remaining = service.listSessions()
    // Track the auto-activated session so the agent can use debug tools immediately
    val activeRemaining = remaining.firstOrNull { it.active }
    if (activeRemaining != null) {
        AgentSessionTracker.trackById(activeRemaining.id)
    } else {
        AgentSessionTracker.clear()
    }
    return if (remaining.isEmpty()) {
        ok(formatSession(info))
    } else {
        ok("${formatSession(info)}\n\n${remaining.size} session(s) remaining:\n${formatSessionList(remaining)}")
    }
}

internal fun handleSessionActivate(service: SessionService, sessionId: String): CallToolResult {
    val info = service.activateSession(sessionId)
    // Update tracker so resolvePausedSession accepts this session
    AgentSessionTracker.trackById(info.id)
    val sessions = service.listSessions()
    return if (sessions.size <= 1) {
        ok(formatSession(info))
    } else {
        ok("${formatSession(info)}\n\n${formatSessionList(sessions)}")
    }
}

fun Server.registerSessionTools(project: Project) {
    val service = SessionService.getInstance(project)
    val breakpointService = BreakpointService.getInstance(project)
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
            handleSessionList(service, breakpointService.listBreakpoints())
        } catch (e: Exception) {
            err(e.message ?: "Unknown error")
        }
    }

    // --- session_stop ---
    addTool(
        name = "session_stop",
        description = "Stop the active debug session, or all sessions.",
        toolAnnotations = ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = true,
            idempotentHint = false,
            openWorldHint = false,
        ),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("all") {
                    put("type", "boolean")
                    put("description", "Set to true to stop all active debug sessions. " +
                            "Omit to stop just the active session.")
                }
            },
            required = emptyList()
        )
    ) { request ->
        val all = request.arguments?.get("all")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

        activityLog.log("session_stop" + if (all) " (all)" else "")

        try {
            handleSessionStop(service, all)
        } catch (e: Exception) {
            err(e.message ?: "Unknown error")
        }
    }

    // --- session_activate ---
    addTool(
        name = "session_activate",
        description = "Switch the active debug session. All debug tools operate on the active session. " +
                "Use session_list to see available sessions and which one is active.",
        toolAnnotations = ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = false,
            openWorldHint = false,
        ),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("session_id") {
                    put("type", "string")
                    put("description", "ID of the session to activate (from session_list).")
                }
            },
            required = listOf("session_id")
        )
    ) { request ->
        val sessionId = request.arguments?.get("session_id")?.jsonPrimitive?.content
            ?: return@addTool err("Missing required parameter: session_id")

        activityLog.log("session_activate ($sessionId)")

        try {
            handleSessionActivate(service, sessionId)
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
