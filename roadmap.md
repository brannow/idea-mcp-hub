# Roadmap

Build incrementally — each milestone is testable on its own before moving to the next.

---

## Milestone 0: Project Foundation ✅

- [x] Project scaffold (Gradle, plugin.xml, Kotlin)
- [x] Hello World plugin loads in sandboxed PhpStorm
- [x] Documentation organized with index
- [x] Tool design spec finalized (16 tools, snapshot concept)
- [x] Logo, .gitignore, initial commit

---

## Milestone 0.5: Status Bar Widget ✅

Give the human visibility into the MCP server state — a small indicator in PhpStorm's status bar.

- [x] Status bar widget with plugin icon (grayed out = inactive, colored = active)
- [x] Click to open popup with: connection status, server port/transport info
- [x] Activity log: lightweight rolling log of recent MCP events ("Client connected", "breakpoint_add called", etc.)
- [x] Server start/stop toggle from the widget

**Test**: Plugin loads → icon appears in status bar (grayed out). Server starts → icon lights up. Click icon → see status popup. Later when tools are implemented, verify tool calls appear in the activity log.

---

## Milestone 1: MCP Server Infrastructure ✅

Get a working MCP server running inside the plugin that an external client can connect to.

- [x] Add Kotlin MCP SDK dependency to build.gradle.kts
- [x] Create MCP server service (project-level, starts with project)
- [x] Transport: Streamable HTTP on localhost (fixed port 6969, fallback to random). See `internal/docs/04-mcp-sdk/stdio-proxy-architecture.md` for stdio proxy analysis.
- [x] Register a single dummy tool (`ping` → returns `pong`) to verify the protocol works
- [x] Connect from an external MCP client (MCP Inspector) and call `ping`
- [x] Client connect/disconnect notifications in activity log and widget

**Test**: MCP client connects → calls `ping` → gets `pong` response. ✅ Verified with MCP Inspector.

---

## Milestone 2: Breakpoint Tools ✅

First real tools — breakpoints work without an active debug session, so they're the simplest to test.

- [x] `breakpoint_list` — list all line breakpoints, optional file filter
- [x] `breakpoint_add` — add a line breakpoint (file + line), with optional condition, log expression, suspend toggle
- [x] `breakpoint_update` — enable/disable, change condition/log expression/suspend by ID or file:line
- [x] `breakpoint_remove` — remove by ID(s), file:line(s) (comma-separated), or `all=true` to remove all
- [x] Flexible file paths: accepts absolute or project-relative paths, returns project-relative
- [x] Flexible IDs: numeric timestamp ID or `file:line` reference (e.g. `src/index.php:5`)
- [x] Line validation: rejects line 0, negative lines, and lines beyond the file's actual line count
- [x] Library detection via `ProjectFileIndex.isInLibrary()` (not string matching on "vendor/")
- [x] Multi-breakpoint-line hint: groups same-line breakpoints with `(multi-breakpoint-line)` label everywhere
- [x] Two output modes: full detail for direct list, compact index (`#ID file:line`) for context hints
- [x] Not-found errors always show all project breakpoints (no substring filtering)
- [x] Update validates ID exists before checking for missing changes (most actionable error first)
- [x] No-changes guard on update: rejects calls with only an ID and no change params
- [x] Ambiguous file:line handling with guidance message
- [x] Unit tests: parameterized test suite covering list, add, update, remove with all edge cases

**Test**: Breakpoints added via MCP Inspector appear in PhpStorm gutter. List matches Breakpoints dialog. Remove clears them. ✅ Verified.

---

## Milestone 3: Session Management ✅

- [x] `session_list` — list active debug sessions with status and active flag
- [x] `session_stop` — stop a specific session or all sessions
- [x] Active session detection (which session is currently focused in the UI)
- [x] Smart stop: no params + no sessions = ok (no-op), specific ID not found = error
- [x] Consistent `ok`/`err` behavior across all stop scenarios
- [x] Unit tests: parameterized test suite for list and stop with edge cases

**Test**: Manually start 1-2 debug sessions in PhpStorm. Call `session_list` → verify output matches the debug tabs. Call `session_stop` → verify session ends. ✅ Verified.

---

## Milestone 3.5: Output Design & Code Quality ✅

Natural language output system, testability refactoring, and platform compliance.

