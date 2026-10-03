package com.nuvio.tv.core.remote

import java.security.MessageDigest

/** Called under the server monitor. Only a SHA-256 digest of the issued credential is persisted. */
internal class RemotePairingAuthority(
    initialHash: String?,
    private val clock: () -> Long,
    private val token: () -> String,
    private val persist: (String?) -> Unit,
) {
    private var credentialHash = initialHash
    private var secret: String? = null
    private var expiresAt = 0L
    private var attemptsResetAt = 0L
    private var failures = 0
    val hasCredential get() = credentialHash != null

    fun open(): String = token().also { secret = it; expiresAt = clock() + 120_000 }
    fun close() { secret = null; expiresAt = 0 }
    fun pair(candidate: String): String? {
        val now = clock()
        if (now >= attemptsResetAt) { failures = 0; attemptsResetAt = now + 60_000 }
        if (failures >= 8) return null
        if (secret == null || now >= expiresAt || !equal(secret!!, candidate)) { failures++; return null }
        val credential = token()
        val digest = hash(credential)
        persist(digest) // Never acknowledge credentials that failed to persist.
        credentialHash = digest
        close()
        return credential
    }
    fun authorized(candidate: String) = candidate.length in 32..128 && credentialHash?.let { equal(it, hash(candidate)) } == true
    fun revoke() { persist(null); credentialHash = null; close() }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun equal(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
