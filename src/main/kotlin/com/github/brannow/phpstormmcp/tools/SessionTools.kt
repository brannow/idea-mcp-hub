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
    // Track the IDE's auto-focused session so the agent can use debug tools immediately
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

}
