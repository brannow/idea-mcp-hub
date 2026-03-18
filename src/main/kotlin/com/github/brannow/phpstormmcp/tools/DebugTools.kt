package com.github.brannow.phpstormmcp.tools

import com.github.brannow.phpstormmcp.statusbar.McpActivityLog
import com.intellij.openapi.application.ApplicationManager
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
        description = "Expand a variable's properties and nested children. " +
                "Use when debug_snapshot shows a type preview like {User} or array(5) that you need to see inside. " +
                "Requires a paused session.",
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

    // --- debug_inspect_frame ---
    addTool(
        name = "debug_inspect_frame",
        description = "View source and variables at a different call stack depth. " +
                "Use frame indices from debug_snapshot's stacktrace. Requires a paused session.",
        toolAnnotations = readOnlyAnnotations,
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("frame_index") {
                    put("type", "integer")
                    put("description", "Stack frame index: 0 = current (top), 1 = caller, etc. " +
                            "Use debug_snapshot with include: [\"stacktrace\"] to see available frames.")
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
                    put("description", "Include PHP superglobals in variables. Default: false.")
                }
                putJsonObject("session_id") {
                    put("type", "string")
                    put("description", "ID of the debug session (from session_list). Omit to use the active session.")
                }
            },
            required = listOf("frame_index")
        )
    ) { request ->
        activityLog.log("debug_inspect_frame")
        try {
            val frameIndex = request.arguments?.get("frame_index")?.jsonPrimitive?.intOrNull
                ?: return@addTool err("Missing required parameter: frame_index")
            if (frameIndex < 0) return@addTool err("frame_index must be >= 0")

            val sessionId = request.arguments?.get("session_id")?.jsonPrimitive?.content
            val (session, error) = resolvePausedSession(project, sessionId)
            if (error != null) return@addTool error

            val suspendContext = session!!.suspendContext
                ?: return@addTool err("No suspend context — session may be between steps")

            val rawFrames = stackFrameService.getRawFrames(suspendContext)
            if (rawFrames.isEmpty()) return@addTool err("No stack frames available")
            if (frameIndex >= rawFrames.size) {
                return@addTool err("frame_index $frameIndex out of range (0..${rawFrames.size - 1})")
            }

            val targetFrame = rawFrames[frameIndex]
            val executionStack = suspendContext.activeExecutionStack
                ?: return@addTool err("No execution stack available")

            // Switch frame in PhpStorm (like clicking the row), then read the snapshot
            ApplicationManager.getApplication().invokeAndWait {
                session.setCurrentStackFrame(executionStack, targetFrame, frameIndex == 0)
            }

            // Now read the state — same path as debug_snapshot
            val includeParam = request.arguments?.get("include")?.jsonArray
                ?.map { it.jsonPrimitive.content }
                ?.toSet()
                ?.ifEmpty { null }
            val includeGlobals = request.arguments?.get("globals")?.jsonPrimitive?.booleanOrNull ?: false
            val includeSource = includeParam == null || "source" in includeParam
            val includeVars = includeParam == null || "variables" in includeParam
            val includeStack = includeParam == null || "stacktrace" in includeParam

            val sessionInfo = sessionService.listSessions().firstOrNull {
                it.id == System.identityHashCode(session).toString()
            }

            val source = if (includeSource) extractSourceContext(session, sourceService) else null
            val variables = if (includeVars) {
                extractVariables(session, variableService)?.let { filterGlobals(it, includeGlobals) }
            } else null
            val frames = if (includeStack) extractStackFrames(session, stackFrameService) else null

            ok(formatSnapshot(sessionInfo, source, variables, frames, activeDepth = frameIndex))
        } catch (e: Exception) {
            err(e.message ?: "Unknown error")
        }
    }

    // --- debug_evaluate ---
    addTool(
        name = "debug_evaluate",
        description = "Evaluate a PHP expression in the current debug scope — " +
                "test ideas, call methods, or modify variables. Requires a paused session.",
        toolAnnotations = ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = false,
            openWorldHint = true,
        ),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("expression") {
                    put("type", "string")
                    put("description", "PHP expression to evaluate. Examples: " +
                            "\"count(\$items)\", \"\$user->getName()\", " +
                            "\"\$this->repository->findAll()\", \"array_keys(\$config)\"")
                }
                putJsonObject("depth") {
                    put("type", "integer")
                    put("description", "Expansion depth for object/array results. Default: 1 (shows immediate properties). " +
                            "Use 0 for just type and value, 2+ for deeper nesting.")
                }
                putJsonObject("session_id") {
                    put("type", "string")
                    put("description", "ID of the debug session (from session_list). Omit to use the active session.")
                }
            },
            required = listOf("expression")
        )
    ) { request ->
        activityLog.log("debug_evaluate")
        try {
            val expression = request.arguments?.get("expression")?.jsonPrimitive?.content
                ?: return@addTool err("Missing required parameter: expression")
            val depth = request.arguments?.get("depth")?.jsonPrimitive?.intOrNull ?: 1
            val sessionId = request.arguments?.get("session_id")?.jsonPrimitive?.content
            val (session, error) = resolvePausedSession(project, sessionId)
            if (error != null) return@addTool error

            val suspendContext = session!!.suspendContext
                ?: return@addTool err("No suspend context — session may be between steps")
            val stack = suspendContext.activeExecutionStack
                ?: return@addTool err("No execution stack available")
            val frame = stack.topFrame
                ?: return@addTool err("No stack frame available")

            // Position context (best-effort)
            val sourceHeader = try {
                extractSourceContext(session, sourceService)?.let { formatSourceHeader(it) }
            } catch (_: Exception) { null }

            val node = try {
                variableService.evaluateExpression(frame, expression, depth)
            } catch (e: EvaluationException) {
                val prefix = if (sourceHeader != null) "at $sourceHeader\n\n" else ""
                val msg = (e.message ?: "Unknown error")
                    .removePrefix("error evaluating code: ")
                    .removePrefix("Error evaluating code: ")
                return@addTool err("$prefix$msg")
            }

            ok(formatEvaluationResult(expression, node, sourceHeader))
        } catch (e: Exception) {
            err(e.message ?: "Unknown error")
        }
    }

    // --- debug_snapshot ---
    addTool(
        name = "debug_snapshot",
        description = "Get the current debug state: position, source code, variables, and call stack. " +
                "Use debug_variable_detail to expand variables shown as previews. Requires a paused session.",
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
                ?.ifEmpty { null }
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
