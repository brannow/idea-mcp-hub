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

fun formatBreakpoint(
    bp: BreakpointInfo,
    activeLocation: Pair<String, Int>? = null,
    highlights: Map<String, String> = emptyMap()
): String {
    val annotations = formatAnnotations(bp, activeLocation, highlights)
    val suffix = if (annotations.isNotEmpty()) " ($annotations)" else ""
    return "#${bp.id} ${bp.file}:${bp.line}$suffix"
}

/**
 * Just IDs and locations — for context hints where the agent only needs
 * to know what exists so it can self-correct.
 * Same-line breakpoints get grouped with a (multi-breakpoint-line) hint.
 */
fun formatBreakpointIndex(breakpoints: List<BreakpointInfo>, activeLocation: Pair<String, Int>? = null): String {
    val grouped = linkedMapOf<String, MutableList<BreakpointInfo>>()
    for (bp in breakpoints) {
        grouped.getOrPut("${bp.file}:${bp.line}") { mutableListOf() }.add(bp)
    }
    return grouped.entries.joinToString("\n") { (location, bps) ->
        if (bps.size == 1) {
            val bp = bps.first()
            val active = if (isActiveBreakpoint(bp, activeLocation)) " (active)" else ""
            "#${bp.id} $location$active"
        } else {
            val isActive = activeLocation != null && bps.any { isActiveBreakpoint(it, activeLocation) }
            val header = if (isActive) "$location (active, multi-breakpoint-line)" else "$location (multi-breakpoint-line)"
            "$header\n${bps.joinToString("\n") { " - #${it.id}" }}"
        }
    }
}

/**
 * Smart list: single breakpoints stay flat, same-line breakpoints get grouped.
 */
fun formatBreakpointList(
    breakpoints: List<BreakpointInfo>,
    activeLocation: Pair<String, Int>? = null,
    highlights: Map<String, String> = emptyMap()
): String {
    // Group by file:line, preserve insertion order
    val grouped = linkedMapOf<String, MutableList<BreakpointInfo>>()
    for (bp in breakpoints) {
        grouped.getOrPut("${bp.file}:${bp.line}") { mutableListOf() }.add(bp)
    }

    return grouped.entries.joinToString("\n") { (location, bps) ->
        if (bps.size == 1) {
            formatBreakpoint(bps.first(), activeLocation, highlights)
        } else {
            formatBreakpointGroup(location, bps, activeLocation, highlights)
        }
    }
}

/**
 * Grouped sub-list: breakpoints as indented bullets with only #ID + annotations.
 * Use with a header line when multiple breakpoints share the same location.
 */
fun formatBreakpointGroupChildren(
    breakpoints: List<BreakpointInfo>,
    highlights: Map<String, String> = emptyMap()
): String {
    return breakpoints.joinToString("\n") { bp ->
        val annotations = formatAnnotations(bp, highlights = highlights)
        val suffix = if (annotations.isNotEmpty()) " ($annotations)" else ""
        " - #${bp.id}$suffix"
    }
}


/**
 * Full grouped view: location as header with hint, breakpoints as indented bullets.
 * Active is shown on the group header, not individual breakpoints (we can't know which one triggered).
 */
/**
 * Full grouped view: location as header with hint, breakpoints as indented bullets.
 * With exact breakpoint ID matching, the specific active breakpoint is marked in the group.
 */
fun formatBreakpointGroup(
    location: String,
    breakpoints: List<BreakpointInfo>,
    activeLocation: Pair<String, Int>? = null,
    highlights: Map<String, String> = emptyMap()
): String {
    val isActive = activeLocation != null && breakpoints.any { isActiveBreakpoint(it, activeLocation) }
    val header = if (isActive) "$location (active, multi-breakpoint-line)" else "$location (multi-breakpoint-line)"
    return "$header\n${formatBreakpointGroupChildren(breakpoints, highlights)}"
}

private fun isActiveBreakpoint(bp: BreakpointInfo, activeLocation: Pair<String, Int>?): Boolean {
    if (activeLocation == null) return false
    return bp.file == activeLocation.first && bp.line == activeLocation.second
}

