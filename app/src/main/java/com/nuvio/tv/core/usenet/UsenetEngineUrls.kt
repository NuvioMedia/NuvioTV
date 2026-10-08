package com.nuvio.tv.core.usenet

internal object UsenetEngineUrls {
    private const val MAX_PORTS = 8
    private val ports = LinkedHashSet<Int>()
    private val sessionUrl = Regex("""^http://127\.0\.0\.1:(\d{1,5})/stream/[0-9a-f]{48}/""")
    private val subtitleUrl = Regex("""^http://127\.0\.0\.1:(\d{1,5})/subtitle/[0-9a-f]{48}/""")

    fun remember(port: Int) = synchronized(ports) {
        ports.remove(port)
        ports.add(port)
        while (ports.size > MAX_PORTS) ports.remove(ports.first())
    }

    fun isSession(url: String): Boolean = matches(sessionUrl, url)

    fun isSubtitle(url: String): Boolean = matches(subtitleUrl, url)

    internal fun clear() = synchronized(ports) { ports.clear() }

    private fun matches(pattern: Regex, url: String): Boolean {
        val port = pattern.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return synchronized(ports) { port in ports }
    }
}