- [x] Natural language output: tools respond with human-readable text, not JSON
- [x] Shared response pattern: result → context → error, consistent across all tools
- [x] Services made final: Platform interface pattern for testability without `open` classes
- [x] IntelliJ platform compliance: `JBList` usage, final light services, dead code removal
- [x] Tool design spec (ToolDesign.md) updated with all output patterns and validation rules

---

## Milestone 4a: Source Context ✅

Orientation for the agent — show where it is and the surrounding code. Not a code viewer (the agent can already read full files).

- [x] Get current position from paused session (`XDebugSession.currentPosition`)
- [x] Read source file content around current line (±5 lines, respecting file boundaries)
- [x] PHP PSI integration: extract FQDN class name and method/function name via `PsiTreeUtil`
- [x] Header format: `\App\Service\UserService::login() — src/Service/UserService.php:8`
- [x] Current line marked with `→` prefix, line numbers with dynamic gutter width
- [x] Handle edge cases: top-level code (no method/class), document not available, empty files
- [x] `SourceContextService` with Platform interface for testability
- [x] Pure companion functions `computeRange()` and `formatSource()` for unit testing
- [x] All document/PSI access wrapped in `ReadAction` via Platform interface
- [x] Added `bundledPlugin("com.jetbrains.php")` and `<depends>com.jetbrains.php</depends>`
- [x] Unit tests: parameterized suite — computeRange (8 cases), formatSource (5 cases), getSourceContext (5 cases), formatSourceContext (3 cases)

**Design decision**: ±5 lines instead of scope-aware method extraction. The agent can already read full files — source context is for quick orientation, not browsing. Simpler, more predictable, and cheaper in tokens.

**Test**: Pause at breakpoint inside a method → shows `\App\Service\UserService::login() — src/Service/UserService.php:8` with ±5 lines. Top-level code → shows file:line without class/method. ✅ Verified.

---

## Milestone 4b: Stack Frames ✅

Walk the call stack from `XSuspendContext` and present it as readable output.

