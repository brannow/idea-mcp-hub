# Stdio Proxy Architecture — How JetBrains Exposes MCP via stdio

## Why This Matters

Many MCP clients (Claude Desktop, Cursor, etc.) natively support **stdio** transport — they spawn a child process and communicate over stdin/stdout. But an IDE plugin runs **inside** the IDE's JVM process. There's no separate stdin/stdout to use.

JetBrains solved this with a **two-process proxy architecture**. This document explains how it works based on reverse-engineering their `mcpserver` plugin (decompiled from the shipped jar files).

---

## The Problem

```
AI Client (wants stdio)  ←→  ???  ←→  PhpStorm Plugin (runs inside IDE)
```

- The MCP server lives inside PhpStorm as a plugin service — it has access to the IDE APIs (debugger, editor, PSI, etc.)
- The MCP server can easily run an HTTP server on localhost (using Ktor CIO)
- But many AI clients expect to launch a subprocess and talk over stdin/stdout
- You can't hijack PhpStorm's stdin/stdout — it's a GUI application

## The Solution: Two-Process Bridge

```
┌─────────────────┐       stdio        ┌──────────────────┐      HTTP/SSE      ┌─────────────────┐
│                 │  ←──────────────→  │                  │  ←──────────────→  │                 │
│   AI Client     │   stdin / stdout   │  McpStdioRunner  │   SSE transport    │   PhpStorm      │
│ (Claude, etc.)  │                    │  (separate JVM)  │   localhost:port   │   MCP Plugin    │
│                 │                    │                  │                    │                 │
└─────────────────┘                    └──────────────────┘                    └─────────────────┘
      MCP Client                         Thin Proxy                              MCP Server
                                    (no IDE dependencies)                    (full IDE API access)
```

**Process 1: PhpStorm** — Runs the MCP server as a plugin service. Listens on `localhost:<port>` via Ktor CIO with SSE transport. Has full access to the IDE's APIs.

**Process 2: McpStdioRunner** — A lightweight JVM process (just the MCP SDK + Ktor client jars). Reads stdin, forwards to the IDE's SSE endpoint. Receives SSE events, writes to stdout. The AI client spawns this process.

---

## How the Stdio Runner Works

### Environment Variables

The runner is configured entirely via environment variables:

| Variable | Purpose |
|----------|---------|
| `IJ_MCP_SERVER_PORT` | **Required.** Port where PhpStorm's MCP HTTP server listens |
| `IJ_MCP_DEBUG` | Enable debug logging to stderr |
| `IJ_MCP_ALLOWED_TOOLS` | Comma-separated tool filter list |
| `IJ_MCP_SERVER_PROJECT_PATH` | Project path for scoping |
| `IJ_MCP_HEADER_*` | Any env var with this prefix gets forwarded as an HTTP header (prefix stripped) |

### Startup Sequence

```
1. Read IJ_MCP_SERVER_PORT from env → parse as int
2. If missing/invalid → print error to stderr, exit
3. Create StdioServerTransport(System.in, System.out)
4. Create HttpClient with SSE plugin
5. Collect all IJ_MCP_* env vars (except IJ_MCP_DEBUG)
6. Create SseClientTransport("http://localhost:<port>")
   → forwards IJ_MCP_HEADER_* vars as HTTP headers
7. Wire bidirectional message forwarding:
   - StdioServerTransport.onMessage → SseClientTransport.send
   - SseClientTransport.onMessage → StdioServerTransport.send
8. Wire close/error handlers for both transports
9. Start both transports
10. Wait until either transport closes
```

### Reconstructed Kotlin (based on decompiled bytecode)

