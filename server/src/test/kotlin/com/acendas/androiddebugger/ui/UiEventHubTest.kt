package com.acendas.androiddebugger.ui

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiEventHubTest {

    private val dir = Files.createTempDirectory("ui-hub-test")
    private val hub = UiEventHub(dir.resolve("events.ndjson"))

    @AfterTest
    fun cleanup() {
        hub.close()
        dir.toFile().deleteRecursively()
    }

    private fun ev(kind: String) = buildJsonObject { put("kind", kind) }
    private fun kindOf(o: kotlinx.serialization.json.JsonObject) = (o["kind"] as JsonPrimitive).content

    @Test
    fun `publish stamps seq and appends ndjson lines`() {
        hub.publish(ev("a"))
        hub.publish(ev("b"))
        val lines = Files.readAllLines(hub.logFile)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"seq\":1") && lines[1].contains("\"seq\":2"))
        assertEquals(2, hub.seq)
    }

    @Test
    fun `event published before the wait call is still found after the cursor`() = runBlocking<Unit> {
        val cursor = hub.seq // e.g. taken by ui_tap before injecting
        hub.publish(ev("screen_changed")) // lands before ui_wait is called
        val got = hub.awaitEvent(cursor, 100) { kindOf(it) == "screen_changed" }
        assertNotNull(got)
    }

    @Test
    fun `events at or before the cursor are ignored`() = runBlocking<Unit> {
        hub.publish(ev("screen_changed"))
        val cursor = hub.seq
        assertNull(hub.awaitEvent(cursor, 100) { kindOf(it) == "screen_changed" })
    }

    @Test
    fun `wait wakes on a later publish`() = runBlocking<Unit> {
        val cursor = hub.seq
        val waiter = async { hub.awaitEvent(cursor, 2_000) { kindOf(it) == "target" } }
        delay(50)
        hub.publish(ev("other"))
        delay(50)
        hub.publish(ev("target"))
        assertEquals("target", kindOf(assertNotNull(waiter.await())))
    }

    @Test
    fun `ring is bounded`() {
        val small = UiEventHub(dir.resolve("small.ndjson"), ringSize = 3)
        repeat(10) { small.publish(ev("x$it")) }
        assertEquals(listOf("x7", "x8", "x9"), small.recent(10).map(::kindOf))
        small.close()
    }
}
