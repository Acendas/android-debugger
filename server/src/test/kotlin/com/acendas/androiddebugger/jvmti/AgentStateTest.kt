package com.acendas.androiddebugger.jvmti

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Per Phase F.1.1. Pins the crash-marker file parser shape — the C++ agent
 * writes line-delimited `key=value` records; the Kotlin side must recover them
 * across plugin upgrades since the agent in the app process can outlast our
 * Kotlin restart.
 */
class AgentStateTest {

    @Test
    fun parse_full_record() {
        val raw = """
            signal=SIGSEGV
            agent_version=1.4.0
            si_addr=0xdeadbeef
            pc=0x7f1234abcd
            pid=14210
            tid=14744
            last_rpc_method=ping
            when_unix=1715472510
        """.trimIndent()
        val r = AgentCrashRecord.parse(raw)
        assertEquals("SIGSEGV", r.signal)
        assertEquals("1.4.0", r.agentVersion)
        assertEquals("0xdeadbeef", r.siAddr)
        assertEquals("0x7f1234abcd", r.pc)
        assertEquals(14210, r.pid)
        assertEquals(14744, r.tid)
        assertEquals("ping", r.lastRpcMethod)
        assertEquals(1715472510L, r.whenUnix)
    }

    @Test
    fun parse_partial_record_leaves_missing_fields_null() {
        val raw = "signal=SIGABRT\npc=0x1234\n"
        val r = AgentCrashRecord.parse(raw)
        assertEquals("SIGABRT", r.signal)
        assertEquals("0x1234", r.pc)
        assertNull(r.lastRpcMethod)
        assertNull(r.pid)
        assertNull(r.tid)
    }

    @Test
    fun parse_ignores_malformed_lines() {
        // Line with no `=` should be skipped (not crash).
        val raw = "signal=SIGBUS\ngarbage line\n=no-key\npid=999\n"
        val r = AgentCrashRecord.parse(raw)
        assertEquals("SIGBUS", r.signal)
        assertEquals(999, r.pid)
    }

    @Test
    fun parse_pid_non_numeric_returns_null_not_zero() {
        val raw = "pid=not-a-number\n"
        val r = AgentCrashRecord.parse(raw)
        assertNull(r.pid)
    }
}
