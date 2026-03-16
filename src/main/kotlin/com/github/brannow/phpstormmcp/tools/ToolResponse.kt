package com.github.brannow.phpstormmcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

/**
 * Shared response helpers for all MCP tools.
 *
 * Response pattern (all tools follow this):
 *
 *   1. Result       — the data, no narration. Single item = just the line.
 *                     Multiple items = header + lines.
 *   2. Context      — (optional) separated by blank line, extra info the
 *                     agent needs to stay oriented.
 *   3. Error        — what went wrong + current state + guidance on what
 *                     to do next. The agent should never need a follow-up
 *                     tool call just to orient itself after an error.
 */

fun ok(text: String) = CallToolResult(content = listOf(TextContent(text)))
fun err(text: String) = CallToolResult(content = listOf(TextContent(text)), isError = true)

// --- Breakpoint formatting ---

fun formatBreakpoint(bp: BreakpointInfo): String {
    val annotations = formatAnnotations(bp)
    val suffix = if (annotations.isNotEmpty()) " ($annotations)" else ""
    return "#${bp.id} ${bp.file}:${bp.line}$suffix"
}

/**
 * Smart list: single breakpoints stay flat, same-line breakpoints get grouped.
 */
fun formatBreakpointList(breakpoints: List<BreakpointInfo>): String {
    // Group by file:line, preserve insertion order
    val grouped = linkedMapOf<String, MutableList<BreakpointInfo>>()
    for (bp in breakpoints) {
        grouped.getOrPut("${bp.file}:${bp.line}") { mutableListOf() }.add(bp)
    }

    return grouped.entries.joinToString("\n") { (location, bps) ->
        if (bps.size == 1) {
            formatBreakpoint(bps.first())
        } else {
            formatBreakpointGroup(location, bps)
        }
    }
}

/**
 * Grouped sub-list: breakpoints as indented bullets with only #ID + annotations.
 * Use with a header line when multiple breakpoints share the same location.
 */
fun formatBreakpointGroupChildren(breakpoints: List<BreakpointInfo>): String {
    return breakpoints.joinToString("\n") { bp ->
        val annotations = formatAnnotations(bp)
        val suffix = if (annotations.isNotEmpty()) " ($annotations)" else ""
        " - #${bp.id}$suffix"
    }
}

/**
 * Full grouped view: location as header, breakpoints as indented bullets.
 */
fun formatBreakpointGroup(location: String, breakpoints: List<BreakpointInfo>): String {
    return "$location\n${formatBreakpointGroupChildren(breakpoints)}"
}

private fun formatAnnotations(bp: BreakpointInfo): String {
    val annotations = mutableListOf<String>()
    if (bp.method) annotations.add("method")
    if (bp.vendor) annotations.add("vendor")
    if (!bp.enabled) annotations.add("disabled")
    if (bp.condition != null) annotations.add("condition: ${bp.condition}")
    if (bp.logExpression != null) annotations.add("log: ${bp.logExpression}")
    if (!bp.suspend) annotations.add("no suspend")
    return annotations.joinToString(", ")
}

// --- Session formatting ---

fun formatSession(session: SessionInfo): String {
    val parts = mutableListOf<String>()
    // Only show status when it's non-default (running). Paused is the expected state.
    val statusTag = if (session.status == "running") " [running]" else ""
    parts.add("#${session.id} \"${session.name}\"$statusTag")

    if (session.currentFile != null && session.currentLine != null) {
        parts.add("at ${session.currentFile}:${session.currentLine}")
    }
    if (session.active) {
        parts.add("(active)")
    }

    return parts.joinToString(" ")
}

fun formatSessionList(sessions: List<SessionInfo>): String {
    return sessions.joinToString("\n") { formatSession(it) }
}
