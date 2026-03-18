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
└── stacktrace:   →#depth name at file:line (active frame marked with →)
```

All snapshot-returning tools accept optional `include` parameter to request
only specific parts (e.g., `include: ["source", "variables"]`).
Session is always included (minimal overhead, always needed).

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

**Stack trace format**: `#depth name at file:line` per frame, shallowest first. Active frame marked with `→` prefix. Frame names extracted via `customizePresentation()` (not `equalityObject`, which returns null for PHP/Xdebug). Library frames annotated with `(library)`.

Example stack trace output:
```
→#0 WorldClass->fooBar() at src/WorldClass.php:22
 #1 WorldClass->foo() at src/WorldClass.php:11
 #2 {main}() at src/index.php:8
```

**Variable previews**: Keep it scannable. `$count = {int} 42`, `$request = {ServerRequest}`, `$items = array(3)`, `$name = {string} "hello"`, `$foo = {null} null`. The agent can use `debug_variable_detail` to dig deeper.

Example full snapshot output:
```
#12345 "index.php" at src/WorldClass.php:22 (active)

\Brannow\Sandbox\WorldClass::fooBar() — src/WorldClass.php:22

 20         $engine = new TypedPatternEngine();
 21         $result = $engine->match('PAGE55');
→22         return $bar;

$this = {Brannow\Sandbox\WorldClass}
$foo = {string} "foo"
$bar = {string} "foo-bar"

→#0 WorldClass->fooBar() at src/WorldClass.php:22
 #1 WorldClass->foo() at src/WorldClass.php:11
 #2 {main}() at src/index.php:8
```

---

## Tools

### 1. Breakpoints

These work independently of debug sessions — breakpoints exist in the project regardless of whether debugging is active.

#### `breakpoint_list`
List all breakpoints with their locations, conditions, and status. Same-line breakpoints are auto-grouped with `(multi-breakpoint-line)` hint.

**Input**: `file` (optional, string) — filter by file path substring
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

**Annotations** (only non-default state is shown, order: active → method → vendor → disabled → condition → log → no suspend):
- `active` — breakpoint at the current execution position
- `method` — method breakpoint (vs line breakpoint)
- `vendor` — breakpoint is in a library/vendor path (detected via `ProjectFileIndex.isInLibrary()`, not string matching)
- `disabled` — breakpoint is not enabled
- `condition: expr` — has a condition expression
- `log: expr` — has a log expression
- `no suspend` — won't pause execution when hit

---

#### `breakpoint_add`
Add a line breakpoint at a file:line location.

**Input**:
- `location` (required, string) — file:line, e.g. `src/index.php:15`
- `condition` (optional, string) — PHP expression that must be true for the breakpoint to trigger, e.g. `$count > 10`
- `log_expression` (optional, string) — PHP expression to evaluate and log when the breakpoint is hit, e.g. `$request->getUri()`
- `suspend` (optional, boolean, default true) — whether to pause execution. Set to false for logging-only breakpoints.

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
Modify an existing breakpoint. Only provided fields are changed.

**Input**:
- `id` (required, string) — breakpoint #ID or file:line reference (accepts `#` prefix)
- `enabled` (optional, boolean) — true/false
- `condition` (optional, string) — new condition (empty string to remove)
- `log_expression` (optional, string) — new log expression (empty string to remove)
- `suspend` (optional, boolean) — true/false

**Validation order**: ID is validated first — if the ID is wrong, the agent gets the not-found error with current breakpoints, regardless of whether change params were provided. Only if the ID is valid does the "no changes specified" check apply. This ensures the agent always gets the most actionable error first.

**Output**: Updated breakpoint in `#ID file:line (annotations)` notation.
**No changes**: Error `No changes specified. Use enabled, condition, log_expression, or suspend to update.`
**Not found**: Shows all breakpoints in the project (compact index).
**Ambiguous file:line**: Lists all breakpoints at that line with `(multi-breakpoint-line)` hint and guidance to use #ID.

---

#### `breakpoint_remove`
Remove one or more breakpoints. Requires explicit targets — no silent "remove all".

**Input**:
- `id` (optional, string) — #ID, file:line, file path, or file substring. Comma-separated for multiple. Accepts `#` prefix. All formats can be mixed in one call.
- `all` (optional, boolean) — set to `true` to remove ALL breakpoints in the project

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
List active debug sessions with their current position and status. Stopped sessions are excluded.