private fun formatAnnotations(
    bp: BreakpointInfo,
    activeLocation: Pair<String, Int>? = null,
    highlights: Map<String, String> = emptyMap()
): String {
    val annotations = mutableListOf<String>()
    highlights[bp.id]?.let { annotations.add(it) }
    if (isActiveBreakpoint(bp, activeLocation)) annotations.add("active")
    if (bp.method) annotations.add("method")
    if (bp.vendor) annotations.add("vendor")
    if (!bp.enabled) annotations.add("disabled")
    if (bp.condition != null) annotations.add("condition: ${bp.condition}")
    if (bp.logExpression != null) annotations.add("log: ${bp.logExpression}")
    if (!bp.suspend) annotations.add("no suspend")
    return annotations.joinToString(", ")
}

// --- Exception breakpoint formatting ---

fun formatExceptionBreakpoint(
    bp: ExceptionBreakpointInfo,
    highlights: Map<String, String> = emptyMap()
): String {
    val annotations = formatExceptionAnnotations(bp, highlights)
    val suffix = if (annotations.isNotEmpty()) " ($annotations)" else ""
    return "#${bp.id} ${bp.exceptionClass}$suffix"
}

private fun formatExceptionAnnotations(
    bp: ExceptionBreakpointInfo,
    highlights: Map<String, String> = emptyMap()
): String {
    val annotations = mutableListOf<String>()
    highlights[bp.id]?.let { annotations.add(it) }
    if (!bp.enabled) annotations.add("disabled")
    if (bp.condition != null) annotations.add("condition: ${bp.condition}")
    if (bp.logExpression != null) annotations.add("log: ${bp.logExpression}")
    if (!bp.suspend) annotations.add("no suspend")
    return annotations.joinToString(", ")
}

// --- Combined breakpoint formatting (line + exception) ---

/**
 * Dispatch formatter for any breakpoint type — used by update/remove handlers.
 */
fun formatAnyBreakpoint(
    bp: AnyBreakpointInfo,
    activeLocation: Pair<String, Int>? = null,
    highlights: Map<String, String> = emptyMap()
): String {
    return when (bp) {
        is BreakpointInfo -> formatBreakpoint(bp, activeLocation, highlights)
        is ExceptionBreakpointInfo -> formatExceptionBreakpoint(bp, highlights)
    }
}

/**
 * Full detail list with both types. Exception breakpoints in a separate section.
 */
fun formatCombinedBreakpointList(
    lineBreakpoints: List<BreakpointInfo>,
    exceptionBreakpoints: List<ExceptionBreakpointInfo>,
    activeLocation: Pair<String, Int>? = null,
    highlights: Map<String, String> = emptyMap()
): String {
    val sections = mutableListOf<String>()
    if (lineBreakpoints.isNotEmpty()) {
        sections.add(formatBreakpointList(lineBreakpoints, activeLocation, highlights))
    }
    if (exceptionBreakpoints.isNotEmpty()) {
        val list = exceptionBreakpoints.joinToString("\n") { formatExceptionBreakpoint(it, highlights) }
        if (lineBreakpoints.isNotEmpty()) {
            sections.add("Exception breakpoints:\n$list")
        } else {
            sections.add(list)
        }
    }
    return sections.joinToString("\n\n")
}

/**
 * Full detail list for mixed AnyBreakpointInfo — splits by type, then delegates.
 */
fun formatAnyBreakpointList(
    breakpoints: List<AnyBreakpointInfo>,
    activeLocation: Pair<String, Int>? = null,
    highlights: Map<String, String> = emptyMap()
): String {
    val line = breakpoints.filterIsInstance<BreakpointInfo>()
    val exception = breakpoints.filterIsInstance<ExceptionBreakpointInfo>()
    return formatCombinedBreakpointList(line, exception, activeLocation, highlights)
}

/**
 * Compact index with both types — for error context hints.
 */
fun formatCombinedBreakpointIndex(
    lineBreakpoints: List<BreakpointInfo>,
    exceptionBreakpoints: List<ExceptionBreakpointInfo>,
    activeLocation: Pair<String, Int>? = null
): String {
    val sections = mutableListOf<String>()
    if (lineBreakpoints.isNotEmpty()) {
        sections.add(formatBreakpointIndex(lineBreakpoints, activeLocation))
    }
    if (exceptionBreakpoints.isNotEmpty()) {
        val list = exceptionBreakpoints.joinToString("\n") { "#${it.id} ${it.exceptionClass}" }
        if (lineBreakpoints.isNotEmpty()) {
            sections.add("Exception breakpoints:\n$list")
        } else {
            sections.add(list)
        }
    }
    return sections.joinToString("\n\n")
}

