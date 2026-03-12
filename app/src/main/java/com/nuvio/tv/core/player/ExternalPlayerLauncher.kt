package com.nuvio.tv.core.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.nuvio.tv.R

object ExternalPlayerLauncher {

    fun launch(
        context: Context,
        url: String,
        title: String? = null,
        headers: Map<String, String>? = null
    ): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(url), "video/*")

                title?.let {
                    putExtra("title", it)
                    putExtra(Intent.EXTRA_TITLE, it)
                }

                headers?.let { hdrs ->
                    if (hdrs.isNotEmpty()) {
                        val headerArray = hdrs.entries.map { "${it.key}: ${it.value}" }.toTypedArray()
                        putExtra("headers", headerArray)
                    }
                }

                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.player_no_external_player),
                Toast.LENGTH_LONG
            ).show()
            false
        }
    }
}
