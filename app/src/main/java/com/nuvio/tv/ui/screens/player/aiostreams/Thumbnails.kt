package com.nuvio.tv.ui.screens.player.aiostreams

import android.content.Context
import android.util.Base64
import com.nuvio.tv.R
import java.security.KeyStore

internal object Thumbnails {
    init {
        System.loadLibrary("aiostreams_native")
    }

    fun interface Listener {
        fun onThumbnail(positionMs: Long, jpeg: ByteArray?, error: String?)
    }

    @JvmStatic
    external fun open(url: String, headers: Array<String>, caPem: ByteArray?, listener: Listener): Long

    @JvmStatic
    external fun want(handle: Long, positionMs: Long, showingMs: Long)

    @JvmStatic
    external fun prepare(handle: Long)

    @JvmStatic
    external fun close(handle: Long)

    @Volatile
    private var roots: ByteArray? = null

    fun trustedRoots(context: Context): ByteArray? = roots ?: runCatching {
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        buildString {
            for (alias in store.aliases()) {
                val certificate = store.getCertificate(alias) ?: continue
                append("-----BEGIN CERTIFICATE-----\n")
                append(Base64.encodeToString(certificate.encoded, Base64.DEFAULT))
                append("-----END CERTIFICATE-----\n")
            }
            for (id in listOf(R.raw.isrgrootx1, R.raw.isrg_root_x2)) {
                append(context.resources.openRawResource(id).bufferedReader().use { it.readText() })
            }
        }.toByteArray()
    }.getOrNull()?.also { roots = it }
}