```kotlin
// McpStdioRunner.kt — reconstructed from JetBrains' implementation
package com.intellij.mcpserver.stdio

const val IJ_MCP_PREFIX = "IJ_MCP_"
const val IJ_MCP_HEADER_PREFIX = "IJ_MCP_HEADER_"

// Env var names
val IJ_MCP_SERVER_PORT = "IJ_MCP_SERVER_PORT"
val IJ_MCP_ALLOWED_TOOLS = "IJ_MCP_ALLOWED_TOOLS"
val IJ_MCP_SERVER_PROJECT_PATH = "IJ_MCP_SERVER_PROJECT_PATH"
val IJ_MCP_DEBUG = "IJ_MCP_DEBUG"

val isDebugEnabled = System.getenv(IJ_MCP_DEBUG)
    ?.let { it.isNotEmpty() || it.equals("true", ignoreCase = true) } ?: false

suspend fun main() {
    val inputStream = System.`in`
    val outputStream = System.out

    // 1. Parse port from environment
    val port = System.getenv(IJ_MCP_SERVER_PORT)?.toIntOrNull()
    if (port == null || port == 0) {
        System.err.println(
            "Please specify the port of the underlying MCP IDE server " +
            "using the environment variable $IJ_MCP_SERVER_PORT"
        )
        return
    }

    if (isDebugEnabled) {
        info("Debug mode enabled")
    } else {
        info("Debug mode can be enabled by setting the $IJ_MCP_DEBUG " +
             "environment variable to any value (empty string or TRUE). " +
             "Debug messages will be printed to stderr.")
    }

    // 2. Create the stdio transport (talks to the AI client)
    val stdioServerTransport = StdioServerTransport(
        inputStream.asSource().buffered(),
        (outputStream as OutputStream).asSink().buffered()
    )

    // 3. Create an HTTP client with SSE support
    val httpClient = HttpClient {
        install(SSE)
    }

    // 4. Collect IJ_MCP_* env vars to forward as headers
    val envsToPass = System.getenv()
        .filter { (key, _) ->
            key != IJ_MCP_DEBUG && key.startsWith(IJ_MCP_PREFIX)
        }

    info("Passing the following headers to the server: " +
         envsToPass.entries.joinToString { "${it.key}=${it.value}" })

    // 5. Create the SSE client transport (talks to PhpStorm's MCP server)
    val sseClientTransport = SseClientTransport(
        client = httpClient,
        url = "http://localhost:$port"
    ) { requestBuilder ->
        // Forward IJ_MCP_HEADER_* env vars as HTTP headers
        envsToPass.forEach { (key, value) ->
            requestBuilder.header(
                key.removePrefix(IJ_MCP_HEADER_PREFIX),
                value
            )
        }
    }

    // 6. Wire bidirectional message forwarding
    sseClientTransport.onMessage { message ->
        // SSE → stdio: forward messages from IDE to AI client
        stdioServerTransport.send(message)
    }

    stdioServerTransport.onMessage { message ->
        // stdio → SSE: forward messages from AI client to IDE
        sseClientTransport.send(message)
    }

    // 7. Handle close events
    sseClientTransport.onClose {
        runBlocking { stdioServerTransport.close() }
    }

    stdioServerTransport.onClose {
        runBlocking { sseClientTransport.close() }
    }

    // 8. Handle errors
    sseClientTransport.onError { error ->
        System.err.println("Error in SSE: ${error.message}")
    }

    stdioServerTransport.onError { error ->
        System.err.println("Error in STDIO: ${error.message}")
    }

    // 9. Start both transports
    sseClientTransport.start()
    stdioServerTransport.start()
    info("Proxy transports started")

    // 10. Wait for completion
    val finished = CompletableDeferred<Unit>()
    stdioServerTransport.onClose {
        finished.complete(Unit)
    }
    info("Waiting for the transports to finish")
    finished.await()
    info("Transports finished")
}

// Logging helpers — write to stderr so they don't corrupt the stdio protocol
private fun info(message: String) {
    System.err.println("[INFO] $message")
}

private fun debug(message: String) {
    if (isDebugEnabled) {
        System.err.println("[DEBUG] $message")
    }
}

private fun error(message: String) {
    System.err.println("[ERROR] $message")
}
```

---

## How the IDE-Side Server Works

### McpServerHeadlessStarter

JetBrains also supports running PhpStorm in **headless mode** specifically for MCP. This is separate from the plugin running inside a normal PhpStorm instance.

```kotlin
// Reconstructed from decompiled bytecode
class McpServerHeadlessStarter : ModernApplicationStarter() {
    override val isHeadless = true

    override suspend fun start(args: List<String>) {
        // Expects: ["mcpServer", <port?>, <projectPath1>, <projectPath2>, ...]
        if (args.isEmpty() || args[0] != "mcpServer") {
            System.out.println("Error: Invalid command. Expected 'mcpServer' as the first argument.")
            throw RuntimeException("System.exit returned normally, while it was supposed to halt JVM.")
        }

        // Parse optional port from args
        val port = parsePort(args.drop(1))
        if (port != null) {
            McpServerSettings.Companion.getInstance().getState().mcpServerPort = port
        }

        // Parse and open project paths
        val projectPaths = parseProjectPaths(args.drop(1))
        println("Waiting for project initialization...")
        for (path in projectPaths) {
            val projectPath = Paths.get(path)
            val options = OpenProjectTask(/* isNewProject = false, ... */)
            val project = ProjectUtil.openOrImportAsync(projectPath, options)
            if (project == null) {
                println("Warning: Unable to open project at $path, skipping...")
            }
        }
    }
}
```

### McpServerService (IDE-side)

The actual MCP server service inside PhpStorm:

```kotlin
// Key observations from decompiled McpServerService:

class McpServerService(private val cs: CoroutineScope) {
    // Uses CIO engine (same as us!)
    private val server: MutableStateFlow<EmbeddedServer<CIOApplicationEngine, ...>>

    // Tracks active sessions with auth tokens
    private val activeAuthorizedSessions: ConcurrentHashMap<String, McpSessionOptions>

    // Per-session project root scoping
    private val sessionRoots: ConcurrentHashMap<String, Set<String>>

    // Atomic counter for call IDs (for telemetry/logging)
    private val callId: AtomicInteger

    fun getServerSseUrl(): String    // e.g., "http://localhost:63342/sse"
    fun getServerStreamUrl(): String // e.g., "http://localhost:63342/mcp"

    fun start() { ... }
    fun stop() { ... }

    // Session-scoped authorization with tool filtering
    suspend fun authorizedSession(
        options: McpSessionOptions,
        block: suspend (CoroutineScope, Int, String, String) -> Unit
    ) { ... }
}
```

