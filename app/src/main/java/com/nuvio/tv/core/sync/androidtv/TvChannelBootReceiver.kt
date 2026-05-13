package com.nuvio.tv.core.sync.androidtv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.tvprovider.media.tv.TvContractCompat

class TvChannelBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            TvContractCompat.ACTION_INITIALIZE_PROGRAMS -> {
                TvChannelRefreshJobService.scheduleImmediate(context)
            }
        }
    }
}
