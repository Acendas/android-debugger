package com.acendas.androiddebugger.hotswap

import com.acendas.androiddebugger.ErrorCode
import com.acendas.androiddebugger.ToolError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verify d8's single-class dexing path. We don't pin a byte-for-byte hash because
 * r8 8.x output evolves subtly across minor bumps (debug-line-number tables, e.g.);
 * instead we assert the structural invariants that matter: non-empty output,
 * recognisable dex magic header, and that dex bytes change when the input class
 * changes.
 */
class DexerTest {

    private val dexer = Dexer(apiLevel = 26)

    @Test
    fun produces_dex_with_correct_magic_header() {
        val klass = TestClassFixture.simpleClass("com/example/Foo")
        val dex = dexer.dexSingleClass(klass, "com.example.Foo")
        assertTrue(dex.size > 50, "dex too small (${dex.size} bytes) — probably empty payload")
        // DEX magic: 'd', 'e', 'x', '\n', then a 3-digit version, then '\0'.
        // d8 produces dex-035 / 037 / 038 depending on min-api; all start "dex\n0".
        assertEquals('d'.code.toByte(), dex[0])
        assertEquals('e'.code.toByte(), dex[1])
        assertEquals('x'.code.toByte(), dex[2])
        assertEquals('\n'.code.toByte(), dex[3])
        assertEquals('0'.code.toByte(), dex[4])
    }

    @Test
    fun different_input_produces_different_dex() {
        val a = dexer.dexSingleClass(
            TestClassFixture.simpleClass("com/example/Foo", methodReturn = 0),
            "com.example.Foo",
        )
        val b = dexer.dexSingleClass(
            TestClassFixture.simpleClass("com/example/Foo", methodReturn = 42),
            "com.example.Foo",
        )
        assertTrue(!a.contentEquals(b), "expected different dex bytes for different method body")
    }

    @Test
    fun same_input_produces_byte_identical_dex() {
        val klass = TestClassFixture.simpleClass("com/example/Foo", methodReturn = 7)
        val a = dexer.dexSingleClass(klass, "com.example.Foo")
        val b = dexer.dexSingleClass(klass, "com.example.Foo")
        assertTrue(a.contentEquals(b), "expected byte-identical dex for identical input (r8 must be deterministic)")
    }

    @Test
    fun rejects_garbage_bytes_with_invalid_target() {
        val garbage = ByteArray(64) { it.toByte() }
        val err = assertFailsWith<ToolError> {
            dexer.dexSingleClass(garbage, "com.example.Garbage")
        }
        assertEquals(ErrorCode.InvalidTarget, err.errorCode)
        assertTrue(
            (err.message ?: "").contains("Failed to dex") || (err.message ?: "").contains("errors"),
            "expected dex-failure message, got: ${err.message}",
        )
    }

    @Test
    fun api_level_validation() {
        assertFailsWith<IllegalArgumentException> { Dexer(apiLevel = 0) }
        assertFailsWith<IllegalArgumentException> { Dexer(apiLevel = -5) }
        assertFailsWith<IllegalArgumentException> { Dexer(apiLevel = 100) }
    }
}
