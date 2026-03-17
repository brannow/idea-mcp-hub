package com.github.brannow.phpstormmcp.tools

import com.github.brannow.phpstormmcp.statusbar.McpActivityLog
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*

/**
 * Resolve a paused debug session, or return an error result.
 */
internal fun resolvePausedSession(project: Project, sessionId: String?): Pair<XDebugSession?, CallToolResult?> {
    val manager = XDebuggerManager.getInstance(project)

    val session = if (sessionId != null) {
        val cleanId = sessionId.trimStart('#').trim()
        manager.debugSessions.firstOrNull {
            System.identityHashCode(it).toString() == cleanId
        } ?: return null to err("Session '#$cleanId' not found")
    } else {
        manager.currentSession ?: return null to err("No active debug session")
    }

    if (session.isStopped) return null to err("Session has ended")
    if (!session.isSuspended) return null to err("Session is running — not paused at a breakpoint")

    return session to null
}

/**
 * Shared data extraction from a paused session.
 * Used by debug_snapshot and debug_variable_detail.
 */
internal fun extractSourceContext(
    session: XDebugSession,
    sourceService: SourceContextService
): SourceContext? {
    val position = session.currentPosition ?: return null
    return sourceService.getSourceContext(position.file, position.line + 1)
}

internal fun extractStackFrames(
    session: XDebugSession,
    stackFrameService: StackFrameService
): List<FrameInfo>? {
    val suspendContext = session.suspendContext ?: return null
    return stackFrameService.getStackFrames(suspendContext)
}

internal fun extractVariables(
    session: XDebugSession,
    variableService: VariableService
): List<VariableInfo>? {
    val suspendContext = session.suspendContext ?: return null
    val stack = suspendContext.activeExecutionStack ?: return null
    val frame = stack.topFrame ?: return null
    return variableService.getVariables(frame)
}

private val readOnlyAnnotations = ToolAnnotations(
    readOnlyHint = true,
    destructiveHint = false,
    idempotentHint = false,
    openWorldHint = false,
)


