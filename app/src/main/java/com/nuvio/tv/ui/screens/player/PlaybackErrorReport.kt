package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.repository.PlaybackIssueErrorInput

/** Native exception details describe only the matching current player error. */
internal fun PlaybackError?.toIssueErrorInput(previous: PlaybackIssueErrorInput?): PlaybackIssueErrorInput {
    if (this?.kind == PlaybackErrorKind.PLAYER && previous != null && previous.displayMessage == message) {
        return previous.copy(category = kind.name)
    }
    return PlaybackIssueErrorInput(
        displayMessage = this?.message,
        errorCode = null,
        errorCodeName = null,
        exceptionClass = null,
        causeClass = null,
        causeMessage = null,
        httpStatus = null,
        category = this?.kind?.name,
    )
}
