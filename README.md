<h1 align="center">MCP Hub</h1>

<p align="center">
  <img src=".github/logo.svg" width="120" height="120" alt="MCP Hub">
</p>

<p align="center">
  JetBrains IDE plugin that gives AI agents full debugger control via MCP
</p>

<p align="center">
  <a href="https://github.com/brannow/idea-mcp-hub/actions/workflows/test.yml"><img src="https://github.com/brannow/idea-mcp-hub/actions/workflows/test.yml/badge.svg" alt="Test"></a>
  <a href="https://github.com/brannow/idea-mcp-hub/releases/latest"><img src="https://img.shields.io/github/v/release/brannow/idea-mcp-hub?label=release" alt="Release"></a>
  <img src="https://img.shields.io/badge/platform-PhpStorm%202025.3+-purple" alt="Platform">
  <img src="https://img.shields.io/badge/MCP%20tools-12-blue" alt="MCP Tools">
  <a href="https://github.com/brannow/idea-mcp-hub/blob/main/LICENSE"><img src="https://img.shields.io/github/license/brannow/idea-mcp-hub" alt="License"></a>
</p>

---

## Setup

1. Install the plugin (Settings > Plugins > Install Plugin from Disk)
2. Open the **MCP Hub** tool window (bottom panel)
3. Click **Install .mcp.json** to auto-configure your MCP client
4. Click **Start Server**

Works with Claude Code, Cursor, Claude Desktop, and any other MCP client.

### Configuration

Settings > Tools > MCP Hub

| Setting | Default | What it does |
|---|---|---|
| Port | 6969 | Localhost port for the MCP server |
| Auto-start | off | Start the server when the project opens |

If the port is already in use (e.g., another PhpStorm instance), you get a notification with options to change the port or retry.

### Installation

**From GitHub Release:** Download the latest `.zip` from [Releases](https://github.com/brannow/idea-mcp-hub/releases), then in PhpStorm go to Settings > Plugins > Gear icon > Install Plugin from Disk and select the zip.

**From source:** Run `./gradlew buildPlugin`, the zip is in `build/distributions/`.

## Tools

12 tools across 4 categories.

### Breakpoints (work without a debug session)

| Tool | What it does |
|---|---|
| `breakpoint_list` | List all breakpoints with conditions, status, annotations |
| `breakpoint_add` | Add a line breakpoint with optional condition, log expression, suspend control |
| `breakpoint_update` | Modify an existing breakpoint (enable/disable, change condition, etc.) |
| `breakpoint_remove` | Remove by ID, file:line, file path, or all |

### Session management

| Tool | What it does |
|---|---|
| `session_list` | List active debug sessions with position and status |
| `session_stop` | Stop a specific session or all sessions |

### Navigation (requires paused session)

| Tool | What it does |
|---|---|
| `debug_step` | Step over / into / out / continue. Returns a snapshot after pausing. |
| `debug_run_to_line` | Run to a specific file:line. Returns a snapshot. |

### Inspection (requires paused session)

| Tool | What it does |
|---|---|
| `debug_snapshot` | Get current state without changing anything |
| `debug_variable_detail` | Expand variables to any depth with circular reference detection |
| `debug_inspect_frame` | Switch to a different stack frame and inspect its scope |
| `debug_evaluate` | Evaluate PHP expressions in debug context, including side effects |

## How it works

You start debug sessions in PhpStorm as usual. The agent connects via MCP and interacts with those sessions: setting breakpoints, stepping through code, inspecting state. You both see the same debug session.

```
Human: starts debug session, hits breakpoint
Agent:  debug_snapshot → sees source, variables, stack
Agent:  debug_step(action: "over") → next line, new snapshot
Agent:  debug_variable_detail(path: "$request.headers") → expands nested object
Agent:  debug_evaluate(expression: "count($items)") → evaluates in current scope
```

## The Debug Snapshot

Most tools return a snapshot. It mirrors what a human sees when paused at a breakpoint: session info, source context, variables, and call stack in one response.

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

Snapshots are customizable. Pass `include: ["source", "variables"]` to skip the stack trace. Useful when stepping through 10 lines and you don't need the full stack every time.

## Design decisions

**Natural language, not JSON.** Tools respond with readable text, not structured data. The agent reasons about it directly, no parsing needed.

**Self-contained responses.** Every error includes enough context to self-correct. Not found? Here are all breakpoints in the project. Ambiguous? Here are the options with IDs. The agent never needs a follow-up call just to orient itself.

**Input matches output.** If a tool outputs `src/index.php:15`, another tool accepts `src/index.php:15` as input. No reformatting between calls.

**Tools, not wrappers.** Each tool is designed like an application for the agent, not a thin API wrapper. The agent doesn't need to know how XDebugSession or XBreakpointManager work internally.

## Build from source

```bash
./gradlew build          # compile + test
./gradlew runIde         # launch sandboxed IDE with plugin
./gradlew buildPlugin    # build distributable zip
```

Requires JDK 21.