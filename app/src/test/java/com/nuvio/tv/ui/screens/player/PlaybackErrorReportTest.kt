package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.repository.PlaybackIssueErrorInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackErrorReportTest {
    private val nativeError = PlaybackIssueErrorInput(
        displayMessage = "Failed",
        errorCode = 2004,
        errorCodeName = "ERROR_CODE_IO_BAD_HTTP_STATUS",
        exceptionClass = "PlaybackException",
        causeClass = "InvalidResponseCodeException",
        causeMessage = "Forbidden",
        httpStatus = 403,
    )

    @Test fun `matching player error retains native exception details`() {
        val report = PlaybackError(PlaybackErrorKind.PLAYER, "Failed", 1).toIssueErrorInput(nativeError)
        assertEquals(nativeError.copy(category = "PLAYER"), report)
    }

    @Test fun `other categories cannot inherit native exception details even with identical message`() {
        for (kind in listOf(PlaybackErrorKind.STREAM, PlaybackErrorKind.TORRENT, PlaybackErrorKind.STARTUP_TIMEOUT)) {
            val report = PlaybackError(kind, "Failed", 1).toIssueErrorInput(nativeError)
            assertEquals(kind.name, report.category)
            assertEquals("Failed", report.displayMessage)
            assertNull(report.errorCode)
            assertNull(report.httpStatus)
            assertNull(report.causeMessage)
        }
    }

    @Test fun `changed or recovered error cannot reuse stale native details`() {
        val changed = PlaybackError(PlaybackErrorKind.PLAYER, "Decoder failed", 1).toIssueErrorInput(nativeError)
        assertEquals("Decoder failed", changed.displayMessage)
        assertNull(changed.errorCode)
        val recovered = (null as PlaybackError?).toIssueErrorInput(nativeError)
        assertNull(recovered.category)
        assertNull(recovered.displayMessage)
        assertNull(recovered.errorCode)
    }
}
