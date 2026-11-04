package com.pulse.app.data

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

/** A real local socket answers in the shape Gemini returned in a live test, so the full request runs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LlmClientTest {
    private lateinit var server: ServerSocket
    private val statuses = Collections.synchronizedList(mutableListOf<String>())
    private val paths = Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var reply = """{"candidates":[{"content":{"parts":[{"text":" Paris"},{"text":"."}],"role":"model"}}]}"""
    @Volatile private var seenKey: String? = null
    @Volatile private var seenBearer: String? = null
    @Volatile private var seenBody: String = ""

    private val base get() = "http://127.0.0.1:${server.localPort}"

    @Before
    fun start() {
        server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            try {
                while (true) server.accept().use { socket -> answer(socket) }
            } catch (_: IOException) {
                // The test closed the server.
            }
        }
    }

    private fun answer(socket: java.net.Socket) {
        val input = socket.getInputStream().bufferedReader()
        paths += input.readLine().split(" ")[1]
        var length = 0
        while (true) {
            val line = input.readLine()
            if (line.isNullOrEmpty()) break
            if (line.startsWith("X-goog-api-key:", ignoreCase = true)) seenKey = line.substringAfter(":").trim()
            if (line.startsWith("Authorization:", ignoreCase = true)) seenBearer = line.substringAfter(":").trim()
            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(":").trim().toInt()
        }
        val body = CharArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        seenBody = String(body, 0, read)
        val status = if (statuses.isEmpty()) "200 OK" else statuses.removeAt(0)
        val bytes = reply.toByteArray()
        val output = socket.getOutputStream()
        output.write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }

    @After
    fun stop() = server.close()

    private fun ask() = GeminiClient("test-key", base).chat("Be brief.", listOf(LlmMessage("user", "Capital of France?")))

    @Test
    fun `sends the Gemini request and joins the answer parts`() {
        assertEquals(LlmResult.Reply("Paris."), ask())
        assertEquals("/v1beta/models/gemini-flash-latest:generateContent", paths.single())
        assertEquals("test-key", seenKey)
        val body = JSONObject(seenBody)
        assertEquals("Be brief.", body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        val first = body.getJSONArray("contents").getJSONObject(0)
        assertEquals("user", first.getString("role"))
        assertEquals("Capital of France?", first.getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test
    fun `an overloaded model falls back to the lighter one`() {
        statuses += "503 Service Unavailable"
        assertEquals(LlmResult.Reply("Paris."), ask())
        assertEquals(
            listOf("/v1beta/models/gemini-flash-latest:generateContent", "/v1beta/models/gemini-flash-lite-latest:generateContent"),
            paths.toList(),
        )
    }

    @Test
    fun `explains when every model is busy`() {
        statuses += listOf("429 Too Many Requests", "503 Service Unavailable")
        assertTrue((ask() as LlmResult.Failed).reason.contains("busy"))
    }

    @Test
    fun `explains a wrong key without trying again`() {
        statuses += "403 Forbidden"
        assertTrue((ask() as LlmResult.Failed).reason.contains("refused the key"))
        assertEquals(1, paths.size)
    }

    @Test
    fun `explains a strange reply instead of crashing`() {
        reply = """{"candidates":[]}"""
        assertTrue((ask() as LlmResult.Failed).reason.contains("empty answer"))
    }

    @Test
    fun `explains when there is no connection`() {
        val closed = base
        server.close()
        val result = GeminiClient("k", closed).chat("s", listOf(LlmMessage("user", "hi")))
        assertTrue((result as LlmResult.Failed).reason.contains("can't reach"))
    }

    @Test
    fun `Resend gets the documented request`() {
        assertNull(ResendClient("re_test", base).send("me@example.com", "Hi", "Body"))
        assertEquals("/emails", paths.single())
        assertEquals("Bearer re_test", seenBearer)
        val body = JSONObject(seenBody)
        assertEquals("me@example.com", body.getJSONArray("to").getString(0))
        assertEquals("Hi", body.getString("subject"))
        assertEquals("Body", body.getString("text"))
    }

    @Test
    fun `Resend explains a refusal`() {
        statuses += "403 Forbidden"
        assertTrue(ResendClient("bad", base).send("me@example.com", "Hi", "Body")!!.contains("refused"))
    }
}
