package com.cone.agent.mcp

import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class HttpMcpClientTest {
    @Test fun initializeDiscoverAndCallWithSessionAndSseResponse() = runTest {
        val requests = CopyOnWriteArrayList<JsonObject>()
        val sessions = CopyOnWriteArrayList<String>()
        val auth = CopyOnWriteArrayList<String>()
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val worker = Executors.newSingleThreadExecutor()
        val serving = worker.submit {
          repeat(4) {
           server.accept().use { socket ->
            socket.soTimeout = 5000
            val reader = socket.getInputStream().bufferedReader()
            reader.readLine()
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: error("Missing HTTP headers")
                if (line.isEmpty()) break
                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            }
            val chars = CharArray(headers.getValue("content-length").toInt())
            var offset = 0
            while (offset < chars.size) {
                val count = reader.read(chars, offset, chars.size - offset)
                check(count > 0)
                offset += count
            }
            val body = String(chars)
            val request = Json.parseToJsonElement(body).jsonObject
            requests.add(request)
            sessions.add(headers["mcp-session-id"].orEmpty())
            auth.add(headers["authorization"].orEmpty())
            val method = request["method"]?.jsonPrimitive?.content
            if (method == "notifications/initialized") {
                socket.getOutputStream().write("HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            } else {
                val result = when (method) {
                    "initialize" -> {
                        """{"protocolVersion":"2025-03-26","capabilities":{"tools":{}},"serverInfo":{"name":"local-test","version":"1"}}"""
                    }
                    "tools/list" -> """{"tools":[{"name":"echo","description":"Echo text","inputSchema":{"type":"object"}}]}"""
                    else -> """{"content":[{"type":"text","text":"hello"}]}"""
                }
                val response = """{"jsonrpc":"2.0","id":${request["id"]},"result":$result}"""
                val sse = method == "tools/call"
                val text = if (sse) ": keepalive\n\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\ndata: $response\n\n" else response
                val bytes = text.toByteArray()
                val type = if (sse) "text/event-stream" else "application/json"
                val session = if (method == "initialize") "Mcp-Session-Id: test-session\r\n" else ""
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\n${session}Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                socket.getOutputStream().write(bytes)
            }
           }
          }
        }
        try {
            val client = HttpMcpClient(
                McpServerConfig(name = "test", url = "http://127.0.0.1:${server.localPort}/mcp", headers = mapOf("Authorization" to "Bearer test-only")),
                OkHttpClient(), Json { ignoreUnknownKeys = true },
            )
            client.initialize().getOrThrow()
            assertEquals("echo", client.listTools().getOrThrow().single().name)
            assertEquals("hello", client.callTool("echo", buildJsonObject { put("text", "hello") }).getOrThrow())
            serving.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("initialize", "notifications/initialized", "tools/list", "tools/call"), requests.map { it["method"]!!.jsonPrimitive.content })
            assertTrue(requests.all { it["jsonrpc"]?.jsonPrimitive?.content == "2.0" })
            assertEquals("2025-03-26", requests[0]["params"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
            assertEquals(listOf("", "test-session", "test-session", "test-session"), sessions)
            assertTrue(auth.all { it == "Bearer test-only" })
            assertEquals("hello", requests.last()["params"]!!.jsonObject["arguments"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        } finally { server.close(); worker.shutdownNow() }
    }

    @Test fun unsupportedTransportsAndMalformedHeadersAreRejected() {
        assertTrue(McpServerConfig(name = "test", url = "https://example.com/mcp", transport = McpTransport.STDIO).validate().isFailure)
        assertTrue(McpServerConfig(name = "test", url = "https://example.com/sse", transport = McpTransport.SSE).validate().isFailure)
        assertTrue(McpServerConfig(name = "test", url = "https://").validate().isFailure)
        assertTrue(McpServerConfig(name = "test", url = "https://example.com/mcp", headers = mapOf("Authorization" to "a\nb")).validate().isFailure)
    }
}
