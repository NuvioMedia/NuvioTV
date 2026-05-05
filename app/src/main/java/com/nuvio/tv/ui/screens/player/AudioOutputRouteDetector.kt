package com.nuvio.tv.ui.screens.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import java.util.Locale

internal data class AudioOutputRoute(
    val key: String,
    val label: String,
    val isBluetooth: Boolean
)

internal object AudioOutputRouteDetector {
    @SuppressLint("NewApi")
    fun detect(context: Context): AudioOutputRoute? {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val device = audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .minByOrNull { routeRank(it.type) }
            ?.takeIf { routeRank(it.type) < ROUTE_RANK_UNSUPPORTED }

        return device?.toRoute()
    }

    @SuppressLint("NewApi")
    private fun AudioDeviceInfo.toRoute(): AudioOutputRoute {
        val typeLabel = typeName(type)
        val product = productName?.toString()?.trim().orEmpty()
        val label = product.ifBlank { typeLabel }
        val normalizedLabel = label
            .lowercase(Locale.US)
            .replace(Regex("\\s+"), "_")
            .replace(Regex("[^a-z0-9._:-]"), "")
            .ifBlank { typeLabel.lowercase(Locale.US) }

        return AudioOutputRoute(
            key = "type:${typeName(type).lowercase(Locale.US)}|name:$normalizedLabel",
            label = label,
            isBluetooth = isBluetoothType(type)
        )
    }

    @SuppressLint("NewApi")
    private fun routeRank(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 0
        AudioDeviceInfo.TYPE_BLE_HEADSET -> 1
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> 2
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 3
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC -> 10
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> 20
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 30
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 40
        else -> ROUTE_RANK_UNSUPPORTED
    }

    @SuppressLint("NewApi")
    private fun isBluetoothType(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> true
        else -> false
    }

    @SuppressLint("NewApi")
    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth_a2dp"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_sco"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble_headset"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "ble_speaker"
        AudioDeviceInfo.TYPE_HDMI -> "hdmi"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "hdmi_arc"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "hdmi_earc"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_device"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb_accessory"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "built_in_speaker"
        else -> "type_$type"
    }

    private const val ROUTE_RANK_UNSUPPORTED = 100
}
