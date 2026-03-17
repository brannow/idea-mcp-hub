package com.github.brannow.phpstormmcp.tools

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.Project
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.frame.*
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.Icon

data class VariableInfo(
    val name: String,
    val type: String?,
    val value: String,
    val hasChildren: Boolean
)

data class VariableNode(
    val name: String,
    val type: String?,
    val value: String,
    val hasChildren: Boolean,
    val children: List<VariableNode>? = null, // null = not expanded
    val circular: Boolean = false // true = cycle detected, expansion skipped
)

class VariablePathException(message: String) : IllegalArgumentException(message)

@Service(Service.Level.PROJECT)
class VariableService(private val project: Project) {

    internal interface Platform {
        fun computeChildren(container: XValueContainer): List<Pair<String, XValue>>
        fun computePresentation(value: XValue): VariablePresentation
    }

    internal data class VariablePresentation(
        val type: String?,
        val value: String,
        val hasChildren: Boolean
    )

    internal var platform: Platform = object : Platform {
        override fun computeChildren(container: XValueContainer): List<Pair<String, XValue>> {
            val future = CompletableFuture<List<Pair<String, XValue>>>()
            val collected = mutableListOf<Pair<String, XValue>>()

            ApplicationManager.getApplication().invokeLater {
                container.computeChildren(object : XCompositeNode {
                    override fun addChildren(children: XValueChildrenList, last: Boolean) {
                        for (i in 0 until children.size()) {
                            collected.add(children.getName(i) to children.getValue(i))
                        }
                        if (last) future.complete(collected)
                    }

                    override fun tooManyChildren(remaining: Int) {
                        future.complete(collected)
                    }

                    override fun tooManyChildren(remaining: Int, addNextChildren: Runnable) {
                        future.complete(collected)
                    }

                    override fun setAlreadySorted(alreadySorted: Boolean) {}

                    override fun setErrorMessage(errorMessage: String) {
                        future.completeExceptionally(RuntimeException(errorMessage))
                    }

                    override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {
                        future.completeExceptionally(RuntimeException(errorMessage))
                    }

                    override fun setMessage(
                        message: String,
                        icon: Icon?,
                        attributes: SimpleTextAttributes,
                        link: XDebuggerTreeNodeHyperlink?
                    ) {}

                    override fun isObsolete(): Boolean = false
                })
            }

            return future.get(5, TimeUnit.SECONDS)
        }

        override fun computePresentation(value: XValue): VariablePresentation {
            val future = CompletableFuture<VariablePresentation>()

            ApplicationManager.getApplication().invokeLater {
                value.computePresentation(object : XValueNode {
                    override fun setPresentation(
                        icon: Icon?,
                        type: String?,
                        value: String,
                        hasChildren: Boolean
                    ) {
                        future.complete(VariablePresentation(type, value, hasChildren))
                    }

                    override fun setPresentation(
                        icon: Icon?,
                        presentation: XValuePresentation,
                        hasChildren: Boolean
                    ) {
                        val collector = ValueTextCollector()
                        presentation.renderValue(collector)
                        future.complete(VariablePresentation(
                            presentation.type,
                            collector.text,
                            hasChildren
                        ))
                    }

                    override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {
                        // Ignored — we already have a value from setPresentation
                    }

                    override fun isObsolete(): Boolean = false
                }, XValuePlace.TREE)
            }

            return future.get(5, TimeUnit.SECONDS)
        }
    }

    /**
     * Extract top-level variables from a stack frame.
     */
    fun getVariables(frame: XStackFrame): List<VariableInfo> {
        val children = try {
            platform.computeChildren(frame)
        } catch (_: Exception) {
            return emptyList()
        }

        return children.mapNotNull { (name, xValue) ->
            val presentation = try {
                platform.computePresentation(xValue)
            } catch (_: Exception) {
                return@mapNotNull null
            }
            VariableInfo(
                name = name,
                type = presentation.type,
                value = presentation.value,
                hasChildren = presentation.hasChildren
            )
        }
    }

