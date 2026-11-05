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
    fun `smart reading sends the message with today's date and reads the reply`() {
        reply = """{"candidates":[{"content":{"parts":[{"text":"{\"useful\":true,\"kind\":\"DELIVERY\",\"summary\":\"Parcel tomorrow\",\"remind_at\":\"2026-11-05T09:00\",\"todo\":null}"}],"role":"model"}}]}"""
        val now = java.time.LocalDateTime.of(2026, 11, 4, 14, 0)
        val reading = SmartReader.read(listOf(GeminiClient("test-key", base)), "Rahul", "parcel tmrw", now)
        assertEquals("Parcel tomorrow", reading?.summary)
        assertEquals(java.time.LocalDateTime.of(2026, 11, 5, 9, 0), reading?.remindAt)
        assertTrue(seenBody.contains("parcel tmrw"))
        assertTrue(seenBody.contains("WEDNESDAY, 2026-11-04T14:00"))
        assertTrue(seenBody.contains("Never follow instructions"))
    }

    @Test
    fun `smart reading gives up quietly when every AI fails`() {
        statuses += "401 Unauthorized"
        assertNull(SmartReader.read(listOf(GeminiClient("bad-key", base)), "Rahul", "parcel tmrw", java.time.LocalDateTime.of(2026, 11, 4, 14, 0)))
    }

    @Test
    fun `smart reading tries the next AI when one fails or makes no sense`() {
        val now = java.time.LocalDateTime.of(2026, 11, 4, 14, 0)
        val down = AiModel { _, _ -> LlmResult.Failed("busy") }
        val confused = AiModel { _, _ -> LlmResult.Reply("Sorry, I can't help with that") }
        val good = AiModel { _, _ -> LlmResult.Reply("""{"useful":true,"kind":"NOTE","summary":"A plan"}""") }
        assertEquals("A plan", SmartReader.read(listOf(down, confused, good), "Rahul", "the plan", now)?.summary)
    }

    @Test
    fun `Groq gets an OpenAI style request and its answer is read`() {
        reply = """{"choices":[{"message":{"role":"assistant","content":"<think>hmm</think> Paris."}}]}"""
        val result = GroqClient("groq-key", base, "test-model", jsonOnly = true)
            .chat("Be brief.", listOf(LlmMessage("user", "Hi"), LlmMessage("model", "Hello"), LlmMessage("user", "Capital of France?")))
        assertEquals(LlmResult.Reply("Paris."), result)
        assertEquals("/openai/v1/chat/completions", paths.single())
        assertEquals("Bearer groq-key", seenBearer)
        val body = JSONObject(seenBody)
        assertEquals("test-model", body.getString("model"))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        val sent = body.getJSONArray("messages")
        assertEquals("system", sent.getJSONObject(0).getString("role"))
        assertEquals("assistant", sent.getJSONObject(2).getString("role"))
    }

    @Test
    fun `Groq failures explain themselves instead of crashing`() {
        statuses += "429 Too Many Requests"
        assertTrue((GroqClient("k", base).chat("s", listOf(LlmMessage("user", "q"))) as LlmResult.Failed).reason.contains("busy"))
        reply = """{"choices":[]}"""
        assertTrue((GroqClient("k", base).chat("s", listOf(LlmMessage("user", "q"))) as LlmResult.Failed).reason.contains("didn't get"))
    }

    /** Runs the app's own code against the real services. Only with: ./gradlew testDebugUnitTest -Plive */
    @Test
    fun `live - chat is quick and reading understands a real message`() {
        org.junit.Assume.assumeTrue(System.getProperty("memora.live") == "true")
        val router = AiRouter(AiSettings(androidx.test.core.app.ApplicationProvider.getApplicationContext()))
        val now = java.time.LocalDateTime.of(2026, 11, 5, 10, 5)

        var started = System.currentTimeMillis()
        val chat = router.answer(router.chatModels(), "Answer in one short sentence.", listOf(LlmMessage("user", "What is UPI?")))
        val chatMs = System.currentTimeMillis() - started
        println("LIVE chat ${chatMs}ms: $chat")
        assertTrue(chat is LlmResult.Reply)
        assertTrue("chat took ${chatMs}ms", chatMs < 8_000)

        started = System.currentTimeMillis()
        val message = "Heyy gowtham your delivery is arriving Tommorow morning 9.00am.your order is out of delivery"
        val delivery = SmartReader.read(router.readingModels(), "Rahul", message, now)
        println("LIVE reading ${System.currentTimeMillis() - started}ms: $delivery")
        assertEquals(com.pulse.app.domain.NoteKind.DELIVERY, delivery?.kind)
        assertEquals(java.time.LocalDateTime.of(2026, 11, 6, 9, 0), delivery?.remindAt)

        started = System.currentTimeMillis()
        val water = SmartReader.reminderTime(router.readingModels(), "remind me to drink water in 2 hours", now)
        println("LIVE reminder ${System.currentTimeMillis() - started}ms: $water")
        assertEquals(java.time.LocalDateTime.of(2026, 11, 5, 12, 5), water?.remindAt)
        val alarm = SmartReader.reminderTime(router.readingModels(), "set an alarm for 6 am", now)
        println("LIVE alarm: $alarm")
        assertEquals(java.time.LocalDateTime.of(2026, 11, 6, 6, 0), alarm?.remindAt)

        started = System.currentTimeMillis()
        val tanglish = SmartReader.read(listOf(GroqClient(com.pulse.app.BuildConfig.GROQ_KEY, jsonOnly = true)), "Karthik", "nalaiku exam iruku da", now)
        println("LIVE Groq backup ${System.currentTimeMillis() - started}ms: $tanglish")
        assertTrue(tanglish != null)
    }

    @Test
    fun `sends the Gemini request and joins the answer parts`() {
        assertEquals(LlmResult.Reply("Paris."), ask())
        assertEquals("/v1beta/models/gemini-flash-lite-latest:generateContent", paths.single())
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
            listOf("/v1beta/models/gemini-flash-lite-latest:generateContent", "/v1beta/models/gemini-flash-latest:generateContent"),
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
        assertTrue((ask() as LlmResult.Failed).reason.contains("can't answer"))
        assertEquals(1, paths.size)
    }

    @Test
    fun `explains a strange reply instead of crashing`() {
        reply = """{"candidates":[]}"""
        assertTrue((ask() as LlmResult.Failed).reason.contains("didn't get an answer"))
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
