package com.github.brannow.phpstormmcp.tools

import com.github.brannow.phpstormmcp.statusbar.McpActivityLog
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerUtil
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*
import java.util.concurrent.CompletableFuture

/**
 * Result of waiting for a debug session after a navigation action.
 * Either the session paused again, or it stopped (ended).
 */
internal sealed class StepResult {
    data object Paused : StepResult()
    data object SessionEnded : StepResult()
}

/**
 * Register a temporary listener, execute a step action, and wait for
 * the session to either pause or stop.
 */
internal fun stepAndWait(session: XDebugSession, action: () -> Unit): StepResult {
    val future = CompletableFuture<StepResult>()

    val listener = object : XDebugSessionListener {
        override fun sessionPaused() {
            future.complete(StepResult.Paused)
        }

        override fun sessionStopped() {
            future.complete(StepResult.SessionEnded)
        }
    }

    session.addSessionListener(listener)
    try {
        action()
        return future.get()
    } finally {
        session.removeSessionListener(listener)
    }
}

private val navigationAnnotations = ToolAnnotations(
    readOnlyHint = false,
    destructiveHint = false,
    idempotentHint = false,
    openWorldHint = false,
)

internal fun buildSnapshotFromResult(
    result: StepResult,
    session: XDebugSession,
    sessionService: SessionService,
    sourceService: SourceContextService,
    variableService: VariableService,
    stackFrameService: StackFrameService,
    includeSource: Boolean,
    includeVars: Boolean,
    includeStack: Boolean,
    includeGlobals: Boolean,
    expandStack: Boolean = false
): CallToolResult {
    return when (result) {
        is StepResult.SessionEnded -> ok("Session ended")
        is StepResult.Paused -> {
            val sessionInfo = sessionService.listSessions().firstOrNull {
                it.id == System.identityHashCode(session).toString()
            }
            val source = if (includeSource) extractSourceContext(session, sourceService) else null
            val filtered = if (includeVars) {
                extractVariables(session, variableService)?.let { filterGlobals(it, includeGlobals) }
            } else null
            val frames = if (includeStack) extractStackFrames(session, stackFrameService) else null

            ok(formatSnapshot(sessionInfo, source, filtered?.variables, frames, collapseLibrary = !expandStack, hiddenGlobalCount = filtered?.hiddenGlobalCount ?: 0))
        }
    }
}

/**
 * Parse include/globals/expand_stack from request arguments.
 */
private data class SnapshotParams(
    val includeSource: Boolean,
    val includeVars: Boolean,
    val includeStack: Boolean,
    val includeGlobals: Boolean,
    val expandStack: Boolean,
)

private fun parseSnapshotParams(arguments: JsonObject?): SnapshotParams {
    val includeParam = arguments?.get("include")?.jsonArray
        ?.map { it.jsonPrimitive.content }
        ?.toSet()
        ?.ifEmpty { null }
    val includeGlobals = arguments?.get("globals")?.jsonPrimitive?.booleanOrNull ?: false
    val expandStack = arguments?.get("expand_stack")?.jsonPrimitive?.booleanOrNull ?: false
    return SnapshotParams(
        includeSource = includeParam == null || "source" in includeParam,
        includeVars = includeParam == null || "variables" in includeParam,
        includeStack = includeParam == null || "stacktrace" in includeParam,
        includeGlobals = includeGlobals,
        expandStack = expandStack,
    )
}

private fun JsonObjectBuilder.putSnapshotParams() {
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
}