// --- Session formatting ---

fun formatSession(
    session: SessionInfo,
    breakpoints: List<BreakpointInfo> = emptyList()
): String {
    val parts = mutableListOf<String>()
    // Only show status when it's non-default. Paused is the expected state.
    val statusTag = when (session.status) {
        "running" -> " [running]"
        "stopped" -> " [stopped]"
        else -> ""
    }
    parts.add("#${session.id} \"${session.name}\"$statusTag")

    if (session.currentFile != null && session.currentLine != null) {
        val matchingBp = breakpoints.firstOrNull {
            it.file == session.currentFile && it.line == session.currentLine
        }
        val bpHint = if (matchingBp != null) " (breakpoint #${matchingBp.id})" else ""
        parts.add("at ${session.currentFile}:${session.currentLine}$bpHint")
    }
    if (session.active) {
        parts.add("(active)")
    }

    return parts.joinToString(" ")
}

fun formatSessionList(
    sessions: List<SessionInfo>,
    breakpoints: List<BreakpointInfo> = emptyList()
): String {
    return sessions.joinToString("\n") { formatSession(it, breakpoints) }
}

// --- Snapshot formatting ---

fun formatSnapshot(
    session: SessionInfo?,
    source: SourceContext?,
    variables: List<VariableInfo>?,
    frames: List<FrameInfo>?,
    activeDepth: Int = 0,
    collapseLibrary: Boolean = true
): String {
    val sections = mutableListOf<String>()

    // Session — always included
    if (session != null) {
        sections.add(formatSession(session))
    }

    // Source context
    if (source != null) {
        sections.add(formatSourceContext(source))
    }

    // Variables
    if (variables != null) {
        sections.add(formatVariables(variables))
    }

    // Stack trace
    if (frames != null) {
        sections.add(formatStackTrace(frames, activeDepth, collapseLibrary))
    }

    return sections.joinToString("\n\n")
}

// --- Source context formatting ---

fun formatSourceHeader(ctx: SourceContext): String {
    return buildString {
        if (ctx.className != null) {
            append(ctx.className)
            if (ctx.methodName != null) append("::${ctx.methodName}()")
        } else if (ctx.methodName != null) {
            append("${ctx.methodName}()")
        }
        if (isNotEmpty()) append(" — ")
        append("${ctx.file}:${ctx.line}")
        if (ctx.isLibrary) append(" (library)")
    }
}

fun formatSourceContext(ctx: SourceContext): String {
    return "${formatSourceHeader(ctx)}\n\n${ctx.formattedSource}"
}

// --- Stack frame formatting ---

// --- Variable formatting ---

private val PHP_SUPERGLOBALS = setOf(
    "\$_ENV", "\$_SERVER", "\$_GET", "\$_POST", "\$_SESSION",
    "\$_COOKIE", "\$_FILES", "\$_REQUEST", "\$GLOBALS"
)

fun filterGlobals(variables: List<VariableInfo>, includeGlobals: Boolean): List<VariableInfo> {
    if (includeGlobals) return variables
    return variables.filter { v ->
        val name = if (v.name.startsWith("$")) v.name else "$${v.name}"
        name !in PHP_SUPERGLOBALS
    }
}

fun formatVariables(variables: List<VariableInfo>): String {
    if (variables.isEmpty()) return "(no variables)"
    return variables.joinToString("\n") { formatVariable(it) }
}

fun formatVariable(v: VariableInfo): String {
    val name = if (v.name.startsWith("$")) v.name else "$${v.name}"
    val display = when {
        v.value.isNotEmpty() && v.type != null && !v.hasChildren -> "{${v.type}} ${v.value}"
        v.value.isNotEmpty() -> v.value
        v.type != null -> "{${v.type}}"
        else -> "(unknown)"
    }
    return "$name = $display"
}

// --- Variable detail formatting ---

