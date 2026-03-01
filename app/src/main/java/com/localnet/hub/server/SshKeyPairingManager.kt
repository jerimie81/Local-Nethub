package com.localnet.hub.server

import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

data class PairedSshKey(
    val id: String,
    val deviceName: String,
    val publicKey: String,
    val fingerprint: String,
    val pairedAt: Long = System.currentTimeMillis(),
)

/**
 * Holds a normalised + validated representation of a key before it is stored.
 * Using a dedicated type avoids the earlier anti-pattern of returning
 * PairedSshKey with id="" from validate().
 */
data class ValidatedKey(
    val deviceName: String,
    val publicKey: String,
    val fingerprint: String,
)

class SshKeyPairingManager {

    private companion object {
        val KNOWN_PREFIXES = listOf("ssh-ed25519 ", "ssh-rsa ", "ecdsa-sha2-")
    }

    private val pairedKeys = CopyOnWriteArrayList<PairedSshKey>()

    fun listKeys(): List<PairedSshKey> = pairedKeys.toList()

    /** Validate and normalise inputs, then store the key. Idempotent on fingerprint. */
    fun pairKey(deviceName: String, publicKey: String): Result<PairedSshKey> {
        val v = validate(deviceName, publicKey).getOrElse { return Result.failure(it) }

        val existing = pairedKeys.firstOrNull { it.fingerprint == v.fingerprint }
        if (existing != null) return Result.success(existing)

        val key = PairedSshKey(
            id = v.fingerprint,
            deviceName = v.deviceName,
            publicKey = v.publicKey,
            fingerprint = v.fingerprint,
        )
        pairedKeys.add(key)
        return Result.success(key)
    }

    /** Validate inputs and return a [ValidatedKey]; does NOT store anything. */
    fun validate(deviceName: String, publicKey: String): Result<ValidatedKey> {
        val name = deviceName.trim().ifBlank { "Unnamed device" }
        val key = publicKey.trim()

        if (!isLikelySshPublicKey(key)) {
            return Result.failure(IllegalArgumentException("Invalid SSH public key format"))
        }

        return Result.success(ValidatedKey(name, key, computeFingerprint(key)))
    }

    fun fingerprintFor(publicKey: String): String = computeFingerprint(publicKey.trim())

    fun unpairKey(id: String): Boolean = pairedKeys.removeIf { it.id == id }

    private fun isLikelySshPublicKey(value: String): Boolean =
        KNOWN_PREFIXES.any { value.startsWith(it) }

    private fun computeFingerprint(publicKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder().withoutPadding().encodeToString(digest)
        return "SHA256:$b64"
    }
}
