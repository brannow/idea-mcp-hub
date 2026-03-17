package com.github.brannow.phpstormmcp.tools

import com.github.brannow.phpstormmcp.statusbar.McpActivityLog
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Get the current debug pause position as project-relative file + 1-based line.
 * Returns null if no session is paused.
 */
internal fun getActiveDebugLocation(project: Project): Pair<String, Int>? {
    val session = XDebuggerManager.getInstance(project).currentSession ?: return null
    if (!session.isSuspended) return null
    val position = session.currentPosition ?: return null
    val basePath = project.basePath
    val file = if (basePath != null && position.file.path.startsWith(basePath)) {
        position.file.path.removePrefix(basePath).removePrefix("/")
    } else {
        position.file.path
    }
    return file to (position.line + 1)
}

internal fun parseLocation(location: String): Pair<String, Int>? {
    val colonIndex = location.lastIndexOf(':')
    if (colonIndex <= 0) return null
    val file = location.substring(0, colonIndex)
    val line = location.substring(colonIndex + 1).toIntOrNull()?.takeIf { it >= 1 } ?: return null
    return file to line
}

/**
 * Not-found response: show the agent what exists so it can self-correct.
 */
internal fun notFoundResponse(query: String, service: BreakpointService, activeLocation: Pair<String, Int>? = null): CallToolResult {
    val all = service.listBreakpoints()
    if (all.isEmpty()) return err("Breakpoint '$query' not found, no breakpoints in project")
    return err("Breakpoint '$query' not found, current breakpoints:\n\n${formatBreakpointIndex(all, activeLocation)}")
}

/**
 * Ambiguous response: list all at that location + guidance.
 */
internal fun ambiguousResponse(query: String, breakpoints: List<BreakpointInfo>, activeLocation: Pair<String, Int>? = null): CallToolResult {
    val location = if (breakpoints.isNotEmpty()) "${breakpoints[0].file}:${breakpoints[0].line}" else query
    return err("${formatBreakpointGroup(location, breakpoints, activeLocation)}\n\nChoose a breakpoint via #ID or remove other breakpoints first.")
}

// --- Extracted handler logic ---

internal fun handleBreakpointList(service: BreakpointService, fileFilter: String?, activeLocation: Pair<String, Int>? = null): CallToolResult {
    val breakpoints = service.listBreakpoints(fileFilter)
    return when {
        breakpoints.isEmpty() && fileFilter != null && !service.fileExists(fileFilter) ->
            ok("File '$fileFilter' not found")
        breakpoints.isEmpty() && fileFilter != null ->
            ok("No breakpoints in $fileFilter")
        breakpoints.isEmpty() ->
            ok("No breakpoints in project")
        else ->
            ok(formatBreakpointList(breakpoints, activeLocation))
    }
}

internal fun handleBreakpointAdd(
    service: BreakpointService,
    location: String?,
    condition: String?,
    logExpression: String?,
    suspend: Boolean,
    activeLocation: Pair<String, Int>? = null
): CallToolResult {
    if (location == null) return err("'location' is required")
    val (file, line) = parseLocation(location)
        ?: return err("Invalid location format. Expected file:line, e.g. \"src/index.php:15\"")

    val result = service.addBreakpoint(file, line, condition, logExpression, suspend)
    val text = StringBuilder(formatBreakpoint(result.breakpoint, activeLocation))

    if (result.existingBreakpoints.isNotEmpty()) {
        val loc = "${result.breakpoint.file}:${result.breakpoint.line}"
        val allOnLine = result.existingBreakpoints + result.breakpoint
        text.append("\n\n$loc (multi-breakpoint-line)")
        for (bp in allOnLine) {
            val extra = if (bp.id == result.breakpoint.id) "new" else null
            text.append("\n - #${bp.id}${formatAddAnnotations(bp, extra, activeLocation)}")
        }
    }

    return ok(text.toString())
}

