# MCP Tool Design for PhpStorm Debugger Plugin

## Design Principle

The tools should provide the same comfort and QoL to the Agent as PHPStorm to the user,
They should reflect the same intuitive and refined nature of what is really important without dumping raw information at the agent.

A tool is not a thin wrapper for an api call it a refined tool. like an Application for an Human. A human don't know what api in the background is called or how that api works, he use the tool and the tool handle the processing.

A good tool can be found by a simple question: "Did the Agent need knowledge about the API/System internally in order use that tool?" If the answer is yes the tool is might be not good.

The agent should not need internal API/system knowledge to use a tool. Think of tools as applications for the agent, the same way PhpStorm is an application for the human.

**Key decision**: Every tool that changes or inspects debug state returns a **Debug Snapshot** — a rich, standardized response that gives the agent the same context a human gets by looking at the debug panel.

---

## Tool Output Design

Tools communicate with the agent in **natural language**, not JSON. The goal: every response leaves the agent fully oriented — no follow-up "what just happened?" calls needed.

### Why not JSON?

The agent isn't a REST client. It reads context like a human reads a debug panel. Natural language output means the agent can immediately reason about the result without parsing structure. It also lets us embed guidance naturally (e.g., "use #ID to target a specific one") that would be awkward in JSON.

### Shared response pattern

Every tool response follows the same three-part structure. The structure is predictable, the language is natural.

```
┌─ Result       Just the data. Single item = one line. Multiple = header + lines.
│               No narration ("Breakpoint added:" is noise — the agent knows what it called).
│
├─ Context      (optional) Blank line separator. Extra info the agent needs.
│               Only present when there's something non-obvious to communicate.
│
└─ Error        What went wrong + current state + what to do next.
                The agent should never need a follow-up call to orient itself.
```

### Core principles

**1. Self-contained responses** — Every response includes enough context for the agent to decide its next action without extra tool calls. A bad tool response causes 3-5 follow-up calls (list, inspect, retry). A good one causes zero.

- Error: not found → show all breakpoints in the project (full list, not filtered), so the agent can self-correct
- Error: ambiguous → list the options with their IDs and tell the agent how to resolve it
- Error: empty state → say clearly "No breakpoints in project", not a generic error
- Success with edge case → proactively include the context the agent will need next

**2. Two output modes for breakpoint lists** — Full detail for direct list calls, compact index for context hints:

| Mode | When | Format |
|------|------|--------|
| Full | `breakpoint_list`, removed items | `#ID file:line (annotations)` with same-line grouping |
| Index | Not-found hints, remaining after remove | `#ID file:line` — just IDs and locations, no annotations |

The agent doesn't need to know a breakpoint is disabled or conditional when it's just trying to find the right ID after a not-found error.

**3. Consistent notation across all tools** — Each domain has one canonical format:

| Domain      | Format                                           | Example                                                    |
|-------------|--------------------------------------------------|------------------------------------------------------------|
| Breakpoint  | `#ID file:line (annotations)`                    | `#3 src/WorldClass.php:13 (disabled, condition: $foo === '')` |
| Session     | `#ID "name" [status]? at file:line (active)`       | `#12345 "index.php" at src/index.php:15 (active)` |
| Variable    | `$name = {type} value` or `$name = {ClassName}`  | `$count = {int} 42`, `$request = {ServerRequest}` |
| Var detail  | indented tree with `(circular reference)` marker | see `debug_variable_detail` section below |

Annotations are parenthetical, comma-separated. Only non-default state is shown (enabled + suspend are defaults, so only `disabled` and `no suspend` appear).

**4. Multi-breakpoint-line hint** — Multiple breakpoints on the same line is unusual and foreign to most agents. Whenever same-line breakpoints are grouped (in any output mode), the header includes `(multi-breakpoint-line)` to signal this concept explicitly:
```
src/WorldClass.php:13 (multi-breakpoint-line)
 - #3
 - #5 (disabled)
```

