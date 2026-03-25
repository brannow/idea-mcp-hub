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
    fun `no tracking yet - no notice`() {
        val result = AgentSessionTracker.checkSessionSwitch("111", "index.php") { "terminated" }
        assertNull(result)
        assertNull(AgentSessionTracker.pendingNotice)
    }

    @Test
    fun `same session - no notice`() {
        AgentSessionTracker.trackById("111")
        val result = AgentSessionTracker.checkSessionSwitch("111", "index.php") { "terminated" }
        assertNull(result)
        assertNull(AgentSessionTracker.pendingNotice)
    }

    @Test
    fun `session switched - sets notice and auto-tracks`() {
        AgentSessionTracker.trackById("111")
        val result = AgentSessionTracker.checkSessionSwitch("222", "test.php") { "terminated" }
        assertNotNull(result)
        assertTrue(result!!.contains("Note: Active session changed"))
        assertTrue(result.contains("#111 (terminated)"))
        assertTrue(result.contains("#222 \"test.php\""))
        assertTrue(result.contains("Ask the user"))
        // Auto-tracked the new session
        assertEquals("222", AgentSessionTracker.lastSessionId)
        // Notice is stored
        assertEquals(result, AgentSessionTracker.pendingNotice)
    }

    @Test
    fun `session switched - previous paused`() {
        AgentSessionTracker.trackById("111")
        val result = AgentSessionTracker.checkSessionSwitch("222", "test.php") { "paused" }
        assertNotNull(result)
        assertTrue(result!!.contains("#111 (paused)"))
        assertEquals("222", AgentSessionTracker.lastSessionId)
    }

    @Test
    fun `session switched - previous running`() {
        AgentSessionTracker.trackById("111")
        val result = AgentSessionTracker.checkSessionSwitch("222", "test.php") { "running" }
        assertNotNull(result)
        assertTrue(result!!.contains("#111 (running)"))
        assertEquals("222", AgentSessionTracker.lastSessionId)
    }

    // --- trackById / clear ---

    @Test
    fun `trackById updates lastSessionId`() {
        assertNull(AgentSessionTracker.lastSessionId)
        AgentSessionTracker.trackById("111")
        assertEquals("111", AgentSessionTracker.lastSessionId)
    }

    @Test
    fun `clear resets lastSessionId and pendingNotice`() {
        AgentSessionTracker.trackById("111")
        AgentSessionTracker.pendingNotice = "some notice"
        AgentSessionTracker.clear()
        assertNull(AgentSessionTracker.lastSessionId)
        assertNull(AgentSessionTracker.pendingNotice)
    }

    // --- consumeNotice ---

    @Test
    fun `consumeNotice returns and clears notice`() {
        AgentSessionTracker.pendingNotice = "test notice"
        val notice = AgentSessionTracker.consumeNotice()
        assertEquals("test notice", notice)
        assertNull(AgentSessionTracker.pendingNotice)
    }

    @Test
    fun `consumeNotice returns null when no notice`() {
        assertNull(AgentSessionTracker.consumeNotice())
    }

    // --- Integration scenarios ---

    @Test
    fun `stop clears tracker`() {
        AgentSessionTracker.trackById("111")
        // Simulate what handleSessionStop does
        AgentSessionTracker.clear()
        // No notice on any session since tracker is cleared
        val result = AgentSessionTracker.checkSessionSwitch("999", "new.php") { "terminated" }
        assertNull(result)
    }

    @Test
    fun `switch auto-tracks then subsequent call sees no switch`() {
        AgentSessionTracker.trackById("111")
        // Session switches — auto-tracked to 222
        val notice = AgentSessionTracker.checkSessionSwitch("222", "test.php") { "paused" }
        assertNotNull(notice)
        assertEquals("222", AgentSessionTracker.lastSessionId)
        // Next call with same session — no switch
        val result = AgentSessionTracker.checkSessionSwitch("222", "test.php") { "paused" }
        assertNull(result)
    }
}