fun Server.registerNavigationTools(project: Project) {
    val activityLog = McpActivityLog.getInstance(project)
    val sourceService = SourceContextService.getInstance(project)
    val stackFrameService = StackFrameService.getInstance(project)
    val variableService = VariableService.getInstance(project)
    val sessionService = SessionService.getInstance(project)

    // --- debug_step ---
    addTool(
        name = "debug_step",
        description = "Step through code. Actions: over (next line), into (enter function), " +
                "out (finish function), continue (resume to next breakpoint). " +
                "Returns a debug_snapshot after pausing. Requires a paused session.",
        toolAnnotations = navigationAnnotations,
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("action") {
                    put("type", "string")
                    put("description", "The stepping action to perform.")
                    putJsonArray("enum") {
                        add("over")
                        add("into")
                        add("out")
                        add("continue")
                    }
                }
                putSnapshotParams()
            },
            required = listOf("action")
        )
    ) { request ->
        val action = request.arguments?.get("action")?.jsonPrimitive?.content
        activityLog.log("debug_step($action)")
        try {
            if (action == null) return@addTool err("Missing required parameter: action")

            val (session, error) = resolvePausedSession(project)
            if (error != null) return@addTool error

            val params = parseSnapshotParams(request.arguments)

            val result = when (action) {
                "over" -> stepAndWait(session!!) { session.stepOver(false) }
                "into" -> stepAndWait(session!!) { session.stepInto() }
                "out" -> stepAndWait(session!!) { session.stepOut() }
                "continue" -> stepAndWait(session!!) { session.resume() }
                else -> return@addTool err("Unknown action: $action. Use: over, into, out, continue")
            }

            withSessionNotice(buildSnapshotFromResult(
                result, session!!, sessionService, sourceService, variableService, stackFrameService,
                params.includeSource, params.includeVars, params.includeStack, params.includeGlobals,
                params.expandStack
            ))
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            err(e.message ?: "Unknown error")
        }
    }

    // --- debug_run_to_line ---
    addTool(
        name = "debug_run_to_line",
        description = "Run to a specific line and return a debug_snapshot. " +
                "Requires a paused session.",
        toolAnnotations = navigationAnnotations,
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("location") {
                    put("type", "string")
                    put("description", "Target file:line. Example: src/index.php:15")
                }
                putSnapshotParams()
            },
            required = listOf("location")
        )
    ) { request ->
        activityLog.log("debug_run_to_line")
        try {
            val location = request.arguments?.get("location")?.jsonPrimitive?.content
                ?: return@addTool err("Missing required parameter: location")

            val (session, error) = resolvePausedSession(project)
            if (error != null) return@addTool error

            val parts = location.split(":")
            if (parts.size < 2) return@addTool err("Invalid location format. Expected file:line (e.g. src/index.php:15)")

            val filePath = parts.dropLast(1).joinToString(":")
            val line = parts.last().toIntOrNull()
                ?: return@addTool err("Invalid line number in location: ${parts.last()}")
            if (line < 1) return@addTool err("Line must be >= 1")

            val file = LocalFileSystem.getInstance().findFileByPath(filePath)
                ?: run {
                    val basePath = project.basePath ?: return@addTool err("File not found: $filePath")
                    LocalFileSystem.getInstance().findFileByPath("$basePath/$filePath")
                }
                ?: return@addTool err("File not found: $filePath")

            val position = XDebuggerUtil.getInstance().createPosition(file, line - 1)
                ?: return@addTool err("Could not create source position for $location")

            val params = parseSnapshotParams(request.arguments)

            val result = stepAndWait(session!!) { session.runToPosition(position, false) }

            val snapshot = buildSnapshotFromResult(
                result, session, sessionService, sourceService, variableService, stackFrameService,
                params.includeSource, params.includeVars, params.includeStack, params.includeGlobals,
                params.expandStack
            )

            // Detect if we stopped before the requested line (e.g. intermediate breakpoint)
            val currentPos = session.currentPosition
            val stoppedEarly = currentPos != null && (
                currentPos.file.path != file.path || (currentPos.line + 1) != line
            )
            if (stoppedEarly && snapshot.isError != true) {
                val first = snapshot.content.firstOrNull()
                if (first is TextContent) {
                    val notice = "Did not reach $location — stopped at an intermediate breakpoint. Use breakpoint_list to see all active breakpoints, or call debug_run_to_line with the same target again to continue."
                    withSessionNotice(CallToolResult(
                        content = listOf(TextContent("$notice\n\n${first.text}")) + snapshot.content.drop(1),
                        isError = snapshot.isError
                    ))
                } else {
                    withSessionNotice(snapshot)
                }
            } else {
                withSessionNotice(snapshot)
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            err(e.message ?: "Unknown error")
        }
    }
}