---

## The MCP Client Configuration (.mcp.json)

This is what the AI client sees. It runs the stdio runner as a subprocess:

```json
{
  "mcpServers": {
    "phpstorm": {
      "command": "java",
      "args": [
        "-cp",
        "/path/to/mcpserver/lib/*",
        "com.intellij.mcpserver.stdio.McpStdioRunnerKt"
      ],
      "env": {
        "IJ_MCP_SERVER_PORT": "63342"
      }
    }
  }
}
```

Or via the JetBrains-provided wrapper script:

```json
{
  "mcpServers": {
    "phpstorm": {
      "command": "/path/to/phpstorm",
      "args": ["mcp-stdio"],
      "env": {
        "IJ_MCP_SERVER_PORT": "63342"
      }
    }
  }
}
```

---

## Jar Contents — What Ships in the Runner

The stdio runner is intentionally lightweight. The `lib/` directory contains:

| Jar | Purpose |
|-----|---------|
| `mcpserver.jar` | Plugin code (McpStdioRunner, McpServerService, tools, etc.) |
| `io.modelcontextprotocol.kotlin.sdk.jar` | MCP Kotlin SDK (umbrella — client + server + core) |
| `ktor-server-sse-jvm.jar` | Ktor SSE plugin (for SSE transport) |
| `io.github.oshai.kotlin.logging.jvm.jar` | Kotlin logging |
| `io.github.smiley4.schema.kenerator.*.jar` | JSON Schema generation (for tool input schemas) |
| `toml4j.jar` | TOML parser (for config files) |

Note: The runner only uses the **client** parts of the MCP SDK (SseClientTransport) and the **server** parts for stdio (StdioServerTransport). It doesn't need the full IDE classpath — just enough to proxy messages.

---

## Key Design Decisions and Trade-offs

### Why a Separate Process for stdio?

1. **stdin/stdout isolation** — The IDE is a GUI app. Capturing its stdin/stdout would interfere with its normal operation (logging, console output, subprocess management).

2. **Classpath isolation** — The stdio runner only needs the MCP SDK + Ktor client. Loading the entire IDE classpath would be slow and fragile.

3. **Lifecycle independence** — The AI client manages the stdio process lifecycle (spawn/kill). This is separate from the IDE's lifecycle. The IDE server can start/stop independently.

### Why SSE Between Runner and IDE (Not Streamable HTTP)?

The decompiled code shows the runner uses `SseClientTransport`, not the newer Streamable HTTP. This is likely because:

1. SSE was the standard MCP HTTP transport when the plugin was first built
2. SSE has a simpler client implementation (just EventSource + POST)
3. Backward compatibility with older SDK versions

The newer Streamable HTTP transport would also work and is the current MCP spec recommendation.

### Why Not Just Use HTTP Directly?

You can! If your MCP client supports HTTP/Streamable HTTP transport, you don't need the stdio proxy at all. Just point it at the IDE's HTTP endpoint:

```
http://localhost:<port>/mcp
```

The stdio proxy exists solely for clients that only support stdio transport.

---

## Relevance to Our Plugin

Our plugin currently uses **Streamable HTTP on a fixed port (6969)**. This works directly with any MCP client that supports HTTP transport (e.g., MCP Inspector, Claude with HTTP config).

If we ever need stdio support, the approach would be:

1. **Ship a small runner jar** alongside the plugin (or as a separate download)
2. **The runner reads our port** from an env var (e.g., `PHPSTORM_MCP_PORT=6969`)
3. **Bridges stdio ↔ HTTP** using the MCP SDK's `StdioServerTransport` + HTTP client transport
4. **Users configure their `.mcp.json`** to run the runner jar

This is a post-v1 concern — right now HTTP works fine for our use case.

---

## Reference Files

All decompiled from: `/Users/b.rannow/Downloads/mcpserver/lib/mcpserver.jar`

| Class | Role |
|-------|------|
| `com.intellij.mcpserver.stdio.McpStdioRunnerKt` | The stdio proxy (main entry point) |
| `com.intellij.mcpserver.stdio.ClassPathMarker` | Marker class for classpath detection |
| `com.intellij.mcpserver.McpStdioRunnerClasspath` | Lists all classes needed on the runner's classpath |
| `com.intellij.mcpserver.McpServerHeadlessStarter` | Headless IDE mode for MCP (extends ModernApplicationStarter) |
| `com.intellij.mcpserver.impl.McpServerService` | The actual MCP HTTP server running inside the IDE |