- [x] Extract execution stack from `XSuspendContext` → `XExecutionStack`
- [x] Async `computeStackFrames()` callback bridged to synchronous via `CompletableFuture` (5s timeout)
- [x] Frame name extraction via `customizePresentation()` with `TextCollector` (minimal `ColoredTextContainer` impl)
- [x] Parses PHP/Xdebug presentation format (`"file.php:line, ClassName->method()"`) to extract name after comma
- [x] Format: `#depth name at file:line` — shallowest first (matches PhpStorm's Frames panel)
- [x] File paths are project-relative
- [x] Handle edge cases: no suspend context, empty stack, frames without source position, missing names
- [x] `StackFrameService` with Platform interface for testability
- [x] All frame access wrapped in `ReadAction` via Platform interface
- [x] Unit tests: parameterized suite — getStackFrames (6 cases), formatStackTrace (5 cases)

**Design decision**: `equalityObject` returns null for PHP/Xdebug, so frame names come from `customizePresentation()` instead. The PHP presentation format includes file info before the comma — we parse and strip that to get just the method/class name.

**Test**: Pause at breakpoint 3+ calls deep → stack matches PhpStorm's Frames panel. File paths are project-relative. ✅ Verified.

---

## Milestone 4c: Variables ✅

The hardest part — `XStackFrame.computeChildren()` is async/callback-based, values are lazy-loaded.

- [x] Extract top-level variables from current `XStackFrame`
- [x] Handle async `computeChildren()` callback pattern (EDT + CompletableFuture, same as StackFrameService)
- [x] Handle both `setPresentation()` overloads: plain type/value strings AND `XValuePresentation` (via `ValueTextCollector` implementing `XValueTextRenderer`)
- [x] Variable preview generation:
  - Scalars → value (`$count = 42`, `$name = "hello"`)
  - Objects → class name (`$request = {ServerRequest}`) — fallback to `{type}` when value is empty
  - Arrays → count (`$items = array(15)`)
  - Null → `$foo = null`
- [x] PHP superglobals filtered by default (`$_ENV`, `$_SERVER`, `$_GET`, `$_POST`, `$_SESSION`, `$_COOKIE`, `$_FILES`, `$_REQUEST`, `$GLOBALS`), opt-in via `globals: true`
- [x] `VariableService` with Platform interface for testability
- [x] Unit tests: getVariables (3 cases), filterGlobals (3 cases), formatVariables (2 cases), formatVariable (8 cases)
- [x] Foundation for `debug_variable_detail` and `debug_snapshot` variable output

**Test**: Pause at breakpoint → `debug_snapshot(include: ["variables"])` output matches PhpStorm's Variables panel. Objects show `{ClassName}`, scalars show values, superglobals hidden by default. ✅ Verified.

---

## Milestone 4d: Debug Snapshot Tool ✅

Compose the pieces from 4a-4c into the `debug_snapshot` tool.

- [x] `debug_snapshot` tool — returns full snapshot of current paused state
- [x] Snapshot composing: session info + source context + variables + stack trace
- [x] `include` parameter — filter snapshot to only requested parts (e.g. `["source", "variables"]`)
- [x] Session always included regardless of `include` (minimal overhead, always needed)
- [x] `globals` parameter — opt-in for PHP superglobals in variable output
- [x] Handle "not paused" state gracefully (session running, no session, session stopped)
- [x] Refactored `DebugTools.kt`: extracted reusable `extractSourceContext()`, `extractStackFrames()`, `extractVariables()` — shared by snapshot and variable_detail
- [x] Removed individual tools (`debug_source_context`, `debug_stack_trace`, `debug_variables`) — redundant with `debug_snapshot(include: [...])`. They were implementation scaffolding not in the ToolDesign.md spec.
- [x] Library detection on source context and stack frames (`(library)` annotation via `ProjectFileIndex.isInLibrary()`)
- [x] All ReadAction threading issues resolved (SourceContextService, StackFrameService)
- [x] Unit tests: formatSnapshot (5 cases covering full, partial, and filtered snapshots)

**Test**: Pause at a breakpoint. Call `debug_snapshot` → output matches PhpStorm's debug panel (session + source + variables + stack). Test `include: ["source", "variables"]` → no stack trace. Test `globals: true` → superglobals visible. ✅ Verified.

---

## Milestone 5: Navigation / Stepping ✅

Consolidated into two tools instead of five — `debug_step` for all stepping actions, `debug_run_to_line` for targeted execution.

- [x] Sync wait pattern: register `XDebugSessionListener` → trigger action → `CompletableFuture.get()` until `sessionPaused()` or `sessionStopped()`
- [x] `debug_step(action: "over")` — step over + return snapshot
- [x] `debug_step(action: "into")` — step into + return snapshot
- [x] `debug_step(action: "out")` — step out + return snapshot
- [x] `debug_step(action: "continue")` — resume + return snapshot (or session-ended)
- [x] `debug_run_to_line(location: "file:line")` — run to specific line via `XSourcePosition` + return snapshot
- [x] File resolution: absolute or project-relative paths, line validation
- [x] Empty `include: []` treated as "include everything" (fixed in both `debug_step` and `debug_snapshot`)
- [x] Shared helpers: `stepAndWait()`, `buildSnapshotFromResult()`, `parseSnapshotParams()`, `putSnapshotParams()`

**Design decision**: Combined over/into/out/continue into one `debug_step` tool with an `action` enum — they're identical except for the session method call. `run_to_line` kept separate because it has a fundamentally different input (location parameter). Timeout handling intentionally skipped — blocking call works, and long-running execution is not our problem.

**Test**: Pause at a breakpoint. `debug_step(action: "over")` → next line snapshot. `debug_step(action: "into")` → inside function. `debug_step(action: "out")` → back to caller. `debug_step(action: "continue")` → next breakpoint or session ended. `debug_run_to_line(location: "src/index.php:15")` → snapshot at target line. ✅ Verified.

---

## Milestone 5.5: Variable Detail Inspection ✅

Pulled forward from Milestone 6 — deep variable inspection before navigation tools.

- [x] `debug_variable_detail` — expand variables to see properties/children
- [x] Dot-path notation for nested access: `$engine`, `$engine.pattern`, `$items.0.name`
- [x] Optional path: omit to expand all top-level variables, specify to drill into specific ones
- [x] Comma-separated paths for multiple variables: `$engine, $result`
- [x] Configurable `depth` parameter (0 = flat like `debug_variables`, 1+ = expand children)
- [x] Type display in detail view: `{int} 55`, `{string} "hello"`, `{CompiledPattern}`
- [x] Tree formatting with indentation for nested structures
- [x] Flexible path resolution: handles `$` prefix mismatch between parsePath and Xdebug names
- [x] Xdebug inherited property matching: `parent` matches `*TypedPatternEngine\Nodes\AstNode*parent` (the `*ClassName*propertyName` convention Xdebug uses for inherited private/protected properties)
- [x] Ambiguity handling: when a short name matches multiple `*ClassName*prop` entries, returns error with options — agent uses full name to disambiguate
- [x] Circular reference detection: tracks object types (FQCNs) along the ancestor chain during depth expansion. When a child's type matches an ancestor, marks it `(circular reference)` instead of expanding. Arrays excluded (they legitimately nest). Prevents infinite expansion of parent-child back-references (e.g., `child.parent → parentNode → children → ...`).
- [x] Explicit path navigation bypasses cycle detection — agent can drill into circular references intentionally via full path (e.g., `$ast.children.0.parent.children.0.text`)
- [x] `globals` filter applies when no path specified
- [x] `VariablePathException` with available-variable hints on path-not-found
- [x] Recursive `expandValue()` with depth control and ancestor type tracking
- [x] `parsePath()` companion function (strips `$`, splits on `.`)
- [x] `isObjectType()` — classifies types as object (FQCN) vs scalar/array for cycle tracking
- [x] `matchesSegment()` — flexible name matching (exact, `$`-prefixed, `*Class*prop` suffix)
- [x] Unit tests: parsePath (6 cases), matchesSegment (8 cases), isObjectType (2 tests), getVariableDetail (6 cases + error + ambiguity + circular bypass), circularCases (2 cases), circularArrayCases (1 case), formatVariableDetail (7 cases incl. circular), getAllVariableDetails via buildDetailService

**Design decision — circular reference detection**: XValue doesn't expose object identity (no Xdebug handle accessible through the API), and each `computeChildren` call creates fresh XValue wrappers, so Java object identity doesn't work. Instead we track object types (FQCNs) in the ancestor chain — if a type reappears, it's likely a back-reference. Trade-off: false positives for legitimately nested same-type objects (e.g., nested SequenceNode), but this is rare and the agent can bypass via explicit paths. We considered using Xdebug object IDs but decided against it — they'd require accessing PhpStorm's internal PHP debug classes via reflection (fragile, breaks on upgrades), and displaying them in output would add noise for no actionable benefit.

**Test**: Pause at breakpoint. `debug_variable_detail` with no path → shows all variables expanded. `$engine` → shows engine's properties. `$engine.ast` → drills into nested object. `$foo` (scalar) → just shows value. Comma-separated paths work. Type annotations visible. Circular references show `(circular reference)` instead of infinite expansion. Explicit paths through cycles work. ✅ Verified.

---

## Milestone 6: Deep Inspection

- [x] `debug_inspect_frame` — switch to a different stack frame (via `setCurrentStackFrame`), return snapshot at that scope. Reuses `debug_snapshot` code path — no duplication.
- [x] `debug_evaluate` — evaluate PHP expression in current debug scope. Returns position context + result. Supports `depth` parameter (default 1) for expanding object/array results. Errors show raw Xdebug message (no wrapper noise). Reuses `expandValue` from variable detail, `formatSourceHeader` extracted from `formatSourceContext`. Not read-only (`openWorldHint = true`) — expressions can have side effects including variable modification (`$bar = 'new value'`).
- [x] ~~`debug_set_value`~~ — dropped. `debug_evaluate` already handles assignments (`$bar = 'test'`, `$this->name = 'new'`). The `XValueModifier` API had callback/timeout issues, and a separate tool adds complexity with zero capability gain.

**Test**: Pause at a breakpoint. Call `debug_inspect_frame(2)` → verify variables match that frame's scope. Call `debug_evaluate("count($items)")` → verify result. Call `debug_evaluate("$bar = 'test'")` → verify variable changed. ✅ Verified.

---

## Future (Post-v1)

Not in scope now, but where this goes:

- Exception breakpoints
- Watch expressions (persistent across steps)
- Start/restart debug sessions from agent
- Mute/unmute all breakpoints
- Smart step into (choose which function to enter)
- **Beyond debugging**: refactoring tools (rename, extract method), code navigation (find usages, go to definition), run configurations, test runner integration
