package com.nuvio.tv.data.mediaserver

import androidx.annotation.StringRes
import com.nuvio.tv.R

@StringRes
fun ServerFailure.messageRes(): Int = when (this) {
    ServerFailure.AUTH_REQUIRED -> R.string.servers_failure_auth
    ServerFailure.UNREACHABLE -> R.string.servers_failure_unreachable
    ServerFailure.NOT_FOUND -> R.string.servers_failure_not_found
    ServerFailure.INCOMPLETE -> R.string.servers_failure_incomplete
    ServerFailure.FORBIDDEN -> R.string.servers_error_forbidden
    ServerFailure.UNSUPPORTED -> R.string.servers_failure_unsupported
    ServerFailure.FAILED -> R.string.servers_error_failed
}

@StringRes
fun Throwable.serverPlaybackMessageRes(): Int =
    serverFailure().takeUnless { it == ServerFailure.FAILED }?.messageRes() ?: R.string.servers_playback_failed

@StringRes
fun ServerPlayMethod.labelRes(): Int = when (this) {
    ServerPlayMethod.DIRECT_PLAY -> R.string.servers_play_method_direct_play
    ServerPlayMethod.DIRECT_STREAM -> R.string.servers_play_method_direct_stream
    ServerPlayMethod.TRANSCODE -> R.string.servers_play_method_transcode
}

fun readableTranscodeReason(reason: String): String =
    reason.replace(WORD_BOUNDARY, " ").lowercase().replaceFirstChar(Char::uppercase)

private val WORD_BOUNDARY = Regex("(?<=[a-z])(?=[A-Z])")
