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

fun Server.registerBreakpointTools(project: Project) {
    val service = BreakpointService.getInstance(project)
    val activityLog = McpActivityLog.getInstance(project)

    addTool(
        name = "breakpoint_list",
        description = "List all breakpoints. Optionally filter by file path substring.",
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
            val breakpoints = service.listBreakpoints(fileFilter)
            val json = BreakpointService.json.encodeToString(breakpoints)
            CallToolResult(content = listOf(TextContent(json)))
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
        }
    }

    addTool(
        name = "breakpoint_add",
        description = "Add a line breakpoint at the specified file and line. Optionally set a condition, log expression, or disable suspension.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("file") {
                    put("type", "string")
                    put("description", "File path (project-relative or absolute), e.g. \"src/index.php\"")
                }
                putJsonObject("line") {
                    put("type", "integer")
                    put("description", "Line number (1-based)")
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
            required = listOf("file", "line")
        )
    ) { request ->
        val file = request.arguments?.get("file")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("Error: 'file' is required")), isError = true)
        val line = request.arguments?.get("line")?.jsonPrimitive?.content?.toIntOrNull()
            ?: return@addTool CallToolResult(content = listOf(TextContent("Error: 'line' is required and must be an integer")), isError = true)
        val condition = request.arguments?.get("condition")?.jsonPrimitive?.content
        val logExpression = request.arguments?.get("log_expression")?.jsonPrimitive?.content
        val suspend = request.arguments?.get("suspend")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true

        activityLog.log("breakpoint_add $file:$line")
        try {
            val bp = service.addBreakpoint(file, line, condition, logExpression, suspend)
            val json = BreakpointService.json.encodeToString(bp)
            CallToolResult(content = listOf(TextContent(json)))
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
        }
    }

    addTool(
        name = "breakpoint_update",
        description = "Update an existing breakpoint's properties. Only provided fields are changed.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("id") {
                    put("type", "string")
                    put("description", "Breakpoint ID (numeric) or file:line reference, e.g. \"src/index.php:5\"")
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
            ?: return@addTool CallToolResult(content = listOf(TextContent("Error: 'id' is required")), isError = true)
        val enabled = request.arguments?.get("enabled")?.jsonPrimitive?.content?.toBooleanStrictOrNull()
        val condition = request.arguments?.get("condition")?.jsonPrimitive?.content
        val logExpression = request.arguments?.get("log_expression")?.jsonPrimitive?.content
        val suspend = request.arguments?.get("suspend")?.jsonPrimitive?.content?.toBooleanStrictOrNull()

        activityLog.log("breakpoint_update $id")
        try {
            val bp = service.updateBreakpoint(id, enabled, condition, logExpression, suspend)
            val json = BreakpointService.json.encodeToString(bp)
            CallToolResult(content = listOf(TextContent(json)))
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
        }
    }

    addTool(
        name = "breakpoint_remove",
        description = "Remove breakpoints. Pass one or more IDs (comma-separated) to remove specific breakpoints, or omit 'id' to remove ALL breakpoints.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("id") {
                    put("type", "string")
                    put("description", "Breakpoint ID(s) or file:line references to remove. Comma-separated for multiple, e.g. \"src/index.php:5, src/index.php:12\". Omit to remove all.")
                }
            },
            required = emptyList()
        )
    ) { request ->
        val idParam = request.arguments?.get("id")?.jsonPrimitive?.content
        val ids = idParam?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }

        activityLog.log(if (ids.isNullOrEmpty()) "breakpoint_remove (all)" else "breakpoint_remove ${ids.joinToString()}")
        try {
            val removed = service.removeBreakpoints(ids)
            val remaining = service.listBreakpoints()
            val response = buildJsonObject {
                put("removed", removed)
                put("remaining", remaining.size)
            }
            CallToolResult(content = listOf(TextContent(response.toString())))
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
        }
    }
}
