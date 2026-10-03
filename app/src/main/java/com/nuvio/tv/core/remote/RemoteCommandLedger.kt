package com.nuvio.tv.core.remote

/** Owned by the player thread. Never evict a request and accidentally execute it twice. */
internal class RemoteCommandLedger(private val limit: Int = 4096) {
    private val requests = LinkedHashMap<String, Pair<RemoteCommand, Boolean>>()
    fun clear() = requests.clear()
    fun execute(snapshot: RemoteSnapshot, request: RemoteCommand, apply: (RemoteCommand) -> Boolean): Boolean {
        if (snapshot.sessionId == null || snapshot.sessionId != request.sessionId || snapshot.state == "idle") return false
        requests[request.requestId]?.let { return it.first == request && it.second }
        if (requests.size >= limit || request.action !in setOf("play", "pause", "seek")) return false
        if (request.action == "seek" && (!snapshot.canSeek || request.positionMs == null || request.positionMs < 0)) return false
        if (request.action != "seek" && request.positionMs != null) return false
        val accepted = runCatching { apply(request) }.getOrDefault(false)
        requests[request.requestId] = request to accepted
        return accepted
    }
}
