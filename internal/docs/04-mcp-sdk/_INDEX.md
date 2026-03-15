# 04 — MCP SDK

Model Context Protocol SDK documentation. Our plugin IS an MCP server — it exposes PhpStorm's debugger to AI agents.

## SDK Choice

Both Kotlin and Java SDKs are available. **Kotlin SDK is the recommended choice** because:
- PhpStorm plugins are best written in Kotlin (JetBrains recommendation)
- Coroutine-first API fits well with IntelliJ's async patterns
- Better type safety and more concise code

The Java SDK docs are kept as reference since some patterns may be useful.

## Docs

- [kotlin-sdk-overview.md](kotlin-sdk-overview.md) — Architecture, server setup, tool/resource/prompt registration, transport types
- [java-sdk-overview.md](java-sdk-overview.md) — Java alternative: architecture, builder pattern, Spring integration
- [stdio-proxy-architecture.md](stdio-proxy-architecture.md) — How JetBrains exposes MCP via stdio using a two-process proxy (reverse-engineered from their mcpserver plugin)

## Full SDK Source

- Kotlin SDK: `../../reference-Repository/kotlin-sdk/`
- Java SDK: `../../reference-Repository/java-sdk/`
- Kotlin samples: `../../reference-Repository/kotlin-sdk/samples/`

## Key Concept for Our Plugin

Our plugin creates an MCP **Server** that exposes **Tools** (debug actions like step, resume, add breakpoint) and **Resources** (debug state like stack frames, variables, breakpoint list). The AI agent connects as an MCP **Client**.
