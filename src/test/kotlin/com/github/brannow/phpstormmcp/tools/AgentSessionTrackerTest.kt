package com.github.brannow.phpstormmcp.tools

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AgentSessionTrackerTest {

    @BeforeEach
    fun reset() {
        AgentSessionTracker.clear()
    }

    // --- checkSessionSwitch ---

    @Test
    fun `no tracking yet - no error`() {
        val result = AgentSessionTracker.checkSessionSwitch("111", "index.php") { "terminated" }
        assertNull(result)
    }

    @Test
    fun `same session - no error`() {
        AgentSessionTracker.trackById("111")
        val result = AgentSessionTracker.checkSessionSwitch("111", "index.php") { "terminated" }
        assertNull(result)
    }

    @Test
    fun `session switched - previous terminated`() {
        AgentSessionTracker.trackById("111")
        val result = AgentSessionTracker.checkSessionSwitch("222", "test.php") { "terminated" }
        assertNotNull(result)
        assertTrue(result!!.contains("Active session changed unexpectedly"))
        assertTrue(result.contains("#111 (terminated)"))
        assertTrue(result.contains("#222 \"test.php\""))
        assertTrue(result.contains("session_activate"))
    }

    @Test
    fun `session switched - previous paused`() {
        AgentSessionTracker.trackById("111")
        val result = AgentSessionTracker.checkSessionSwitch("222", "test.php") { "paused, inactive" }
        assertNotNull(result)
        assertTrue(result!!.contains("#111 (paused, inactive)"))
    }

    @Test
    fun `session switched - previous running`() {
        AgentSessionTracker.trackById("111")
        val result = AgentSessionTracker.checkSessionSwitch("222", "test.php") { "running, inactive" }
        assertNotNull(result)
        assertTrue(result!!.contains("#111 (running, inactive)"))
    }

    // --- trackById / clear ---

    @Test
    fun `trackById updates lastSessionId`() {
        assertNull(AgentSessionTracker.lastSessionId)
        AgentSessionTracker.trackById("111")
        assertEquals("111", AgentSessionTracker.lastSessionId)
    }

    @Test
    fun `clear resets lastSessionId`() {
        AgentSessionTracker.trackById("111")
        AgentSessionTracker.clear()
        assertNull(AgentSessionTracker.lastSessionId)
    }

    // --- Integration with session tools ---

    @Test
    fun `activate updates tracker`() {
        AgentSessionTracker.trackById("111")
        // Simulate what handleSessionActivate does
        AgentSessionTracker.trackById("222")
        // Now checkSessionSwitch should accept 222
        val result = AgentSessionTracker.checkSessionSwitch("222", "test.php") { "terminated" }
        assertNull(result)
    }

    @Test
    fun `stop clears tracker`() {
        AgentSessionTracker.trackById("111")
        // Simulate what handleSessionStop does
        AgentSessionTracker.clear()
        // No error on any session since tracker is cleared
        val result = AgentSessionTracker.checkSessionSwitch("999", "new.php") { "terminated" }
        assertNull(result)
    }

    @Test
    fun `activate after unexpected switch resolves the conflict`() {
        AgentSessionTracker.trackById("111")
        // Session switches unexpectedly
        val error = AgentSessionTracker.checkSessionSwitch("222", "test.php") { "paused, inactive" }
        assertNotNull(error)
        // Agent calls session_activate to confirm
        AgentSessionTracker.trackById("222")
        // Now it works
        val result = AgentSessionTracker.checkSessionSwitch("222", "test.php") { "paused, inactive" }
        assertNull(result)
    }
}