internal fun handleBreakpointUpdate(
    service: BreakpointService,
    id: String?,
    enabled: Boolean?,
    condition: String?,
    logExpression: String?,
    suspend: Boolean?,
    activeLocation: Pair<String, Int>? = null
): CallToolResult {
    if (id == null) return err("'id' is required")
    val hasChanges = enabled != null || condition != null || logExpression != null || suspend != null

    return try {
        if (!hasChanges) {
            // Validate the ID exists before complaining about missing changes
            service.updateBreakpoint(id)
            return err("No changes specified. Use enabled, condition, log_expression, or suspend to update.")
        }
        val bp = service.updateBreakpoint(id, enabled, condition, logExpression, suspend)
        ok(formatBreakpoint(bp, activeLocation))
    } catch (e: AmbiguousBreakpointException) {
        ambiguousResponse(id, e.breakpoints, activeLocation)
    } catch (e: BreakpointNotFoundException) {
        notFoundResponse(id, service, activeLocation)
    }
}

internal fun handleBreakpointRemove(
    service: BreakpointService,
    ids: List<String>?,
    all: Boolean,
    activeLocation: Pair<String, Int>? = null
): CallToolResult {
    if (!all && ids.isNullOrEmpty()) {
        return err("Specify breakpoint(s) to remove or use all=true to remove all breakpoints")
    }

    val result = service.removeBreakpoints(if (all) null else ids)

    if (result.removed.isEmpty() && result.notFound.isEmpty()) {
        return ok("No breakpoints in project")
    }

    val text = StringBuilder()

    if (result.removed.isNotEmpty()) {
        text.append(formatBreakpointList(result.removed, activeLocation))

        val remaining = service.listBreakpoints()
        if (remaining.isNotEmpty()) {
            text.append("\n\n${remaining.size} breakpoint(s) remaining:\n${formatBreakpointIndex(remaining, activeLocation)}")
        }
    }

    if (result.notFound.isNotEmpty()) {
        if (text.isNotEmpty()) text.append("\n\n")
        val notFoundStr = result.notFound.joinToString(", ") { "'$it'" }

        val allBps = service.listBreakpoints()
        if (allBps.isEmpty() && result.removed.isNotEmpty()) {
            text.append("Breakpoint $notFoundStr not found")
        } else if (allBps.isEmpty()) {
            text.append("Breakpoint $notFoundStr not found, no breakpoints in project")
        } else {
            text.append("Breakpoint $notFoundStr not found, current breakpoints:\n\n${formatBreakpointIndex(allBps, activeLocation)}")
        }
    }

    // err() when nothing was removed (all IDs not found), ok() otherwise (including partial success)
    val isErr = result.removed.isEmpty() && result.notFound.isNotEmpty()
    return if (isErr) err(text.toString()) else ok(text.toString())
}

// --- MCP Tool Registration ---

