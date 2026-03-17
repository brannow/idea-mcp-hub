package com.github.brannow.phpstormmcp.server

import com.github.brannow.phpstormmcp.statusbar.McpServerState
import com.github.brannow.phpstormmcp.tools.registerBreakpointTools
import com.github.brannow.phpstormmcp.tools.registerDebugTools
import com.github.brannow.phpstormmcp.tools.registerSessionTools
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.util.collections.ConcurrentMap
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.ServerSocket

private const val MCP_SESSION_ID_HEADER = "mcp-session-id"

@Service(Service.Level.PROJECT)
class McpServerService(private val project: Project) : Disposable {

    private var server: EmbeddedServer<*, *>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var port: Int = 0
    private val transports = ConcurrentMap<String, StreamableHttpServerTransport>()

    companion object {
        const val DEFAULT_PORT = 6969

        fun getInstance(project: Project): McpServerService {
            return project.getService(McpServerService::class.java)
        }
    }

    val isRunning: Boolean
        get() = server != null

    fun start() {
        if (isRunning) return

        port = resolvePort()
        val state = McpServerState.getInstance(project)

        server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            install(ContentNegotiation) {
                json(McpJson)
            }
            install(SSE)

            routing {
                route("/mcp") {
                    sse {
                        val transport = findTransport(call) ?: return@sse
                        transport.handleRequest(this, call)
                    }

                    post {
                        val transport = getOrCreateTransport(call, state) ?: return@post
                        transport.handleRequest(null, call)
                    }

                    delete {
                        val transport = findTransport(call) ?: return@delete
                        transport.handleRequest(null, call)
                    }
                }
            }
        }

        scope.launch {
            server?.start(wait = false)
        }

        state.start(project, "HTTP :$port")
    }

    fun stop() {
        transports.clear()
        server?.stop(500, 1000)
        server = null
        port = 0
        McpServerState.getInstance(project).stop(project)
    }

    override fun dispose() {
        stop()
        scope.cancel()
    }

    private suspend fun findTransport(call: ApplicationCall): StreamableHttpServerTransport? {
        val sessionId = call.request.header(MCP_SESSION_ID_HEADER)
        if (sessionId.isNullOrEmpty()) {
            call.respond(HttpStatusCode.BadRequest, "Bad Request: No valid session ID provided")
            return null
        }
        val transport = transports[sessionId]
        if (transport == null) {
            call.respond(HttpStatusCode.NotFound, "Session not found")
        }
        return transport
    }

    private suspend fun getOrCreateTransport(
        call: ApplicationCall,
        state: McpServerState
    ): StreamableHttpServerTransport? {
        val sessionId = call.request.header(MCP_SESSION_ID_HEADER)
        if (sessionId != null) {
            val transport = transports[sessionId]
            if (transport == null) {
                call.respond(HttpStatusCode.NotFound, "Session not found")
            }
            return transport
        }

        val configuration = StreamableHttpServerTransport.Configuration(enableJsonResponse = true)
        val transport = StreamableHttpServerTransport(configuration)

        transport.setOnSessionInitialized { initializedSessionId ->
            transports[initializedSessionId] = transport
            state.clientConnected(project)
        }

        transport.setOnSessionClosed { closedSessionId ->
            transports.remove(closedSessionId)
            state.clientDisconnected(project)
        }

        val mcpServer = createMcpServer()
        mcpServer.onClose {
            transport.sessionId?.let { transports.remove(it) }
        }
        mcpServer.createSession(transport)

        return transport
    }

    private fun createMcpServer(): Server {
        return Server(
            serverInfo = Implementation(
                name = "phpstorm-mcp",
                version = "0.1.0"
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true),
                )
            )
        ).apply {
            registerBreakpointTools(project)
            registerSessionTools(project)
            registerDebugTools(project)
        }
    }

    private fun resolvePort(): Int {
        return if (isPortAvailable(DEFAULT_PORT)) {
            DEFAULT_PORT
        } else {
            ServerSocket(0).use { it.localPort }
        }
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (_: Exception) {
            false
        }
    }
}