**5. Input format matches output format** — If the output says `src/index.php:15`, the input accepts `src/index.php:15`. The agent can use output from one tool as input to another without reformatting. No separate file/line parameters when a single `location` is more natural.

**6. Guide, don't dump** — When the agent hits an edge case (like multiple breakpoints on one line), don't just list data — tell it what to do: "Choose a breakpoint via #ID or remove other breakpoints first."

### Response examples

These examples show the shared pattern applied across different tools and scenarios.

**Success — single item (add, update, stop):**
```
#4 src/WorldClass.php:13
```

**Success — list (list, remove):**
```
#2 src/index.php:5
src/WorldClass.php:13 (multi-breakpoint-line)
 - #3 (condition: $foo === '')
 - #5 (disabled)
```

**Success + context (add with existing breakpoint on same line):**
```
#6 src/WorldClass.php:13

src/WorldClass.php:13 (multi-breakpoint-line)
 - #3 (condition: $foo === '')
 - #4
 - #5 (disabled)
 - #6 (new)
```

**Success — remove with remaining (compact index for remaining):**
```
#3 src/WorldClass.php:13 (condition: $foo === '')
#4 src/WorldClass.php:13

2 breakpoint(s) remaining:
#5 src/index.php:5
#6 src/index.php:10
```

**Error: ambiguous — tree format + guidance:**
```
src/WorldClass.php:13 (multi-breakpoint-line)
 - #3 (condition: $foo === '')
 - #4
 - #5 (disabled)
 - #6

Choose a breakpoint via #ID or remove other breakpoints first.
```

**Error: not found — shows all breakpoints in project (compact index):**
```
Breakpoint '#999' not found, current breakpoints:

#3 src/index.php:5
#7 src/WorldClass.php:13
#10 src/NonUsedClass.php:5
```
Always shows the full project list — no substring filtering. The agent gets an unambiguous picture of what exists.

**Error: empty state — one-liner, no ambiguity:**
```
No breakpoints in project
```

**Error: no changes specified on update:**
```
No changes specified. Use enabled, condition, log_expression, or suspend to update.
```

