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

internal fun parseLocation(location: String): Pair<String, Int>? {
    val colonIndex = location.lastIndexOf(':')
    if (colonIndex <= 0) return null
    val file = location.substring(0, colonIndex)
    val line = location.substring(colonIndex + 1).toIntOrNull() ?: return null
    return file to line
}

/**
 * Not-found response: show the agent what exists so it can self-correct.
 */
internal fun notFoundResponse(query: String, service: BreakpointService): CallToolResult {
    val all = service.listBreakpoints()
    if (all.isEmpty()) return err("Breakpoint '$query' not found, no breakpoints in project")

    // Try narrowing to breakpoints matching the query
    val filtered = service.listBreakpoints(query)
    return if (filtered.isNotEmpty()) {
        err("Breakpoint '$query' not found, current breakpoints:\n\n${formatBreakpointList(filtered)}")
    } else {
        err("Breakpoint '$query' not found, current breakpoints:\n\n${formatBreakpointList(all)}")
    }
}

/**
 * Ambiguous response: list all at that location + guidance.
 */
internal fun ambiguousResponse(query: String, breakpoints: List<BreakpointInfo>): CallToolResult {
    val location = if (breakpoints.isNotEmpty()) "${breakpoints[0].file}:${breakpoints[0].line}" else query
    return err("${formatBreakpointGroup(location, breakpoints)}\n\nChoose a breakpoint via #ID or remove other breakpoints first.")
}

// --- Extracted handler logic ---

internal fun handleBreakpointList(service: BreakpointService, fileFilter: String?): CallToolResult {
    val breakpoints = service.listBreakpoints(fileFilter)
    return when {
        breakpoints.isEmpty() && fileFilter != null && !service.fileExists(fileFilter) ->
            ok("File '$fileFilter' not found")
        breakpoints.isEmpty() && fileFilter != null ->
            ok("No breakpoints in $fileFilter")
        breakpoints.isEmpty() ->
            ok("No breakpoints in project")
        else ->
            ok(formatBreakpointList(breakpoints))
    }
}

internal fun handleBreakpointAdd(
    service: BreakpointService,
    location: String?,
    condition: String?,
    logExpression: String?,
    suspend: Boolean
): CallToolResult {
    if (location == null) return err("'location' is required")
    val (file, line) = parseLocation(location)
        ?: return err("Invalid location format. Expected file:line, e.g. \"src/index.php:15\"")

    val result = service.addBreakpoint(file, line, condition, logExpression, suspend)
    val text = StringBuilder(formatBreakpoint(result.breakpoint))

    if (result.existingBreakpoints.isNotEmpty()) {
        val loc = "${result.breakpoint.file}:${result.breakpoint.line}"
        text.append("\n\n$loc also has other breakpoints:\n")
        text.append(formatBreakpointGroupChildren(result.existingBreakpoints))
    }

    return ok(text.toString())
}

internal fun handleBreakpointUpdate(
    service: BreakpointService,
    id: String?,
    enabled: Boolean?,
    condition: String?,
    logExpression: String?,
    suspend: Boolean?
): CallToolResult {
    if (id == null) return err("'id' is required")

    return try {
        val bp = service.updateBreakpoint(id, enabled, condition, logExpression, suspend)
        ok(formatBreakpoint(bp))
    } catch (e: AmbiguousBreakpointException) {
        ambiguousResponse(id, e.breakpoints)
    } catch (e: BreakpointNotFoundException) {
        notFoundResponse(id, service)
    }
}

internal fun handleBreakpointRemove(
    service: BreakpointService,
    ids: List<String>?,
    all: Boolean
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
        text.append(formatBreakpointList(result.removed))

        val remaining = service.listBreakpoints()
        if (remaining.isNotEmpty()) {
            text.append("\n\n${remaining.size} breakpoint(s) remaining:\n${formatBreakpointList(remaining)}")
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
            // Try narrowing to breakpoints matching the not-found queries
            val filtered = result.notFound.flatMap { q -> service.listBreakpoints(q) }.distinct()
            if (filtered.isNotEmpty()) {
                text.append("Breakpoint $notFoundStr not found, current breakpoints:\n\n${formatBreakpointList(filtered)}")
            } else {
                text.append("Breakpoint $notFoundStr not found, current breakpoints:\n\n${formatBreakpointList(allBps)}")
            }
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
            handleBreakpointList(service, fileFilter)
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
            handleBreakpointAdd(service, location, condition, logExpression, suspend)
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
            handleBreakpointUpdate(service, id, enabled, condition, logExpression, suspend)
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
            handleBreakpointRemove(service, ids, all)
        } catch (e: AmbiguousBreakpointException) {
            ambiguousResponse(idParam ?: "", e.breakpoints)
        } catch (e: Exception) {
            err(e.message ?: "Unknown error")
        }
    }
}