fun Server.registerBreakpointTools(project: Project) {
    val service = BreakpointService.getInstance(project)
    val activityLog = McpActivityLog.getInstance(project)

    // --- breakpoint_list ---
    addTool(
        name = "breakpoint_list",
        description = "List all breakpoints. Optionally filter by file path substring.",
        toolAnnotations = ToolAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = false,
            openWorldHint = false,
        ),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("file") {
                    put("type", "string")
                    put("description", "Filter breakpoints by file path (substring match)")
                }
            },
            required = emptyList()
        )
    ) { request ->
        val fileFilter = request.arguments?.get("file")?.jsonPrimitive?.content
        activityLog.log("breakpoint_list" + if (fileFilter != null) " (file: $fileFilter)" else "")
        try {
            handleBreakpointList(service, fileFilter, getActiveDebugLocation(project))
        } catch (e: Exception) {
            err(e.message ?: "Unknown error")
        }
    }

    // --- breakpoint_add ---
    addTool(
        name = "breakpoint_add",
        description = "Add a line breakpoint. Optionally set a condition, log expression, or disable suspension.",
        toolAnnotations = ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = false,
            openWorldHint = false,
        ),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("location") {
                    put("type", "string")
                    put("description", "File path and line, e.g. \"src/index.php:15\"")
                }
                putJsonObject("condition") {
                    put("type", "string")
                    put("description", "PHP expression that must be true for the breakpoint to trigger, e.g. \"\$count > 10\"")
                }
                putJsonObject("log_expression") {
                    put("type", "string")
                    put("description", "PHP expression to evaluate and log when the breakpoint is hit, e.g. \"\$request->getUri()\"")
                }
                putJsonObject("suspend") {
                    put("type", "boolean")
                    put("description", "Whether to pause execution when hit (default: true). Set to false for logging-only breakpoints.")
                }
            },
            required = listOf("location")
        )
    ) { request ->
        val location = request.arguments?.get("location")?.jsonPrimitive?.content
        val condition = request.arguments?.get("condition")?.jsonPrimitive?.content
        val logExpression = request.arguments?.get("log_expression")?.jsonPrimitive?.content
        val suspend = request.arguments?.get("suspend")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true

        activityLog.log("breakpoint_add $location")
        try {
            handleBreakpointAdd(service, location, condition, logExpression, suspend, getActiveDebugLocation(project))
        } catch (e: Exception) {
            err(e.message ?: "Unknown error")
        }
    }

    // --- breakpoint_update ---
    addTool(
        name = "breakpoint_update",
        description = "Update an existing breakpoint's properties. Only provided fields are changed.",
        toolAnnotations = ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = false,
            openWorldHint = false,
        ),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("id") {
                    put("type", "string")
                    put("description", "Breakpoint #ID or file:line reference, e.g. \"src/index.php:5\"")
                }
                putJsonObject("enabled") {
                    put("type", "boolean")
                    put("description", "Enable or disable the breakpoint")
                }
                putJsonObject("condition") {
                    put("type", "string")
                    put("description", "New condition expression. Empty string to remove.")
                }
                putJsonObject("log_expression") {
                    put("type", "string")
                    put("description", "New log expression. Empty string to remove.")
                }
                putJsonObject("suspend") {
                    put("type", "boolean")
                    put("description", "Whether to pause execution when hit")
                }
            },
            required = listOf("id")
        )
    ) { request ->
        val id = request.arguments?.get("id")?.jsonPrimitive?.content
        val enabled = request.arguments?.get("enabled")?.jsonPrimitive?.content?.toBooleanStrictOrNull()
        val condition = request.arguments?.get("condition")?.jsonPrimitive?.content
        val logExpression = request.arguments?.get("log_expression")?.jsonPrimitive?.content
        val suspend = request.arguments?.get("suspend")?.jsonPrimitive?.content?.toBooleanStrictOrNull()

        activityLog.log("breakpoint_update $id")
        try {
            handleBreakpointUpdate(service, id, enabled, condition, logExpression, suspend, getActiveDebugLocation(project))
        } catch (e: Exception) {
            err(e.message ?: "Unknown error")
        }
    }

    // --- breakpoint_remove ---
    addTool(
        name = "breakpoint_remove",
        description = "Remove breakpoints by #ID, file:line, or file path (removes all breakpoints in that file). Comma-separated for multiple. Use all=true to remove ALL breakpoints.",
        toolAnnotations = ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = true,
            idempotentHint = false,
            openWorldHint = false,
        ),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("id") {
                    put("type", "string")
                    put("description", "Breakpoint #ID(s), file:line, or file path to purge. Comma-separated for multiple.")
                }
                putJsonObject("all") {
                    put("type", "boolean")
                    put("description", "Set to true to remove ALL breakpoints in the project")
                }
            },
            required = emptyList()
        )
    ) { request ->
        val idParam = request.arguments?.get("id")?.jsonPrimitive?.content
        val ids = idParam?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val all = request.arguments?.get("all")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

        activityLog.log(if (all) "breakpoint_remove (all)" else "breakpoint_remove ${ids?.joinToString()}")
        try {
            val activeLocation = getActiveDebugLocation(project)
            handleBreakpointRemove(service, ids, all, activeLocation)
        } catch (e: AmbiguousBreakpointException) {
            ambiguousResponse(idParam ?: "", e.breakpoints, getActiveDebugLocation(project))
        } catch (e: Exception) {
            err(e.message ?: "Unknown error")
        }
    }
}
