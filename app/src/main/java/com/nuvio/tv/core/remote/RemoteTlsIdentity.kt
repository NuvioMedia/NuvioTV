package com.nuvio.tv.core.remote

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.X509KeyManager
import javax.security.auth.x500.X500Principal

/** A non-exportable Android Keystore identity for the local TLS server. */
internal class RemoteTlsIdentity(alias: String = "nuvio.tv.remote.tls.v2") {
    val certificate: X509Certificate
    val socketFactory: SSLServerSocketFactory

    init {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        // v1 keys cannot acquire new authorizations. Use a new alias so upgrades
        // replace the incompatible identity; the phone must scan its new pin.
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    // Conscrypt hashes the TLS transcript before asking Keystore
                    // to sign with NONEwithECDSA. SHA digests alone reject TLS.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setCertificateSubject(X500Principal("CN=Nuvio TV Remote"))
                    .setCertificateSerialNumber(BigInteger(128, SecureRandom()))
                    .setCertificateNotBefore(Date(0))
                    .setCertificateNotAfter(Date(4_102_444_800_000L))
                    .build())
                generateKeyPair()
            }
        }
        certificate = store.getCertificate(alias) as X509Certificate
        val key = store.getKey(alias, null) as PrivateKey
        val manager = object : X509KeyManager {
            override fun getPrivateKey(a: String?) = if (a == alias) key else null
            override fun getCertificateChain(a: String?) = if (a == alias) arrayOf(certificate) else null
            override fun getServerAliases(type: String?, issuers: Array<out Principal>?) = if (type == "EC") arrayOf(alias) else null
            override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: Socket?) = if (type == "EC") alias else null
            override fun getClientAliases(type: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        }
        socketFactory = SSLContext.getInstance("TLS").apply {
            init(arrayOf(manager), null, SecureRandom())
        }.serverSocketFactory
    }
}