**Input**: (none)
**Output**:
```
#12345 "index.php" at src/index.php:15 (active)
#12346 "test.php" [running]
```
Or: `No sessions in project`

---

#### `session_stop`
Stop one or more debug sessions.

**Input**:
- `session_id` (optional, string) — ID of session (with or without `#` prefix). If omitted → stops the active session (or first on the stack).
- `all` (optional, boolean) — true to stop all sessions

**Output**: Stopped session in `#ID "name" [stopped] at file:line` notation. If other sessions remain, lists them with count.
**Not found (specific ID)**: Error — shows the requested ID + list of active sessions.
**No sessions (no ID or all=true)**: `No sessions in project` — this is `ok`, not an error. Stopping nothing is a no-op, not a failure. Only specifying a non-existent ID is an error.

---

### 3. Navigation / Control

All navigation tools require a paused debug session and:
- Accept optional `session_id` (defaults to active session)
- Accept optional `include` to filter the snapshot (default: full snapshot)
- Accept optional `globals` to include PHP superglobals in variables (default: false)
- Return a **Debug Snapshot** after the action completes
- Are async: they trigger the action, register a temporary `XDebugSessionListener`, wait for `sessionPaused()` or `sessionStopped()`, then return the snapshot

**Session resolution errors** (shared across all debug tools):
- `No active debug session` — no session exists
- `Session '#ID' not found` — specific session_id doesn't match
- `Session has ended` — session is stopped
- `Session is running — not paused at a breakpoint` — session is executing, can't step

#### `debug_step`
Step through code. Consolidates all stepping actions into one tool — they're identical except for the session method call.

**Input**:
- `action` (required, string enum) — the stepping action:
  - `"over"` — execute current line, stop at next line in same scope
  - `"into"` — step into the function call on current line
  - `"out"` — run until current function returns, stop in caller
  - `"continue"` — resume execution until next breakpoint or end
- `session_id` (optional, string) — debug session ID. Omit to use the active session.
- `include` (optional, string array) — snapshot parts: `"source"`, `"variables"`, `"stacktrace"`. Omit for full snapshot.
- `globals` (optional, boolean, default false) — include PHP superglobals in variables

**Output**: Debug Snapshot (at the new position after stepping) or `Session ended` if the program finished.

**Why one tool, not four?** Over/into/out/continue are identical in structure — same inputs, same output, same async pattern. Splitting them into four tools adds noise to the tool list without adding capability. The `action` enum is explicit enough.

---

#### `debug_run_to_line`
Run to a specific line and return a debug snapshot. Like a temporary breakpoint — execution continues until reaching the target line.

**Input**:
- `location` (required, string) — target file:line, e.g. `src/index.php:15`. Accepts absolute or project-relative paths.
- `session_id` (optional, string) — debug session ID. Omit to use the active session.
- `include` (optional, string array) — snapshot parts: `"source"`, `"variables"`, `"stacktrace"`. Omit for full snapshot.
- `globals` (optional, boolean, default false) — include PHP superglobals in variables

**Output**: Debug Snapshot (at target line) or `Session ended` if the line wasn't reached.

**Why separate from `debug_step`?** It has a fundamentally different input — a location parameter instead of a stepping direction. Forcing it into the `action` enum would feel unnatural.

---

### 4. Inspection

All inspection tools require a paused debug session and accept optional `session_id`.

#### `debug_snapshot`
Get the current debug state without changing anything. This is the "just show me where we are" tool.

**Input**:
- `session_id` (optional, string) — debug session ID. Omit to use the active session.
- `include` (optional, string array) — parts: `"source"`, `"variables"`, `"stacktrace"`. Omit for full snapshot.
- `globals` (optional, boolean, default false) — include PHP superglobals in variables

**Output**: Debug Snapshot

---

#### `debug_inspect_frame`
View source and variables at a different call stack depth. Like clicking a row in the stacktrace panel — shows variables and code at that frame's location. Uses `setCurrentStackFrame` to switch IDE context before reading the snapshot.

**Input**:
- `frame_index` (required, integer) — 0 = current (top), 1 = caller, etc. Use `debug_snapshot` with `include: ["stacktrace"]` to see available frames.
- `session_id` (optional, string) — debug session ID. Omit to use the active session.
- `include` (optional, string array) — snapshot parts: `"source"`, `"variables"`, `"stacktrace"`. Omit for full snapshot.
- `globals` (optional, boolean, default false) — include PHP superglobals in variables

