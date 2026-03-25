package com.github.brannow.phpstormmcp.tools

import com.github.brannow.phpstormmcp.statusbar.McpActivityLog
import com.intellij.execution.console.ConsoleViewWrapperBase
import com.intellij.execution.console.DuplexConsoleView
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*

/**
 * Tracks which session the agent is working with.
 * Detects when the active session changes externally (e.g., user clicks a different debug tab)
 * and informs the agent via a notice prepended to the next tool response.
 *
 * Updated by: resolvePausedSession (on success), session_stop (auto-track remaining)
 * Cleared by: session_stop (all), session ended
 */
internal object AgentSessionTracker {
    @Volatile
    var lastSessionId: String? = null

    @Volatile
    var pendingNotice: String? = null

    fun track(session: XDebugSession) {
        lastSessionId = System.identityHashCode(session).toString()
    }

    fun trackById(sessionId: String) {
        lastSessionId = sessionId
    }

    fun clear() {
        lastSessionId = null
        pendingNotice = null
    }

    fun consumeNotice(): String? {
        val n = pendingNotice
        pendingNotice = null
        return n
    }

    /**
     * Check if the active session changed unexpectedly.
     * If switched, auto-tracks the new session and sets [pendingNotice].
     * Returns the notice text, or null if no switch occurred.
     */
    fun checkSessionSwitch(
        currentSessionId: String,
        currentSessionName: String,
        previousSessionStatus: (lastId: String) -> String
    ): String? {
        val lastId = lastSessionId ?: return null
        if (lastId == currentSessionId) return null

        // Auto-track the new session
        lastSessionId = currentSessionId

        val status = previousSessionStatus(lastId)
        val notice = "Note: Active session changed from #$lastId ($status) to #$currentSessionId \"$currentSessionName\".\n" +
            "Ask the user to switch debug tabs in PhpStorm if you need session #$lastId."
        pendingNotice = notice
        return notice
    }
}

/**
 * Wraps a successful [CallToolResult] with a session switch notice if one is pending.
 * Consumes the notice so it only appears once.
 */
internal fun withSessionNotice(result: CallToolResult): CallToolResult {
    val notice = AgentSessionTracker.consumeNotice() ?: return result
    if (result.isError == true) return result
    val first = result.content.firstOrNull()
    if (first is TextContent) {
        return CallToolResult(
            content = listOf(TextContent("$notice\n\n${first.text}")) + result.content.drop(1),
            isError = result.isError
        )
    }
    return result
}

/**
 * Resolve the active debug session (must not be stopped, but can be running or paused).
 * Includes session switch detection.
 */
internal fun resolveActiveSession(project: Project): Pair<XDebugSession?, CallToolResult?> {
    val manager = XDebuggerManager.getInstance(project)
    val sessionService = SessionService.getInstance(project)
    val session = manager.currentSession

    if (session == null || session.isStopped) {
        AgentSessionTracker.clear()
        val alive = sessionService.listSessions()
        if (alive.isEmpty()) {
            return null to err("No debug session")
        }
        return null to err("Session ended. Other sessions:\n\n${formatSessionList(alive)}\n\nThe IDE does not auto-activate a remaining session. Ask the user to select the desired session in the IDE's Debug tool window, then call your tool again.")
    }

    // Check for unexpected session switch
    val currentId = System.identityHashCode(session).toString()
    val lastId = AgentSessionTracker.lastSessionId
    if (lastId != null && lastId != currentId) {
        // Previous session terminated? Auto-track the new one — no need to block.
        val previousSession = manager.debugSessions.firstOrNull {
            System.identityHashCode(it).toString() == lastId
        }
        val previousAlive = previousSession != null && !previousSession.isStopped

        if (previousAlive) {
            // Previous session is still alive — inform the agent but don't block
            val status = if (previousSession!!.isSuspended) "paused" else "running"
            AgentSessionTracker.checkSessionSwitch(
                currentSessionId = currentId,
                currentSessionName = session.sessionName
            ) { status }
            // Notice is stored on AgentSessionTracker.pendingNotice,
            // consumed by withSessionNotice() in the tool handler
        } else {
            // Previous session terminated — let the agent know
            AgentSessionTracker.pendingNotice = "Note: Previous session #$lastId terminated. Now using session #$currentId \"${session.sessionName}\"."
        }
    }

    AgentSessionTracker.track(session)
    return session to null
}

