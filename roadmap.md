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
- [x] `breakpoint_remove` — remove by ID(s), file:line(s) (comma-separated), or omit to remove all
- [x] Flexible file paths: accepts absolute or project-relative paths, returns project-relative
- [x] Flexible IDs: numeric timestamp ID or `file:line` reference (e.g. `src/index.php:5`)

**Test**: Breakpoints added via MCP Inspector appear in PhpStorm gutter. List matches Breakpoints dialog. Remove clears them. ✅ Verified.

---

## Milestone 3: Session Management ✅

- [x] `session_list` — list active debug sessions with status and active flag
- [x] `session_stop` — stop a specific session or all sessions
- [x] Active session detection (which session is currently focused in the UI)

**Test**: Manually start 1-2 debug sessions in PhpStorm. Call `session_list` → verify output matches the debug tabs. Call `session_stop` → verify session ends.

---

## Milestone 4: Debug Snapshot

Build the snapshot response format before adding navigation tools — this is the foundation all other tools return.

- [ ] Snapshot data model (session, position, source, variables, stacktrace)
- [ ] Source context extraction: scope-aware (detect method boundaries, show method or ±10 lines)
- [ ] Variable preview generation (scalars → value, objects → class name, arrays → count)
- [ ] Stacktrace extraction from XSuspendContext
- [ ] `debug_snapshot` tool — returns full snapshot of current state
- [ ] `include` parameter — filter snapshot to only requested parts

**Test**: Manually pause at a breakpoint. Call `debug_snapshot` → verify the response matches what you see in the PhpStorm debug panel (same variables, same stack, same code location). Test `include: ["variables"]` returns only variables.

---

## Milestone 5: Navigation / Stepping

Each tool triggers an action and returns a snapshot when the debugger pauses again.

- [ ] `debug_step_over` — step over + return snapshot
- [ ] `debug_step_into` — step into + return snapshot
- [ ] `debug_step_out` — step out + return snapshot
- [ ] `debug_continue` — resume + return snapshot (or session-ended)
- [ ] `debug_run_to_line` — run to specific line + return snapshot
- [ ] Async wait pattern: trigger action → listen for pause → return result

**Test**: Pause at a breakpoint. Call `debug_step_over` → verify response shows the next line. Call `debug_step_into` on a function call → verify you're inside the function. Call `debug_continue` → verify you hit the next breakpoint or session ends.

---

## Milestone 6: Deep Inspection

- [ ] `debug_inspect_frame` — switch to a different stack frame, return snapshot at that scope
- [ ] `debug_variable_detail` — expand nested variables by path (e.g. `$request.headers`)
- [ ] `debug_evaluate` — evaluate PHP expression in current context
- [ ] `debug_set_value` — modify a variable at runtime

**Test**: Pause at a breakpoint. Call `debug_inspect_frame(2)` → verify variables match that frame's scope. Call `debug_variable_detail("$request")` → verify children match the Variables panel. Call `debug_evaluate("count($items)")` → verify result. Call `debug_set_value("$count", "99")` → verify variable changed in PhpStorm.

---

## Milestone 7: Integration & Polish

- [ ] Error handling: graceful responses for no session, session running (not paused), invalid paths
- [ ] Timeout handling for navigation tools (what if `debug_continue` never hits a breakpoint?)
- [ ] Multi-session: verify all tools work correctly with 2+ concurrent sessions
- [ ] Edge cases: very large stack traces, deeply nested objects, long string values
- [ ] Performance: snapshot generation should be fast, variable expansion should be lazy

---

## Future (Post-v1)

Not in scope now, but where this goes:

- Exception breakpoints
- Watch expressions (persistent across steps)
- Start/restart debug sessions from agent
- Mute/unmute all breakpoints
- Smart step into (choose which function to enter)
- **Beyond debugging**: refactoring tools (rename, extract method), code navigation (find usages, go to definition), run configurations, test runner integration
