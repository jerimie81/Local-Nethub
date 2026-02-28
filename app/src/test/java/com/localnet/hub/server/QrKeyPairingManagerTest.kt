package com.localnet.hub.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class QrKeyPairingManagerTest {

    private lateinit var keyManager: SshKeyPairingManager
    private lateinit var qrManager: QrKeyPairingManager

    private val deviceAKey =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBpDMCwFSMQwFHFkjFGqMQI4K7HxcMVbcAAAAAAdeviceA devicea@local"
    private val deviceBKey =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBpDMCwFSMQwFHFkjFGqMQI4K7HxcMVbcBBBBBBdeviceB deviceb@local"

    @Before
    fun setUp() {
        keyManager = SshKeyPairingManager()
        qrManager = QrKeyPairingManager(keyManager)
    }

    // ── Init phase ────────────────────────────────────────────────────────────

    @Test
    fun `createInitPayload succeeds with valid inputs`() {
        val result = qrManager.createInitPayload("Device A", deviceAKey)
        assertTrue("Init should succeed with valid key", result.isSuccess)
    }

    @Test
    fun `createInitPayload fails with invalid public key`() {
        val result = qrManager.createInitPayload("Device A", "not-a-key")
        assertTrue("Init should fail with invalid key", result.isFailure)
    }

    @Test
    fun `createInitPayload produces non-empty sessionId`() {
        val init = qrManager.createInitPayload("Device A", deviceAKey).getOrThrow()
        assertTrue(init.sessionId.isNotBlank())
    }

    @Test
    fun `createInitPayload produces non-empty SAS`() {
        val init = qrManager.createInitPayload("Device A", deviceAKey).getOrThrow()
        assertTrue("SAS should not be blank", init.sas.isNotBlank())
    }

    @Test
    fun `createInitPayload produces non-empty payload`() {
        val init = qrManager.createInitPayload("Device A", deviceAKey).getOrThrow()
        assertTrue("Payload should not be blank", init.payload.isNotBlank())
    }

    @Test
    fun `createInitPayload expiresAt is in the future`() {
        val now = System.currentTimeMillis()
        val init = qrManager.createInitPayload("Device A", deviceAKey).getOrThrow()
        assertTrue("expiresAt should be in the future", init.expiresAt > now)
    }

    @Test
    fun `two init payloads have different sessionIds`() {
        val init1 = qrManager.createInitPayload("Device A", deviceAKey).getOrThrow()
        val init2 = qrManager.createInitPayload("Device A", deviceAKey).getOrThrow()
        assertFalse("Each init should have a unique session", init1.sessionId == init2.sessionId)
    }

    // ── Response phase ────────────────────────────────────────────────────────

    @Test
    fun `createResponsePayload succeeds with valid init payload`() {
        val init = qrManager.createInitPayload("Device A", deviceAKey).getOrThrow()
        val result = qrManager.createResponsePayload(
            initPayload = init.payload,
            deviceName = "Device B",
            publicKey = deviceBKey
        )
        assertTrue("Response should succeed given valid init payload", result.isSuccess)
    }

    @Test
    fun `createResponsePayload fails with empty initPayload`() {
        val result = qrManager.createResponsePayload(
            initPayload = "",
            deviceName = "Device B",
            publicKey = deviceBKey
        )
        assertTrue("Response should fail with empty init payload", result.isFailure)
    }

    @Test
    fun `createResponsePayload fails with invalid public key`() {
        val init = qrManager.createInitPayload("Device A", deviceAKey).getOrThrow()
        val result = qrManager.createResponsePayload(
            initPayload = init.payload,
            deviceName = "Device B",
            publicKey = "garbage-key"
        )
        assertTrue("Response should fail with invalid key", result.isFailure)
    }

    @Test
    fun `createResponsePayload produces non-empty payload`() {
        val init = qrManager.createInitPayload("Device A", deviceAKey).getOrThrow()
        val response = qrManager.createResponsePayload(
            initPayload = init.payload,
            deviceName = "Device B",
            publicKey = deviceBKey
        ).getOrThrow()
        assertTrue(response.payload.isNotBlank())
    }

    @Test
    fun `response sessionId matches init sessionId`() {
        val init = qrManager.createInitPayload("Device A", deviceAKey).getOrThrow()
        val response = qrManager.createResponsePayload(
            initPayload = init.payload,
            deviceName = "Device B",
            publicKey = deviceBKey
        ).getOrThrow()
        assertEquals(
            "Session IDs must match across init and response",
            init.sessionId,
            response.sessionId
        )
    }

    @Test
    fun `response SAS matches init SAS`() {
        val init = qrManager.createInitPayload("Device A", deviceAKey).getOrThrow()
        val response = qrManager.createResponsePayload(
            initPayload = init.payload,
            deviceName = "Device B",
            publicKey = deviceBKey
        ).getOrThrow()
        assertEquals(
            "SAS must be identical on both devices for verification",
            init.sas,
            response.sas
        )
    }

    @Test
    fun `response provides responder fingerprint`() {
        val init = qrManager.createInitPayload("Device A", deviceAKey).getOrThrow()
        val response = qrManager.createResponsePayload(
            initPayload = init.payload,
            deviceName = "Device B",
            publicKey = deviceBKey
        ).getOrThrow()
        assertTrue(
            "Responder fingerprint should start with SHA256:",
            response.responderFingerprint.startsWith("SHA256:")
        )
    }

    // ── Finalize phase ────────────────────────────────────────────────────────

    @Test
    fun `full handshake succeeds end to end`() {
        val init = qrManager.createInitPayload("Device A", deviceAKey).getOrThrow()
        val response = qrManager.createResponsePayload(
            initPayload = init.payload,
            deviceName = "Device B",
            publicKey = deviceBKey
        ).getOrThrow()
        val finalized = qrManager.finalizeFromResponse(response.payload)
        assertTrue("Full QR handshake should complete successfully", finalized.isSuccess)
    }

    @Test
    fun `finalize stores paired key in keyManager`() {
        val init = qrManager.createInitPayload("Device A", deviceAKey).getOrThrow()
        val response = qrManager.createResponsePayload(
            initPayload = init.payload,
            deviceName = "Device B",
            publicKey = deviceBKey
        ).getOrThrow()
        qrManager.finalizeFromResponse(response.payload).getOrThrow()
        assertEquals("Device B's key should be stored after finalize", 1, keyManager.listKeys().size)
    }

    @Test
    fun `finalize produces consistent SAS with init and response`() {
        val init = qrManager.createInitPayload("Device A", deviceAKey).getOrThrow()
        val response = qrManager.createResponsePayload(
            initPayload = init.payload,
            deviceName = "Device B",
            publicKey = deviceBKey
        ).getOrThrow()
        val finalized = qrManager.finalizeFromResponse(response.payload).getOrThrow()
        assertEquals(
            "Finalized SAS must match init SAS so users can verify",
            init.sas,
            finalized.sas
        )
    }

    @Test
    fun `finalize with empty payload fails`() {
        val result = qrManager.finalizeFromResponse("")
        assertTrue("Finalize should fail with empty payload", result.isFailure)
    }

    @Test
    fun `finalize with corrupted payload fails`() {
        val result = qrManager.finalizeFromResponse("not::valid::payload::data")
        assertTrue("Finalize should fail with corrupted payload", result.isFailure)
    }

    @Test
    fun `finalize with replayed response payload fails or is idempotent`() {
        val init = qrManager.createInitPayload("Device A", deviceAKey).getOrThrow()
        val response = qrManager.createResponsePayload(
            initPayload = init.payload,
            deviceName = "Device B",
            publicKey = deviceBKey
        ).getOrThrow()
        qrManager.finalizeFromResponse(response.payload)
        // Second finalize with same payload — should either idempotently succeed or fail
        val second = qrManager.finalizeFromResponse(response.payload)
        // Don't mandate the exact outcome but ensure no crash
        assertNotNull(second)
    }

    // ── Session expiry ────────────────────────────────────────────────────────

    @Test
    fun `init payload expiresAt is after current time`() {
        val init = qrManager.createInitPayload("Device A", deviceAKey).getOrThrow()
        assertTrue(init.expiresAt > System.currentTimeMillis())
    }

    @Test
    fun `response expiresAt is after current time`() {
        val init = qrManager.createInitPayload("Device A", deviceAKey).getOrThrow()
        val response = qrManager.createResponsePayload(
            initPayload = init.payload,
            deviceName = "Device B",
            publicKey = deviceBKey
        ).getOrThrow()
        assertTrue(response.expiresAt > System.currentTimeMillis())
    }
}
