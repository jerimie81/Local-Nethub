package com.localnet.hub.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SshTunnelManagerTest {

    private lateinit var manager: SshTunnelManager

    // Use high ephemeral ports unlikely to be in use during CI
    private val testPort1 = 19221
    private val testPort2 = 19222

    @Before
    fun setUp() {
        manager = SshTunnelManager()
    }

    @After
    fun tearDown() {
        manager.stopAll()
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @Test
    fun `startTunnel fails for port 0`() {
        val result = manager.startTunnel(listenPort = 0, targetHost = "192.168.1.10")
        assertTrue(result.isFailure)
        assertEquals("Invalid listen port", result.exceptionOrNull()?.message)
    }

    @Test
    fun `startTunnel fails for port above 65535`() {
        val result = manager.startTunnel(listenPort = 99999, targetHost = "192.168.1.10")
        assertTrue(result.isFailure)
    }

    @Test
    fun `startTunnel fails for negative listen port`() {
        val result = manager.startTunnel(listenPort = -1, targetHost = "192.168.1.10")
        assertTrue(result.isFailure)
    }

    @Test
    fun `startTunnel fails for blank targetHost`() {
        val result = manager.startTunnel(listenPort = testPort1, targetHost = "")
        assertTrue(result.isFailure)
        assertEquals("Target host required", result.exceptionOrNull()?.message)
    }

    @Test
    fun `startTunnel fails for whitespace-only targetHost`() {
        val result = manager.startTunnel(listenPort = testPort1, targetHost = "   ")
        assertTrue(result.isFailure)
    }

    @Test
    fun `startTunnel fails for invalid target port 0`() {
        val result = manager.startTunnel(
            listenPort = testPort1, targetHost = "192.168.1.10", targetPort = 0
        )
        assertTrue(result.isFailure)
        assertEquals("Invalid target port", result.exceptionOrNull()?.message)
    }

    @Test
    fun `startTunnel fails for target port above 65535`() {
        val result = manager.startTunnel(
            listenPort = testPort1, targetHost = "192.168.1.10", targetPort = 70000
        )
        assertTrue(result.isFailure)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Test
    fun `startTunnel succeeds and creates a runtime entry`() {
        val result = manager.startTunnel(listenPort = testPort1, targetHost = "127.0.0.1")
        assertTrue("Tunnel should start successfully on valid inputs", result.isSuccess)
        assertEquals(1, manager.listTunnels().size)
        manager.stopTunnel(testPort1)
    }

    @Test
    fun `startTunnel fails when same port already in use`() {
        manager.startTunnel(listenPort = testPort1, targetHost = "127.0.0.1")
        val second = manager.startTunnel(listenPort = testPort1, targetHost = "127.0.0.1")
        assertTrue("Second tunnel on same port must fail", second.isFailure)
        assertEquals(
            "Tunnel already running on port $testPort1",
            second.exceptionOrNull()?.message
        )
        manager.stopTunnel(testPort1)
    }

    @Test
    fun `stopTunnel removes tunnel from list`() {
        manager.startTunnel(listenPort = testPort1, targetHost = "127.0.0.1")
        manager.stopTunnel(testPort1)
        assertEquals(0, manager.listTunnels().size)
    }

    @Test
    fun `stopTunnel returns false for non-existent port`() {
        val stopped = manager.stopTunnel(19999)
        assertFalse(stopped)
    }

    @Test
    fun `stopAll removes all tunnels`() {
        manager.startTunnel(listenPort = testPort1, targetHost = "127.0.0.1")
        manager.startTunnel(listenPort = testPort2, targetHost = "127.0.0.1")
        assertEquals(2, manager.listTunnels().size)
        manager.stopAll()
        assertEquals(0, manager.listTunnels().size)
    }

    @Test
    fun `can restart tunnel on same port after stopping`() {
        manager.startTunnel(listenPort = testPort1, targetHost = "127.0.0.1")
        manager.stopTunnel(testPort1)
        // Give the OS a moment to release the port
        Thread.sleep(100)
        val result = manager.startTunnel(listenPort = testPort1, targetHost = "127.0.0.1")
        assertTrue("Should be able to reuse port after stopping", result.isSuccess)
        manager.stopTunnel(testPort1)
    }

    // ── Runtime metadata ──────────────────────────────────────────────────────

    @Test
    fun `tunnel runtime stores correct config`() {
        manager.startTunnel(listenPort = testPort1, targetHost = "10.0.0.1", targetPort = 22)
        val tunnel = manager.listTunnels().first()
        assertEquals(testPort1, tunnel.config.listenPort)
        assertEquals("10.0.0.1", tunnel.config.targetHost)
        assertEquals(22, tunnel.config.targetPort)
        manager.stopTunnel(testPort1)
    }

    @Test
    fun `tunnel runtime default target port is 22`() {
        manager.startTunnel(listenPort = testPort1, targetHost = "10.0.0.1")
        val tunnel = manager.listTunnels().first()
        assertEquals(22, tunnel.config.targetPort)
        manager.stopTunnel(testPort1)
    }

    @Test
    fun `tunnel runtime starts with zero active connections`() {
        manager.startTunnel(listenPort = testPort1, targetHost = "127.0.0.1")
        val tunnel = manager.listTunnels().first()
        assertEquals(0, tunnel.activeConnections)
        manager.stopTunnel(testPort1)
    }

    @Test
    fun `tunnel runtime starts with empty lastError`() {
        manager.startTunnel(listenPort = testPort1, targetHost = "127.0.0.1")
        val tunnel = manager.listTunnels().first()
        assertEquals("", tunnel.lastError)
        manager.stopTunnel(testPort1)
    }

    @Test
    fun `listTunnels returns snapshot`() {
        manager.startTunnel(listenPort = testPort1, targetHost = "127.0.0.1")
        val snapshot = manager.listTunnels()
        manager.startTunnel(listenPort = testPort2, targetHost = "127.0.0.1")
        assertEquals("Snapshot should not reflect later additions", 1, snapshot.size)
        manager.stopAll()
    }

    // ── Multiple tunnels ──────────────────────────────────────────────────────

    @Test
    fun `multiple tunnels can coexist on different ports`() {
        manager.startTunnel(listenPort = testPort1, targetHost = "10.0.0.1")
        manager.startTunnel(listenPort = testPort2, targetHost = "10.0.0.2")
        assertEquals(2, manager.listTunnels().size)
        manager.stopAll()
    }

    @Test
    fun `stopping one tunnel does not affect others`() {
        manager.startTunnel(listenPort = testPort1, targetHost = "10.0.0.1")
        manager.startTunnel(listenPort = testPort2, targetHost = "10.0.0.2")
        manager.stopTunnel(testPort1)
        val remaining = manager.listTunnels()
        assertEquals(1, remaining.size)
        assertEquals(testPort2, remaining.first().config.listenPort)
        manager.stopAll()
    }
}