**Session — same pattern:**
```
#12345 "index.php" at src/index.php:15 (active)
#12346 "test.php" [running]
```
Status is only shown for non-default state: `[running]` means code is executing between breakpoints (agent can't inspect), `[stopped]` means the session has been terminated. No tag = paused at a breakpoint, ready for interaction.

---

## Core Concept: Debug Snapshot

The snapshot is not a tool itself — it's the **standard response format** returned by most tools. It mirrors what a human sees when paused at a breakpoint:

```
Debug Snapshot:
├── session:      #ID "name" [status] at file:line (active)
├── source:       FQDN\Class::method() — file:line
│                 ±5 lines around current position, current line marked with →
│                 (orientation, not a code viewer — agent can read full files)
├── variables:    top-level variables with type + value preview
│                 (scalars show value, objects show class name,
│                  arrays show count)
└── stacktrace:   #depth name at file:line (per frame, shallowest first)
```

All snapshot-returning tools accept optional `include` parameter to request
only specific parts (e.g., `include: ["source", "variables"]`).
Session + position are always included (minimal overhead, always needed).

**Source context (±5 lines)**: Shows ±5 lines around the current position (respecting file boundaries), with the current line marked by `→`. Header includes FQDN class name and method/function name extracted via PHP PSI. This is orientation — the agent already has file-reading tools for full content. Simpler and more predictable than scope-aware method extraction.

Example source context output:
```
\App\Service\UserService::login() — src/Service/UserService.php:8

  3
  4 class UserService {
  5     public function login() {
  6         $user = getUser();
  7         if ($user === null) {
→ 8             return false;
  9         }
 10         return true;
 11     }
 12 }
```

**Stack trace format**: `#depth name at file:line` per frame, shallowest first. Frame names extracted via `customizePresentation()` (not `equalityObject`, which returns null for PHP/Xdebug).

**Variable previews**: Keep it scannable. `$count = 42`, `$request = {ServerRequest}`, `$items = array(15)`, `$name = "hello world"`. The agent can use `variable_detail` to dig deeper.

---

## Tools

### 1. Breakpoints

These work independently of debug sessions — breakpoints exist in the project regardless of whether debugging is active.

#### `breakpoint_list`
List all breakpoints in the project. Same-line breakpoints are auto-grouped with `(multi-breakpoint-line)` hint.

**Input**: `file` (optional) — filter by file path substring
**Output**:
```
#2 src/index.php:5
src/WorldClass.php:13 (multi-breakpoint-line)
 - #3
 - #4 (disabled)
 - #5
 - #6 (condition: $foo === 'bar', log: $foo, no suspend)
#10 src/NonUsedClass.php:5 (method)
```
Or: `No breakpoints in project`
Or: `No breakpoints in src/Foo.php`
Or: `File 'src/nonExistent' not found` (when the file doesn't exist at all)

**Annotations** (only non-default state is shown):
- `method` — method breakpoint (vs line breakpoint)
- `library` — breakpoint is in a library/vendor path (detected via `ProjectFileIndex.isInLibrary()`, not string matching)
- `disabled` — breakpoint is not enabled
- `condition: expr` — has a condition expression
- `log: expr` — has a log expression
- `no suspend` — won't pause execution when hit

---

#### `breakpoint_add`
Add a line breakpoint.

**Input**:
- `location` (required) — file:line, e.g. `src/index.php:15`
- `condition` (optional) — PHP expression, e.g. `$count > 10`
- `log_expression` (optional) — expression to evaluate and log when hit
- `suspend` (optional, default true) — whether to pause execution

**Validation**:
- Line must be >= 1 (negative and zero are rejected at parse level)
- Line must not exceed the file's actual line count (prevents ghost breakpoints that never fire)
- File must exist in the project

**Output**: The created breakpoint. If the line already has breakpoints, shows all breakpoints on that line with `(new)` marking the one just added:
```
#6 src/WorldClass.php:13

src/WorldClass.php:13 (multi-breakpoint-line)
 - #3 (condition: $foo === '')
 - #4
 - #5 (disabled)
 - #6 (new)
```

---

#### `breakpoint_update`
Modify an existing breakpoint.

**Input**:
- `id` (required) — breakpoint #ID or file:line reference (accepts `#` prefix)
- `enabled` (optional) — true/false
- `condition` (optional) — new condition (empty string to remove)
- `log_expression` (optional) — new log expression
- `suspend` (optional) — true/false

**Validation order**: ID is validated first — if the ID is wrong, the agent gets the not-found error with current breakpoints, regardless of whether change params were provided. Only if the ID is valid does the "no changes specified" check apply. This ensures the agent always gets the most actionable error first.

**Output**: Updated breakpoint in `#ID file:line (annotations)` notation.
**No changes**: Error `No changes specified. Use enabled, condition, log_expression, or suspend to update.`
**Not found**: Shows all breakpoints in the project (compact index).
**Ambiguous file:line**: Lists all breakpoints at that line with `(multi-breakpoint-line)` hint and guidance to use #ID.

---

#### `breakpoint_remove`
Remove breakpoint(s). Requires explicit targets — no silent "remove all".

**Input**:
- `id` (optional) — #ID, file:line, file path, or file substring. Comma-separated for multiple. Accepts `#` prefix. All formats can be mixed in one call.
- `all` (optional) — set to `true` to remove ALL breakpoints in the project

**Output**: Lists removed breakpoints (full detail) + remaining count (compact index):
```
#3 src/WorldClass.php:13 (condition: $foo === '')
#4 src/WorldClass.php:13

2 breakpoint(s) remaining:
#5 src/index.php:5
#6 src/index.php:10
```
**Not found**: Shows all breakpoints in the project (compact index).
**Ambiguous file:line**: Same as update — lists options with `(multi-breakpoint-line)` hint and guidance.
**No params**: Error asking to specify targets or use `all=true`.

---

### 2. Session Management

#### `session_list`
List active debug sessions.

**Input**: (none)
**Output**:
```
#12345 "index.php" at src/index.php:15 (active)
#12346 "test.php" [running]
```
Or: `No sessions in project`

---

#### `session_stop`
Stop debug session(s).

**Input**:
- `session_id` (optional) — ID of session (with or without `#` prefix). If omitted → stops the active session (or first on the stack).
- `all` (optional) — true to stop all sessions

**Output**: Stopped session in `#ID "name" at file:line` notation. If other sessions remain, lists them with count.
**Not found (specific ID)**: Error — shows the requested ID + list of active sessions.
**No sessions (no ID or all=true)**: `No sessions in project` — this is `ok`, not an error. Stopping nothing is a no-op, not a failure. Only specifying a non-existent ID is an error.

---

### 3. Navigation / Control

All navigation tools:
- Accept optional `session_id` (defaults to active session)
- Return a **Debug Snapshot** after the action completes
- Are async: they trigger the action, wait for the debugger to pause again, then return the snapshot

#### `debug_continue`
Resume execution until next breakpoint or end.

**Input**: optional `session_id`, optional `include`
**Output**: Debug Snapshot (at next breakpoint) or session-ended status

---

#### `debug_step_over`
Execute current line, stop at next line in same scope.

**Input**: optional `session_id`, optional `include`
**Output**: Debug Snapshot

---

#### `debug_step_into`
Step into the function call on current line.

**Input**: optional `session_id`, optional `include`
**Output**: Debug Snapshot

---

#### `debug_step_out`
Run until current function returns, stop in caller.

**Input**: optional `session_id`, optional `include`
**Output**: Debug Snapshot

---

#### `debug_run_to_line`
Continue execution until reaching a specific line (temporary breakpoint).

**Input**:
- `file` (required)
- `line` (required)
- optional `session_id`, optional `include`

**Output**: Debug Snapshot (at target line) or session-ended if line not reached

---

### 4. Inspection

#### `debug_snapshot`
Get the current debug state without changing anything. This is the "just show me where we are" tool.

**Input**: optional `session_id`, optional `include`
**Output**: Debug Snapshot

---

#### `debug_inspect_frame`
Switch inspection to a different stack frame. Like clicking a row in the stacktrace panel — shows variables and code at that frame's location.

**Input**:
- `frame_index` (required) — 0 = current (top), 1 = caller, etc.
- optional `session_id`, optional `include`

**Output**: Debug Snapshot (for the selected frame — source + variables at that frame's scope)

---

#### `debug_variable_detail`
Expand variables to see their properties/children. For drilling into nested objects and arrays.

**Input**:
- `path` (optional) — variable path(s) using dot notation, comma-separated for multiple. Omit to expand all top-level variables. Examples: `$engine`, `$engine.pattern`, `$engine, $result`
- `depth` (optional, default 1) — how many levels of children to expand. Use 0 for flat list (same as `debug_variables`).
- `globals` (optional, default false) — include PHP superglobals. Only applies when no path specified.
- optional `session_id`

**Path resolution**:
- Strips `$` prefix and splits on `.`: `$engine.ast.children.0` → segments `["engine", "ast", "children", "0"]`
- Flexible matching: `parent` matches both `parent` and `*TypedPatternEngine\Nodes\AstNode*parent` (Xdebug's inherited property naming convention `*ClassName*propertyName`)
- If a short name matches multiple inherited properties (e.g., `*AstNode*parent` and `*NestedNode*parent`), returns an ambiguity error listing the options — agent can use the full `*ClassName*prop` form to disambiguate
- The agent can always use the exact Xdebug property name as shown in output

**Circular reference detection**:
- PHP objects commonly have bidirectional references (e.g., `child.parent → parentNode → children → child.parent → ...`)
- During automatic depth expansion, object types (FQCNs) are tracked along the ancestor chain
- When a child's type matches an ancestor's type, expansion stops with `(circular reference)` marker
- Arrays are excluded from tracking (they legitimately nest)
- **Explicit path navigation bypasses cycle detection** — `$engine.ast.children.0.parent.children.0.text` works even though it traverses a circular reference, because path walking resolves segments directly without ancestor tracking. Only the final `expandValue()` call starts fresh.
- Trade-off: false positives for legitimately nested same-type objects (rare). The agent can drill past with explicit paths.

**Output**: Variable tree with indentation:
```
$engine = {CompiledPattern}
  pattern = {string} "PAGE{id:int}"
  regex = {string} "/^PAGE(?P<g1>\d+)$/"
  ast = {SequenceNode}
    *TypedPatternEngine\Nodes\AstNode*parent = null
    children = {array[2]}
      0 = {LiteralNode}
        text = {string} "PAGE"
        *TypedPatternEngine\Nodes\AstNode*parent = {SequenceNode} (circular reference)
      1 = {GroupNode}
        *TypedPatternEngine\Nodes\AstNode*parent = {SequenceNode} (circular reference)
```

**Design note — variable_detail vs evaluate**: `variable_detail` is a read-only tree walk — it expands nodes the debugger already has. Safe, no side effects. For evaluating expressions like `$request->headers->get('Content-Type')`, use `debug_evaluate` instead — it executes PHP code via Xdebug in the current context (can have side effects). The distinction matters: an agent should prefer `variable_detail` for inspecting state (cheap, safe) and only use `evaluate` when it needs to run code (method calls, computed values). PHP developers think in arrow chains (`$request->headers->get()`), so agents will naturally gravitate toward `evaluate` — but for simple property/array access, `variable_detail` with dot-path notation (`$request.headers.0`) is the better choice.

---

#### `debug_evaluate`
Evaluate a PHP expression in the current debug context.

**Input**:
- `expression` (required) — PHP expression, e.g. `$request->getMethod()`, `count($items)`, `$user->getName()`
- optional `session_id`

**Output**: Result with type and value (same format as variable preview, expandable via `variable_detail`)

---

#### `debug_set_value`
Modify a variable's value at runtime.

**Input**:
- `path` (required) — variable path, e.g. `$count`, `$request.method`
- `value` (required) — new value as string (e.g. `42`, `"hello"`, `null`)
- optional `session_id`

**Output**: Confirmation + updated Debug Snapshot

---

## Tool Count Summary

| Category | Tools | Count |
|---|---|---|
| Breakpoints | list, add, update, remove | 4 |
| Sessions | list, stop | 2 |
| Navigation | continue, step_over, step_into, step_out, run_to_line | 5 |
| Inspection | snapshot, inspect_frame, variable_detail, evaluate, set_value | 5 |
| **Total** | | **16** |

---

## Tool Annotations (MCP Hints)

Every tool declares MCP `ToolAnnotations` to signal its behavior to the client. These are hints, not guarantees.

**Key decisions:**

- **`idempotentHint` is always `false`** — The agent shares IDE state with a human user. Between two identical calls, the user may have changed breakpoints, stepped through code, or stopped sessions. No call is guaranteed to be a no-op on repeat.
- **`openWorldHint` is always `false`** — All tools interact with PhpStorm's internal state, not external services.
- **`readOnlyHint`** — `true` only for list/inspection tools that don't modify state.
- **`destructiveHint`** — `true` for tools that remove or terminate things (breakpoint_remove, session_stop). `false` for tools that add or modify.

| Tool | readOnly | destructive | idempotent | openWorld |
|---|---|---|---|---|
| `breakpoint_list` | true | false | false | false |
| `breakpoint_add` | false | false | false | false |
| `breakpoint_update` | false | false | false | false |
| `breakpoint_remove` | false | true | false | false |
| `session_list` | true | false | false | false |
| `session_stop` | false | true | false | false |
| `debug_variable_detail` | true | false | false | false |
| `debug_snapshot` | true | false | false | false |

---

## Active Session Convention

PhpStorm maintains an **active session** — the one currently selected in the debug tab. All session-scoped tools default to the active session (or first on the stack if no active session):

- **0 sessions**: `No sessions in project` (ok for implicit stop, error for explicit ID)
- **1 session**: That session is always active
- **N sessions**: The active session is used by default. Agent can switch by passing a different `session_id`.

The snapshot always includes which session is active:
```
session: { id: "abc", name: "index.php", active: true }
```

`session_list` marks which session is active. The agent only needs to think about session IDs when it wants to work with a non-active session.

## Snapshot Customization

All tools that return a snapshot accept an optional `include` parameter to request only specific parts:

```
include: ["variables"]              → only variables
include: ["source", "variables"]    → source + variables, no stacktrace
include: ["stacktrace"]             → only the stack
```

**Default (no `include`)**: full snapshot (position + source + variables + stacktrace).

This matters for token efficiency — if the agent is stepping through 10 lines, it probably doesn't need the full stacktrace every time. Just `include: ["source", "variables"]` to see what changed.

---

## What We're NOT Including (v1)

- **Watches**: `evaluate` covers this — watches are a UI persistence concept for humans
- **Starting debug sessions**: Human controls when debugging begins
- **Mute all breakpoints**: Edge case, add later if needed
- **Exception breakpoints**: Only line breakpoints for v1
- **Conditional stepping (smart step into)**: Too complex for v1

## Implementation Notes

- Navigation tools are **async by nature**: they trigger an action in PhpStorm, then wait for the debugger to pause. The MCP response returns only when paused (or timed out / session ended).
- All tools run their IDE operations on EDT (Event Dispatch Thread) as required by IntelliJ, but the MCP request handling itself is on a background thread.
- Variable preview generation should be smart: truncate long strings, limit array previews, show class names for objects.
- **Library detection** uses `ProjectFileIndex.isInLibrary()` — the platform API that respects the project's actual library root configuration, not string matching on path names like `vendor/`.
- **Line validation** on `breakpoint_add` uses `FileDocumentManager` to get the actual line count and reject out-of-bounds lines. PhpStorm's internal `XBreakpointManager.addLineBreakpoint()` doesn't validate this (we bypass the GUI guards), so the tool must do it.
- **ReadAction threading**: All IntelliJ model reads (Document, PSI, XBreakpointManager, XDebuggerManager) must be wrapped in `ReadAction.compute()`. Every service routes this through a `Platform.readAction()` method — real implementations call `ReadAction.compute()`, test mocks pass through directly. Without this, random threading violations crash the MCP request handler.
- **PHP PSI dependency**: Source context uses `com.jetbrains.php.lang.psi.elements.Method`, `Function`, `PhpClass` for class/method name extraction. Requires `bundledPlugin("com.jetbrains.php")` in build.gradle.kts and `<depends>com.jetbrains.php</depends>` in plugin.xml.
- **Xdebug inherited property naming**: Xdebug exposes inherited private/protected properties with the `*FullyQualified\ClassName*propertyName` convention. Path resolution matches short names against these (e.g., `parent` matches `*TypedPatternEngine\Nodes\AstNode*parent`). When ambiguous (multiple classes define the same property name), the agent must use the full `*ClassName*prop` form.
- **Circular reference detection**: `XValue` has no accessible object identity (no Xdebug handle/address through the public API), and each `computeChildren()` call creates fresh `XValue` wrappers, so Java object identity doesn't work. Instead, object types (FQCNs) are tracked along the expansion ancestor chain. Arrays are excluded (type `"array"` nests legitimately). This is a heuristic — false positives exist for legitimately nested same-type objects, but are rare in practice. Explicit path navigation always bypasses cycle detection since `getVariableDetail` resolves the path first, then calls `expandValue` with a fresh ancestor set.
- **PHP superglobals**: `$_ENV`, `$_SERVER`, `$_GET`, `$_POST`, `$_SESSION`, `$_COOKIE`, `$_FILES`, `$_REQUEST`, `$GLOBALS` are filtered by default in variable output. They're noisy (dozens of entries) and rarely relevant during debugging. Opt-in via `globals: true`.