    /**
     * Expand all top-level variables to the requested depth.
     * Used when no path is specified — shows everything.
     */
    fun getAllVariableDetails(frame: XStackFrame, depth: Int = 1): List<VariableNode> {
        val children = try {
            platform.computeChildren(frame)
        } catch (_: Exception) {
            return emptyList()
        }

        return children.mapNotNull { (name, xValue) ->
            try {
                expandValue(name, xValue, depth, emptySet())
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Drill into a variable by dot-path.
     * Path format: "$engine", "$engine.pattern", "$items.0.name"
     * Returns a VariableNode tree expanded to the requested depth.
     */
    fun getVariableDetail(frame: XStackFrame, path: String, depth: Int = 1): VariableNode {
        val segments = parsePath(path)
        if (segments.isEmpty()) throw VariablePathException("Empty path")

        // Resolve the target XValue by walking the path
        val rootChildren = try {
            platform.computeChildren(frame)
        } catch (e: Exception) {
            throw VariablePathException("Could not read variables from frame")
        }

        var currentChildren = rootChildren
        var targetName = segments.first()
        var targetValue: XValue? = null

        for ((idx, segment) in segments.withIndex()) {
            // Match flexibly:
            // - "result" matches "result" and "$result"
            // - "parent" matches "*TypedPatternEngine\Nodes\AstNode*parent" (inherited property naming)
            val matches = currentChildren.filter {
                matchesSegment(it.first, segment)
            }
            if (matches.isEmpty()) {
                val available = currentChildren.map { it.first }
                val pathSoFar = if (idx == 0) "\$$segment" else segments.take(idx + 1).joinToString(".")
                throw VariablePathException(
                    "'$pathSoFar' not found, available: ${available.joinToString(", ")}"
                )
            }
            if (matches.size > 1) {
                val pathSoFar = if (idx == 0) "\$$segment" else segments.take(idx + 1).joinToString(".")
                val options = matches.joinToString(", ") { it.first }
                throw VariablePathException(
                    "'$pathSoFar' is ambiguous, matches: $options\n\nUse the full property name to be specific."
                )
            }
            val match = matches.first()

            targetName = segment
            targetValue = match.second

            // If there are more segments, drill deeper
            if (idx < segments.size - 1) {
                currentChildren = try {
                    platform.computeChildren(match.second)
                } catch (e: Exception) {
                    val pathSoFar = segments.take(idx + 1).joinToString(".")
                    throw VariablePathException("Could not expand '$pathSoFar'")
                }
            }
        }

        // Now expand the target to the requested depth
        return expandValue(targetName, targetValue!!, depth, emptySet())
    }

    /**
     * Expand an XValue into a VariableNode tree.
     * depth 0 = just the node itself (no children), depth 1 = immediate children, etc.
     *
     * Circular reference detection: tracks object types (FQCNs) along the ancestor chain.
     * When a child's type matches an ancestor's type, it's marked as circular instead of
     * expanded — this catches parent-child back-references (the most common cycle pattern).
     * Arrays are excluded from tracking since they legitimately nest.
     * False positives (legitimately nested same-type objects) are rare, and the agent can
     * always bypass detection by drilling with an explicit path.
     */
    private fun expandValue(name: String, xValue: XValue, depth: Int, ancestorTypes: Set<String>): VariableNode {
        val presentation = try {
            platform.computePresentation(xValue)
        } catch (_: Exception) {
            return VariableNode(name, null, "(error reading value)", false)
        }

        // Circular reference check: if this object's type already appears in our ancestor chain,
        // it's likely a back-reference (e.g., child.parent → same parent object).
        // Only check object types (hasChildren + non-array type).
        val currentType = presentation.type
        if (presentation.hasChildren && currentType != null && isObjectType(currentType) && currentType in ancestorTypes) {
            return VariableNode(
                name = name,
                type = currentType,
                value = presentation.value,
                hasChildren = true,
                children = null,
                circular = true
            )
        }

        // Add current type to ancestor set for child expansion (only for objects)
        val childAncestors = if (presentation.hasChildren && currentType != null && isObjectType(currentType)) {
            ancestorTypes + currentType
        } else {
            ancestorTypes
        }

        val children = if (depth > 0 && presentation.hasChildren) {
            try {
                val childPairs = platform.computeChildren(xValue)
                childPairs.map { (childName, childValue) ->
                    expandValue(childName, childValue, depth - 1, childAncestors)
                }
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        return VariableNode(
            name = name,
            type = presentation.type,
            value = presentation.value,
            hasChildren = presentation.hasChildren,
            children = children
        )
    }

    companion object {
        /**
         * Types that are NOT tracked for cycle detection.
         * Arrays nest legitimately; scalars never have children.
         */
        private val NON_OBJECT_TYPES = setOf("array", "int", "string", "float", "bool", "null")

        /**
         * Returns true if the type represents a PHP object (class name) rather than
         * a scalar or array. Object types are tracked for circular reference detection.
         */
        internal fun isObjectType(type: String): Boolean {
            return type.lowercase() !in NON_OBJECT_TYPES
        }

        fun getInstance(project: Project): VariableService =
            project.getService(VariableService::class.java)

        /**
         * Parse a dot-path into segments.
         * "$engine.pattern" → ["engine", "pattern"]
         * "$items.0.name" → ["items", "0", "name"]
         * "engine" → ["engine"]
         */
        fun parsePath(path: String): List<String> {
            val cleaned = path.trimStart('$').trim()
            if (cleaned.isEmpty()) return emptyList()
            return cleaned.split(".")
        }

        /**
         * Xdebug exposes inherited private/protected properties with the naming convention
         * `*FullyQualified\ClassName*propertyName`. This means a simple property like "parent"
         * is actually named `*TypedPatternEngine\Nodes\AstNode*parent` in the debugger.
         *
         * This matcher allows the agent to use just "parent" in paths while also accepting
         * the full `*Class*prop` form or the exact name.
         *
         * Match priority: exact match > $-prefixed > stripped suffix match
         */
        internal fun matchesSegment(childName: String, segment: String): Boolean {
            // Exact match
            if (childName == segment) return true
            // $-prefixed match (PHP variables)
            if (childName == "\$$segment") return true
            // Xdebug inherited property: *ClassName*propertyName → matches "propertyName"
            if (childName.startsWith("*") && childName.indexOf("*", startIndex = 1) > 0) {
                val stripped = childName.substringAfterLast("*")
                if (stripped == segment) return true
            }
            return false
        }
    }
}

/**
 * Minimal XValueTextRenderer that collects rendered text fragments.
 * Used to extract display text from XValuePresentation.
 */
private class ValueTextCollector : XValuePresentation.XValueTextRenderer {
    private val sb = StringBuilder()
    val text: String get() = sb.toString()

    override fun renderValue(value: String) {
        sb.append(value)
    }

    override fun renderStringValue(value: String) {
        sb.append("\"$value\"")
    }

    override fun renderNumericValue(value: String) {
        sb.append(value)
    }

    override fun renderKeywordValue(value: String) {
        sb.append(value)
    }

    override fun renderValue(value: String, key: TextAttributesKey) {
        sb.append(value)
    }

    override fun renderStringValue(value: String, additionalSpecialCharsToHighlight: String?, maxLength: Int) {
        val truncated = if (value.length > maxLength) value.substring(0, maxLength) + "..." else value
        sb.append("\"$truncated\"")
    }

    override fun renderComment(comment: String) {
        sb.append(comment)
    }

    override fun renderSpecialSymbol(symbol: String) {
        sb.append(symbol)
    }

    override fun renderError(error: String) {
        sb.append(error)
    }
}