/**
 * Resolve the active paused debug session, or return an error result.
 * Always uses the current (active) session — the one focused in the IDE's debug panel.
 *
 * If the active session changed externally (user switched tabs), a notice is stored
 * on [AgentSessionTracker.pendingNotice] and consumed by [withSessionNotice].
 */
internal fun resolvePausedSession(project: Project): Pair<XDebugSession?, CallToolResult?> {
    val (session, error) = resolveActiveSession(project)
    if (error != null) return null to error
    if (!session!!.isSuspended) return null to err("Session is running — not paused at a breakpoint")
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

/**
 * Recursively unwrap a ConsoleView to find the underlying ConsoleViewImpl.
 * Handles DuplexConsoleView (PHP debug uses this) and ConsoleViewWrapperBase.
 */
internal fun findConsoleViewImpl(view: ConsoleView?): ConsoleViewImpl? {
    return when (view) {
        is ConsoleViewImpl -> view
        is DuplexConsoleView<*, *> -> {
            findConsoleViewImpl(view.primaryConsoleView as? ConsoleView)
                ?: findConsoleViewImpl(view.secondaryConsoleView as? ConsoleView)
        }
        is ConsoleViewWrapperBase -> findConsoleViewImpl(view.delegate)
        else -> null
    }
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
            },
            required = emptyList()
        )
    ) { request ->
        activityLog.log("debug_variable_detail")
        try {
            val path = request.arguments?.get("path")?.jsonPrimitive?.content
            val depth = request.arguments?.get("depth")?.jsonPrimitive?.intOrNull ?: 1
            val includeGlobals = request.arguments?.get("globals")?.jsonPrimitive?.booleanOrNull ?: false
            val (session, error) = resolvePausedSession(project)
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
                withSessionNotice(ok(formatVariableDetailList(filterGlobalNodes(nodes, includeGlobals))))
            } else {
                // Split comma-separated paths
                val paths = path.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (paths.size == 1) {
                    val node = variableService.getVariableDetail(frame, paths.first(), depth)
                    withSessionNotice(ok(formatVariableDetail(node, paths.first())))
                } else {
                    val nodes = paths.map { p ->
                        p to variableService.getVariableDetail(frame, p, depth)
                    }
                    withSessionNotice(ok(nodes.joinToString("\n\n") { (p, node) -> formatVariableDetail(node, p) }))
                }
            }
        } catch (e: ProcessCanceledException) {
            throw e
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
                putJsonObject("expand_stack") {
                    put("type", "boolean")
                    put("description", "Show all stack frames including library frames. " +
                            "Default: false (consecutive library frames are collapsed).")
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

            val (session, error) = resolvePausedSession(project)
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
            val expandStack = request.arguments?.get("expand_stack")?.jsonPrimitive?.booleanOrNull ?: false
            val includeSource = includeParam == null || "source" in includeParam
            val includeVars = includeParam == null || "variables" in includeParam
            val includeStack = includeParam == null || "stacktrace" in includeParam

            val sessionInfo = sessionService.listSessions().firstOrNull {
                it.id == System.identityHashCode(session).toString()
            }

            val source = if (includeSource) extractSourceContext(session, sourceService) else null
            val filtered = if (includeVars) {
                extractVariables(session, variableService)?.let { filterGlobals(it, includeGlobals) }
            } else null
            val frames = if (includeStack) extractStackFrames(session, stackFrameService) else null

            withSessionNotice(ok(formatSnapshot(sessionInfo, source, filtered?.variables, frames, activeDepth = frameIndex, collapseLibrary = !expandStack, hiddenGlobalCount = filtered?.hiddenGlobalCount ?: 0)))
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            err(e.message ?: "Unknown error")
        }
    }

    // --- debug_evaluate ---
    addTool(
        name = "debug_evaluate",
        description = "Evaluate a PHP expression in the top stack frame's scope (frame #0) — " +
                "test ideas, call methods, or modify variables. " +
                "Always uses frame #0 regardless of debug_inspect_frame. Requires a paused session.",
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
            },
            required = listOf("expression")
        )
    ) { request ->
        activityLog.log("debug_evaluate")
        try {
            val expression = request.arguments?.get("expression")?.jsonPrimitive?.content
                ?: return@addTool err("Missing required parameter: expression")
            val depth = request.arguments?.get("depth")?.jsonPrimitive?.intOrNull ?: 1
            val (session, error) = resolvePausedSession(project)
            if (error != null) return@addTool error

            val suspendContext = session!!.suspendContext
                ?: return@addTool err("No suspend context — session may be between steps")
            val stack = suspendContext.activeExecutionStack
                ?: return@addTool err("No execution stack available")
            val frame = stack.topFrame
                ?: return@addTool err("No stack frame available")

            val node = try {
                variableService.evaluateExpression(frame, expression, depth)
            } catch (e: EvaluationException) {
                val msg = (e.message ?: "Unknown error")
                    .removePrefix("error evaluating code: ")
                    .removePrefix("Error evaluating code: ")
                return@addTool err(msg)
            }

            withSessionNotice(ok(formatEvaluationResult(expression, node)))
        } catch (e: ProcessCanceledException) {
            throw e
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
                putJsonObject("expand_stack") {
                    put("type", "boolean")
                    put("description", "Show all stack frames including library frames. " +
                            "Default: false (consecutive library frames are collapsed).")
                }
            },
            required = emptyList()
        )
    ) { request ->
        activityLog.log("debug_snapshot")
        try {
            val (session, error) = resolvePausedSession(project)
            if (error != null) return@addTool error

            val includeParam = request.arguments?.get("include")?.jsonArray
                ?.map { it.jsonPrimitive.content }
                ?.toSet()
                ?.ifEmpty { null }
            val includeGlobals = request.arguments?.get("globals")?.jsonPrimitive?.booleanOrNull ?: false
            val expandStack = request.arguments?.get("expand_stack")?.jsonPrimitive?.booleanOrNull ?: false
            // null = include everything
            val includeSource = includeParam == null || "source" in includeParam
            val includeVars = includeParam == null || "variables" in includeParam
            val includeStack = includeParam == null || "stacktrace" in includeParam

            val sessionInfo = sessionService.listSessions().firstOrNull {
                it.id == System.identityHashCode(session!!).toString()
            }

            val source = if (includeSource) extractSourceContext(session!!, sourceService) else null
            val filtered = if (includeVars) {
                extractVariables(session!!, variableService)?.let { filterGlobals(it, includeGlobals) }
            } else null
            val frames = if (includeStack) extractStackFrames(session!!, stackFrameService) else null

            withSessionNotice(ok(formatSnapshot(sessionInfo, source, filtered?.variables, frames, collapseLibrary = !expandStack, hiddenGlobalCount = filtered?.hiddenGlobalCount ?: 0)))
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            err(e.message ?: "Unknown error")
        }
    }

    // --- debug_console ---
    addTool(
        name = "debug_console",
        description = "Read the debug session's console output (stdout/stderr). " +
                "Useful when the PHP process runs in Docker or a remote environment where the agent can't see stdout directly.",
        toolAnnotations = readOnlyAnnotations,
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("tail") {
                    put("type", "integer")
                    put("description", "Return only the last N lines. Default: 0 (all output).")
                }
            },
            required = emptyList()
        )
    ) { request ->
        activityLog.log("debug_console")
        try {
            val tail = request.arguments?.get("tail")?.jsonPrimitive?.intOrNull ?: 0
            val (session, error) = resolveActiveSession(project)
            if (error != null) return@addTool error

            val consoleImpl = findConsoleViewImpl(session!!.consoleView as? ConsoleView)
                ?: return@addTool err("Console not available")

            // flushDeferredText ensures pending output is written to the editor
            ApplicationManager.getApplication().invokeAndWait {
                consoleImpl.flushDeferredText()
            }

            val text = consoleImpl.editor?.document?.text ?: ""
            if (text.isBlank()) return@addTool withSessionNotice(ok("(empty)"))

            val output = if (tail > 0) {
                val lines = text.lines()
                lines.takeLast(tail).joinToString("\n")
            } else {
                text
            }

            withSessionNotice(ok(output))
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            err(e.message ?: "Unknown error")
        }
    }
}