**Output**: Debug Snapshot (for the selected frame — source + variables at that frame's scope)

---

#### `debug_variable_detail`
Expand a variable's properties and nested children. Use when `debug_snapshot` shows a type preview like `{User}` or `array(5)` that you need to see inside.

**Input**:
- `path` (optional, string) — variable path(s) using dot notation, comma-separated for multiple. Omit to show all top-level variables expanded. Examples: `$engine`, `$engine.pattern`, `$engine, $result`
- `depth` (optional, integer, default 1) — how many levels of children to expand. Use 0 for just type and value without expanding children.
- `globals` (optional, boolean, default false) — include PHP superglobals. Only applies when no path specified.
- `session_id` (optional, string) — debug session ID. Omit to use the active session.

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
Evaluate a PHP expression in the current debug scope — test ideas, call methods, or modify variables. Not read-only: expressions can have side effects including variable assignment (`$bar = 'new value'`).

**Input**:
- `expression` (required, string) — PHP expression. Examples: `count($items)`, `$user->getName()`, `$this->repository->findAll()`, `array_keys($config)`, `$bar = 'test'`
- `depth` (optional, integer, default 1) — expansion depth for object/array results. Use 0 for just type and value, 2+ for deeper nesting.
- `session_id` (optional, string) — debug session ID. Omit to use the active session.

**Output**: Position context (best-effort) + expression result with type, expanded to specified depth:
```
at \App\UserService::processRequest() — src/UserService.php:42

count($items) = {int} 3
```

Object result with depth expansion:
```
at src/UserService.php:42

$user = {App\Entity\User}
  name = {string} "John"
  email = {string} "john@example.com"
  age = {int} 30
```

Position header formats:
- With class & method: `at \App\Foo::bar() — src/Foo.php:10`
- Function only: `at doStuff() — src/helpers.php:5`
- File only: `at src/index.php:1`
- Library: `at \Lib\Foo::run() — vendor/lib/Foo.php:20 (library)`
- No position available: header omitted

**Error**: Returns the raw Xdebug error message — no wrapper noise.

**Why `debug_set_value` was dropped**: `debug_evaluate` already handles assignments (`$bar = 'test'`, `$this->name = 'new'`). The `XValueModifier` API had callback/timeout issues, and a separate tool adds complexity with zero capability gain.

---

## Tool Count Summary

| Category | Tools | Count |
|---|---|---|
| Breakpoints | `breakpoint_list`, `breakpoint_add`, `breakpoint_update`, `breakpoint_remove` | 4 |
| Sessions | `session_list`, `session_stop` | 2 |
| Navigation | `debug_step` (over/into/out/continue), `debug_run_to_line` | 2 |
| Inspection | `debug_snapshot`, `debug_inspect_frame`, `debug_variable_detail`, `debug_evaluate` | 4 |
| **Total** | | **12** |

---

## Tool Annotations (MCP Hints)

Every tool declares MCP `ToolAnnotations` to signal its behavior to the client. These are hints, not guarantees.

**Key decisions:**

- **`idempotentHint` is always `false`** — The agent shares IDE state with a human user. Between two identical calls, the user may have changed breakpoints, stepped through code, or stopped sessions. No call is guaranteed to be a no-op on repeat.
- **`openWorldHint`** — `false` for all tools except `debug_evaluate`. Evaluate is `true` because expressions execute PHP code via Xdebug, which can call methods, access databases, make HTTP requests, or trigger any other side effect the PHP application's code path allows.
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
| `debug_snapshot` | true | false | false | false |
| `debug_variable_detail` | true | false | false | false |
| `debug_inspect_frame` | true | false | false | false |
| `debug_evaluate` | false | false | false | **true** |
| `debug_step` | false | false | false | false |
| `debug_run_to_line` | false | false | false | false |

---

## Active Session Convention

PhpStorm maintains an **active session** — the one currently selected in the debug tab. All session-scoped tools default to the active session (or first on the stack if no active session):

- **0 sessions**: `No active debug session` (error for debug tools), `No sessions in project` (ok for implicit stop)
- **1 session**: That session is always active
- **N sessions**: The active session is used by default. Agent can switch by passing a different `session_id`.

`session_list` marks which session is active. The agent only needs to think about session IDs when it wants to work with a non-active session.

## Snapshot Customization

All tools that return a snapshot accept an optional `include` parameter to request only specific parts:

```
include: ["variables"]              → only variables
include: ["source", "variables"]    → source + variables, no stacktrace
include: ["stacktrace"]             → only the stack
```

**Default (no `include` or empty `include`)**: full snapshot (session + source + variables + stacktrace).

This matters for token efficiency — if the agent is stepping through 10 lines, it probably doesn't need the full stacktrace every time. Just `include: ["source", "variables"]` to see what changed.

---

## What We're NOT Including (v1)

- **Watches**: `evaluate` covers this — watches are a UI persistence concept for humans
- **Starting debug sessions**: Human controls when debugging begins
- **Mute all breakpoints**: Edge case, add later if needed
- **Exception breakpoints**: Only line breakpoints for v1
- **Conditional stepping (smart step into)**: Too complex for v1

## Implementation Notes

- Navigation tools are **async by nature**: they trigger an action in PhpStorm, register a temporary `XDebugSessionListener`, then block via `CompletableFuture.get()` until `sessionPaused()` or `sessionStopped()` fires. The MCP response returns only when the debugger pauses or the session ends.
- All tools run their IDE operations on EDT (Event Dispatch Thread) as required by IntelliJ, but the MCP request handling itself is on a background thread.
- Variable preview generation is smart: type + value for scalars (`{int} 42`, `{string} "hello"`), class name for objects (`{ServerRequest}`), count for arrays (`array(15)`), `{null} null` for nulls, `(unknown)` as fallback.
- **Library detection** uses `ProjectFileIndex.isInLibrary()` — the platform API that respects the project's actual library root configuration, not string matching on path names like `vendor/`. Annotated as `(library)` in source context and stack frames, `(vendor)` in breakpoint annotations.
- **Line validation** on `breakpoint_add` uses `FileDocumentManager` to get the actual line count and reject out-of-bounds lines. PhpStorm's internal `XBreakpointManager.addLineBreakpoint()` doesn't validate this (we bypass the GUI guards), so the tool must do it.
- **ReadAction threading**: All IntelliJ model reads (Document, PSI, XBreakpointManager, XDebuggerManager) must be wrapped in `ReadAction.compute()`. Every service routes this through a `Platform.readAction()` method — real implementations call `ReadAction.compute()`, test mocks pass through directly. Without this, random threading violations crash the MCP request handler.
- **PHP PSI dependency**: Source context uses `com.jetbrains.php.lang.psi.elements.Method`, `Function`, `PhpClass` for class/method name extraction. Requires `bundledPlugin("com.jetbrains.php")` in build.gradle.kts and `<depends>com.jetbrains.php</depends>` in plugin.xml.
- **Xdebug inherited property naming**: Xdebug exposes inherited private/protected properties with the `*FullyQualified\ClassName*propertyName` convention. Path resolution matches short names against these (e.g., `parent` matches `*TypedPatternEngine\Nodes\AstNode*parent`). When ambiguous (multiple classes define the same property name), the agent must use the full `*ClassName*prop` form.
- **Circular reference detection**: `XValue` has no accessible object identity (no Xdebug handle/address through the public API), and each `computeChildren()` call creates fresh `XValue` wrappers, so Java object identity doesn't work. Instead, object types (FQCNs) are tracked along the expansion ancestor chain. Arrays are excluded (type `"array"` nests legitimately). This is a heuristic — false positives exist for legitimately nested same-type objects, but are rare in practice. Explicit path navigation always bypasses cycle detection since `getVariableDetail` resolves the path first, then calls `expandValue` with a fresh ancestor set.
- **PHP superglobals**: `$_ENV`, `$_SERVER`, `$_GET`, `$_POST`, `$_SESSION`, `$_COOKIE`, `$_FILES`, `$_REQUEST`, `$GLOBALS` are filtered by default in variable output. They're noisy (dozens of entries) and rarely relevant during debugging. Opt-in via `globals: true`.
- **Breakpoint IDs**: Numeric, derived from `XBreakpoint.timeStamp`. Displayed with `#` prefix in output, accepted with or without `#` in input.
- **Session IDs**: Derived from `System.identityHashCode(session)`. Accepted with or without `#` prefix.
- **Frame navigation**: `debug_inspect_frame` invokes `setCurrentStackFrame` on the IDE before reading the snapshot, which switches the Variables panel context — same as clicking a row in the debugger's Frames panel.