fun Server.registerDebugTools(project: Project) {
    val activityLog = McpActivityLog.getInstance(project)
    val sourceService = SourceContextService.getInstance(project)
    val stackFrameService = StackFrameService.getInstance(project)
    val variableService = VariableService.getInstance(project)
    val sessionService = SessionService.getInstance(project)

    // --- debug_variable_detail ---
    addTool(
        name = "debug_variable_detail",
        description = "Expand variables to see their properties/children. Requires a paused debug session. " +
                "Omit path to expand all top-level variables. " +
                "Use dot-path notation to drill into nested structures: \$engine, \$engine.pattern, \$items.0.name. " +
                "Short property names work for inherited properties (e.g. 'parent' matches '*AstNode*parent'). " +
                "Circular object references are detected and marked automatically.",
        toolAnnotations = readOnlyAnnotations,
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Variable path(s) using dot notation. Comma-separated for multiple. " +
                            "Omit to show all variables. " +
                            "Examples: \"\$engine\", \"\$engine.pattern\", \"\$engine, \$result\"")
                }
                putJsonObject("depth") {
                    put("type", "integer")
                    put("description", "How many levels of children to expand. Default: 1. " +
                            "Use 0 for just type and value without expanding children.")
                }
                putJsonObject("globals") {
                    put("type", "boolean")
                    put("description", "Include PHP superglobals (\$_SERVER, \$_ENV, etc.). Default: false. Only applies when no path is specified.")
                }
                putJsonObject("session_id") {
                    put("type", "string")
                    put("description", "ID of the debug session (from session_list). Omit to use the active session.")
                }
            },
            required = emptyList()
        )
    ) { request ->
        activityLog.log("debug_variable_detail")
        try {
            val path = request.arguments?.get("path")?.jsonPrimitive?.content
            val depth = request.arguments?.get("depth")?.jsonPrimitive?.intOrNull ?: 1
            val includeGlobals = request.arguments?.get("globals")?.jsonPrimitive?.booleanOrNull ?: false
            val sessionId = request.arguments?.get("session_id")?.jsonPrimitive?.content
            val (session, error) = resolvePausedSession(project, sessionId)
            if (error != null) return@addTool error

            val suspendContext = session!!.suspendContext
                ?: return@addTool err("No suspend context — session may be between steps")
            val stack = suspendContext.activeExecutionStack
                ?: return@addTool err("No execution stack available")
            val frame = stack.topFrame
                ?: return@addTool err("No stack frame available")

            if (path == null) {
                // No path → expand all top-level variables
                val nodes = variableService.getAllVariableDetails(frame, depth)
                ok(formatVariableDetailList(filterGlobalNodes(nodes, includeGlobals)))
            } else {
                // Split comma-separated paths
                val paths = path.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (paths.size == 1) {
                    val node = variableService.getVariableDetail(frame, paths.first(), depth)
                    ok(formatVariableDetail(node, paths.first()))
                } else {
                    val nodes = paths.map { p ->
                        p to variableService.getVariableDetail(frame, p, depth)
                    }
                    ok(nodes.joinToString("\n\n") { (p, node) -> formatVariableDetail(node, p) })
                }
            }
        } catch (e: VariablePathException) {
            err(e.message ?: "Variable not found")
        } catch (e: Exception) {
            err(e.message ?: "Unknown error")
        }
    }

    // --- debug_snapshot ---
    addTool(
        name = "debug_snapshot",
        description = "Get the current debug state. Requires a paused debug session. " +
                "Returns session info, source context (±5 lines around current position), top-level variables, and call stack. " +
                "Use 'include' to request only specific parts for token efficiency.",
        toolAnnotations = readOnlyAnnotations,
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("session_id") {
                    put("type", "string")
                    put("description", "ID of the debug session (from session_list). Omit to use the active session.")
                }
                putJsonObject("include") {
                    put("type", "array")
                    put("description", "Parts to include: \"source\", \"variables\", \"stacktrace\". " +
                            "Omit for full snapshot. Session info is always included.")
                    putJsonObject("items") {
                        put("type", "string")
                        putJsonArray("enum") {
                            add("source")
                            add("variables")
                            add("stacktrace")
                        }
                    }
                }
                putJsonObject("globals") {
                    put("type", "boolean")
                    put("description", "Include PHP superglobals (\$_SERVER, \$_ENV, \$_GET, etc.). Default: false.")
                }
            },
            required = emptyList()
        )
    ) { request ->
        activityLog.log("debug_snapshot")
        try {
            val sessionId = request.arguments?.get("session_id")?.jsonPrimitive?.content
            val (session, error) = resolvePausedSession(project, sessionId)
            if (error != null) return@addTool error

            val includeParam = request.arguments?.get("include")?.jsonArray
                ?.map { it.jsonPrimitive.content }
                ?.toSet()
            val includeGlobals = request.arguments?.get("globals")?.jsonPrimitive?.booleanOrNull ?: false
            // null = include everything
            val includeSource = includeParam == null || "source" in includeParam
            val includeVars = includeParam == null || "variables" in includeParam
            val includeStack = includeParam == null || "stacktrace" in includeParam

            val sessionInfo = sessionService.listSessions().firstOrNull {
                it.id == System.identityHashCode(session!!).toString()
            }

            val source = if (includeSource) extractSourceContext(session!!, sourceService) else null
            val variables = if (includeVars) {
                extractVariables(session!!, variableService)?.let { filterGlobals(it, includeGlobals) }
            } else null
            val frames = if (includeStack) extractStackFrames(session!!, stackFrameService) else null

            ok(formatSnapshot(sessionInfo, source, variables, frames))
        } catch (e: Exception) {
            err(e.message ?: "Unknown error")
        }
    }
}
