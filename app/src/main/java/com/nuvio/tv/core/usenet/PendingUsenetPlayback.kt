package com.nuvio.tv.core.usenet

/** Results-screen ownership until an internal or external player actually launches.
 * Call on the UI thread. Releasing an abandoned URL must not affect a newer session.
 */
internal class PendingUsenetPlayback(private val releaseSession: (String) -> Unit) {
    private var url: String? = null

    fun replace(next: String) {
        if (url == next) return
        release()
        url = next
    }

    fun handoff(launchedUrl: String?) {
        if (url == launchedUrl) url = null
    }

    fun release(expectedUrl: String? = url) {
        if (url != expectedUrl) return
        val abandoned = url ?: return
        url = null
        releaseSession(abandoned)
    }
}
