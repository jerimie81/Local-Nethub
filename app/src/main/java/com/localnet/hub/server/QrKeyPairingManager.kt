package com.localnet.hub.server

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class QrPairingInit(
    val payload: String,
    val sessionId: String,
    val sas: String,
    val expiresAt: Long,
)

data class QrPairingResponse(
    val payload: String,
    val sessionId: String,
    val sas: String,
    val responderFingerprint: String,
    val expiresAt: Long,
)

data class QrPairingFinalize(
    val pairedKey: PairedSshKey,
    val sessionId: String,
    val sas: String,
)

class QrKeyPairingManager(
    private val keyPairingManager: SshKeyPairingManager,
    private val sessionTtlMs: Long = 120_000,
) {

    private data class PendingSession(
        val sessionId: String,
        val nonce: String,
        val sas: String,
        val expiresAt: Long,
    )

    private val sessions = ConcurrentHashMap<String, PendingSession>()

    fun createInitPayload(deviceName: String, publicKey: String): Result<QrPairingInit> {
        val v = keyPairingManager.validate(deviceName, publicKey)
            .getOrElse { return Result.failure(it) }

        pruneExpired()
        val sessionId = UUID.randomUUID().toString().replace("-", "")
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val expiresAt = System.currentTimeMillis() + sessionTtlMs
        val sas = shortCode("$sessionId|$nonce|${v.fingerprint}|$expiresAt")

        sessions[sessionId] = PendingSession(sessionId, nonce, sas, expiresAt)

        val payload = encodePayload(
            type = "init",
            values = mapOf(
                "sid" to sessionId,
                "nonce" to nonce,
                "exp" to expiresAt.toString(),
                "sas" to sas,
                "name" to v.deviceName,
                "key" to v.publicKey,
            ),
        )

        return Result.success(QrPairingInit(payload, sessionId, sas, expiresAt))
    }

    fun createResponsePayload(
        initPayload: String,
        deviceName: String,
        publicKey: String,
    ): Result<QrPairingResponse> {
        val initData = decodePayload(initPayload).getOrElse { return Result.failure(it) }
        if (initData["type"] != "init") {
            return Result.failure(IllegalArgumentException("Invalid init payload type"))
        }

        val v = keyPairingManager.validate(deviceName, publicKey)
            .getOrElse { return Result.failure(it) }

        val sessionId = initData["sid"].orEmpty()
        val nonce = initData["nonce"].orEmpty()
        val expiresAt = initData["exp"]?.toLongOrNull()
            ?: return Result.failure(IllegalArgumentException("Missing expiration"))
        val sas = initData["sas"].orEmpty()
        val initiatorKey = initData["key"].orEmpty()

        if (sessionId.isBlank() || nonce.isBlank() || sas.isBlank() || initiatorKey.isBlank()) {
            return Result.failure(IllegalArgumentException("Malformed init payload"))
        }
        if (System.currentTimeMillis() > expiresAt) {
            return Result.failure(IllegalStateException("Pairing request expired"))
        }

        val initiatorFingerprint = keyPairingManager.fingerprintFor(initiatorKey)
        val expectedSas = shortCode("$sessionId|$nonce|$initiatorFingerprint|$expiresAt")
        if (sas != expectedSas) {
            return Result.failure(IllegalArgumentException("SAS mismatch"))
        }

        val proof = hash("$sessionId|$nonce|${v.fingerprint}|$sas")
        val payload = encodePayload(
            type = "resp",
            values = mapOf(
                "sid" to sessionId,
                "nonce" to nonce,
                "exp" to expiresAt.toString(),
                "sas" to sas,
                "name" to v.deviceName,
                "key" to v.publicKey,
                "proof" to proof,
            ),
        )

        return Result.success(
            QrPairingResponse(
                payload = payload,
                sessionId = sessionId,
                sas = sas,
                responderFingerprint = v.fingerprint,
                expiresAt = expiresAt,
            ),
        )
    }

    fun finalizeFromResponse(responsePayload: String): Result<QrPairingFinalize> {
        pruneExpired()
        val resp = decodePayload(responsePayload).getOrElse { return Result.failure(it) }
        if (resp["type"] != "resp") {
            return Result.failure(IllegalArgumentException("Invalid response payload type"))
        }

        val sessionId = resp["sid"].orEmpty()
        val nonce = resp["nonce"].orEmpty()
        val expiresAt = resp["exp"]?.toLongOrNull()
            ?: return Result.failure(IllegalArgumentException("Missing expiration"))
        val sas = resp["sas"].orEmpty()
        val responderName = resp["name"].orEmpty()
        val responderKey = resp["key"].orEmpty()
        val proof = resp["proof"].orEmpty()

        val pending = sessions[sessionId]
            ?: return Result.failure(IllegalStateException("Unknown or expired pairing session"))

        if (pending.expiresAt != expiresAt || System.currentTimeMillis() > expiresAt) {
            sessions.remove(sessionId)
            return Result.failure(IllegalStateException("Pairing session expired"))
        }
        if (pending.nonce != nonce || pending.sas != sas) {
            return Result.failure(IllegalArgumentException("Pairing session mismatch"))
        }

        val responderFingerprint = keyPairingManager.fingerprintFor(responderKey)
        if (proof != hash("$sessionId|$nonce|$responderFingerprint|$sas")) {
            return Result.failure(IllegalArgumentException("Response proof mismatch"))
        }

        val paired = keyPairingManager.pairKey(responderName, responderKey)
            .getOrElse { return Result.failure(it) }
        sessions.remove(sessionId)
        return Result.success(QrPairingFinalize(paired, sessionId, sas))
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { it.value.expiresAt < now }
    }

    private fun encodePayload(type: String, values: Map<String, String>): String {
        val body = values.entries.joinToString("|") { (k, v) -> "$k=${enc(v)}" }
        return "lnh1|type=$type|$body"
    }

    private fun decodePayload(payload: String): Result<Map<String, String>> {
        if (!payload.startsWith("lnh1|")) {
            return Result.failure(IllegalArgumentException("Invalid payload prefix"))
        }
        val map = mutableMapOf<String, String>()
        for (part in payload.split("|").drop(1)) {
            val idx = part.indexOf('=')
            if (idx <= 0) continue
            map[part.substring(0, idx)] = dec(part.substring(idx + 1))
        }
        return Result.success(map)
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun shortCode(seed: String): String = hash(seed).take(6).uppercase()

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun dec(value: String): String = URLDecoder.decode(value, "UTF-8")
}