fun formatVariableDetail(node: VariableNode, path: String): String {
    val display = variableDisplayValue(node)
    val circularTag = if (node.circular) " (circular reference)" else ""
    val header = "$path = $display$circularTag"
    val children = node.children
    if (children.isNullOrEmpty()) return header
    return "$header\n${formatVariableChildren(children, indent = 1)}"
}

fun formatVariableDetailList(nodes: List<VariableNode>): String {
    if (nodes.isEmpty()) return "(no variables)"
    return nodes.joinToString("\n") { node ->
        val name = if (node.name.startsWith("$")) node.name else "$${node.name}"
        val display = variableDisplayValue(node)
        val circularTag = if (node.circular) " (circular reference)" else ""
        val line = "$name = $display$circularTag"
        val children = node.children
        if (children.isNullOrEmpty()) {
            line
        } else {
            "$line\n${formatVariableChildren(children, indent = 1)}"
        }
    }
}

fun filterGlobalNodes(nodes: List<VariableNode>, includeGlobals: Boolean): List<VariableNode> {
    if (includeGlobals) return nodes
    return nodes.filter { v ->
        val name = if (v.name.startsWith("$")) v.name else "$${v.name}"
        name !in PHP_SUPERGLOBALS
    }
}

internal fun formatVariableChildren(children: List<VariableNode>, indent: Int): String {
    val prefix = "  ".repeat(indent)
    return children.joinToString("\n") { child ->
        val display = variableDisplayValue(child)
        val circularTag = if (child.circular) " (circular reference)" else ""
        val line = "$prefix${child.name} = $display$circularTag"
        val nested = child.children
        if (nested.isNullOrEmpty()) {
            line
        } else {
            "$line\n${formatVariableChildren(nested, indent + 1)}"
        }
    }
}

internal fun variableDisplayValue(node: VariableNode): String {
    return when {
        node.value.isNotEmpty() && node.type != null -> "{${node.type}} ${node.value}"
        node.value.isNotEmpty() -> node.value
        node.type != null -> "{${node.type}}"
        else -> "(unknown)"
    }
}

// --- Evaluation formatting ---

fun formatEvaluationResult(expression: String, node: VariableNode): String {
    val display = variableDisplayValue(node)
    val circularTag = if (node.circular) " (circular reference)" else ""
    val header = "$expression = $display$circularTag"
    val children = node.children
    if (children.isNullOrEmpty()) return header
    return "$header\n${formatVariableChildren(children, indent = 1)}"
}

// --- Stack frame formatting ---

fun formatStackTrace(
    frames: List<FrameInfo>,
    activeDepth: Int = 0,
    collapseLibrary: Boolean = true
): String {
    if (frames.isEmpty()) return "(no stack frames)"

    if (!collapseLibrary) {
        return formatStackTraceFlat(frames, activeDepth)
    }

    // Group consecutive library frames and collapse runs of 3+
    val lines = mutableListOf<String>()
    var i = 0
    while (i < frames.size) {
        val frame = frames[i]
        if (frame.isLibrary && frame.depth != activeDepth) {
            // Collect consecutive library frames (excluding active frame)
            val runStart = i
            while (i < frames.size && frames[i].isLibrary && frames[i].depth != activeDepth) {
                i++
            }
            val run = frames.subList(runStart, i)
            if (run.size >= 3) {
                val firstDepth = run.first().depth
                val lastDepth = run.last().depth
                lines.add(" #$firstDepth-#$lastDepth [${run.size} library frames]")
            } else {
                for (f in run) {
                    lines.add(formatFrame(f, activeDepth))
                }
            }
        } else {
            lines.add(formatFrame(frame, activeDepth))
            i++
        }
    }
    return lines.joinToString("\n")
}

private fun formatStackTraceFlat(frames: List<FrameInfo>, activeDepth: Int): String {
    return frames.joinToString("\n") { formatFrame(it, activeDepth) }
}

private fun formatFrame(frame: FrameInfo, activeDepth: Int): String {
    val location = when {
        frame.file != null && frame.line != null -> "${frame.file}:${frame.line}"
        frame.file != null -> frame.file
        else -> "(unknown)"
    }
    val name = frame.name ?: "(unknown)"
    val lib = if (frame.isLibrary) " (library)" else ""
    val prefix = if (frame.depth == activeDepth) "→" else " "
    return "$prefix#${frame.depth} $name at $location$lib"
}
