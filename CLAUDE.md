# PhpStorm MCP Plugin

## What This Is

A PhpStorm plugin that acts as an MCP (Model Context Protocol) server, exposing the IDE's debugging features to AI agents. The agent can set breakpoints, step through code, inspect variables, evaluate expressions — the same workflow a human developer uses with xdebug.

## Project Structure

```
phpstorm-mcp/
├── src/main/kotlin/com/github/brannow/phpstormmcp/     # Plugin source (Kotlin)
├── src/main/resources/META-INF/plugin.xml              # Plugin configuration
├── roadmap.md                                          # Project Roadmap (todo list)
├── build.gradle.kts                                    # Gradle build (IntelliJ Platform Gradle Plugin 2.x)
├── gradle.properties                                   # Platform target: PhpStorm 2025.3
├── internal/                                           # Documentation & reference (not shipped with plugin)
│   ├── INDEX.md                                        # ** START HERE ** — master documentation index
│   ├── docs/                                           # Organized documentation
│   │   ├── 03-debugger-api/                            # XDebugger framework docs (primary focus)
│   │   ├── 04-mcp-sdk/                                 # MCP Kotlin/Java SDK overviews
│   │   └── ...                                         # See INDEX.md for full list
│   ├── tools/                                          # Tool design specifications
│   │   └── ToolDesign.md                               # Final tool specs (14 tools, snapshot concept)
│   └── reference-Repository/                           # Cloned reference repos
│       ├── intellij-community/                         # IntelliJ Platform source (xdebugger-api, xdebugger-impl)
│       ├── intellij-sdk-code-samples/                  # Official plugin examples
│       ├── kotlin-sdk/                                 # MCP Kotlin SDK (our primary SDK)
│       └── java-sdk/                                   # MCP Java SDK (reference only)
```

## Key Documentation

| Need to understand...                                 | Read this                                              |
|-------------------------------------------------------|--------------------------------------------------------|
| Documentation overview & navigation                   | `internal/INDEX.md`                                    |
| Project Roadmap (todo list) and what we already build | `roadmap.md`                                           |
| XDebugger API (our core integration)                  | `internal/docs/03-debugger-api/_INDEX.md`              |
| MCP SDK (how we expose tools)                         | `internal/docs/04-mcp-sdk/_INDEX.md`                   |
| Tool design & specifications                          | `internal/tools/ToolDesign.md`                         |
| Tool design philosophy                                | `internal/tools/Tools.md`                              |
| Plugin structure (plugin.xml, services)               | `internal/docs/02-plugin-structure/_INDEX.md`          |
| Editor/PSI APIs (reading code context)                | `internal/docs/05-editor-and-psi/_INDEX.md`            |
| Debugger action IDs                                   | `internal/docs/03-debugger-api/debugger-action-ids.md` |
| API quick reference (all key classes)                 | `internal/docs/03-debugger-api/api-quick-reference.md` |

## Architecture Decisions

- **Language**: Kotlin (JetBrains recommendation, aligns with Kotlin MCP SDK)
- **MCP SDK**: Kotlin SDK (`internal/reference-Repository/kotlin-sdk/`)
- **Target IDE**: PhpStorm 2025.3 (build 253)
- **Plugin type**: MCP Server — the AI agent is the MCP client
- **Debug sessions**: Human starts sessions, agent interacts with them

## Core Design Concept: Debug Snapshot

Every tool that changes or inspects debug state returns a standardized **Debug Snapshot** — the same context a human sees in the debug panel:
- Session info (id, name, status, active)
- Position (file, line, method, class)
- Source code (scope-aware: shows containing method or ~10 lines around current position)
- Variables (top-level with type + value preview)
- Stacktrace (full call stack)

Snapshots are customizable via `include` parameter for token efficiency.

See `internal/tools/ToolDesign.md` for full tool specifications.

## Build & Run

```bash
./gradlew build          # Compile the plugin
./gradlew runIde         # Launch sandboxed PhpStorm with plugin installed
./gradlew buildPlugin    # Build distributable .zip
```

Requires JDK 21 (JBR recommended). IDE for development: IntelliJ IDEA Community Edition.

## Key Source Locations in Reference Repos

XDebugger API (the interfaces we call):
```
internal/reference-Repository/intellij-community/platform/xdebugger-api/src/com/intellij/xdebugger/
├── XDebuggerManager.java          # Entry point — get debug sessions
├── XDebugSession.java             # Session control (step, resume, etc.)
├── XDebugProcess.java             # Language-specific debug process
├── breakpoints/                   # Breakpoint management
├── frame/                         # Stack frames, variables (XStackFrame, XValue)
├── evaluation/                    # Expression evaluation
└── stepping/                      # Smart step into
```

MCP Kotlin SDK (how we build the server):
```
internal/reference-Repository/kotlin-sdk/
├── README.md                      # Comprehensive SDK reference
├── kotlin-sdk-server/             # Server module (our primary dependency)
├── kotlin-sdk-core/               # Protocol types, transports
└── samples/                       # Example implementations
```
