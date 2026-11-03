package com.nuvio.tv.reshaped.livetv

import android.util.Xml
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.xmlpull.v1.XmlPullParser

/**
 * Programme guide for the channels in the list: a few programmes per channel around now, keyed
 * by the channel's [LiveTvChannel.guideKey]. The one on air is picked when it is shown, so "now
 * playing" moves on by itself as programmes end.
 */
internal typealias LiveTvSchedule = Map<String, List<LiveTvProgramme>>

private const val CANCEL_CHECK_EVENTS = 4096
/** The length given to a channel's last programme when the guide names no stop for it. */
private const val LAST_PROGRAMME_MS = 60L * 60 * 1000
private const val RELAXED_FEATURE = "http://xmlpull.org/v1/doc/features.html#relaxed"

/**
 * How much of the guide is kept per channel: programmes that ended up to [pastMs] ago (at most
 * [maxPast]) and ones starting within [aheadMs] (at most [maxAhead]). Weak TVs keep less.
 */
internal class LiveTvGuideWindow(
    val pastMs: Long,
    val maxPast: Int,
    val aheadMs: Long,
    val maxAhead: Int,
    /**
     * How far back channels with catch-up keep programmes in memory, so past ones can be played
     * again (their days, at most this); older days are in [LiveTvArchive], read when looked at.
     */
    val catchupPastMs: Long = pastMs,
    val maxCatchupPast: Int = maxPast,
    /**
     * Descriptions and pictures are what the guide's header shows; for every programme they would
     * take tens of MB with big lists. They are read for programmes on within [detailsMs] from now,
     * kept for the one on now and the next [detailsPerChannel] − 1 of each channel, cut to
     * [maxDescription] characters, and stop being kept once [detailsBudgetChars] are used.
     */
    val detailsMs: Long = 0L,
    val maxDescription: Int = 0,
    val detailsPerChannel: Int = 0,
    val detailsBudgetChars: Int = 0,
    /**
     * At most this many past programmes over all catch-up channels: with thousands of them
     * (whole Xtream panels), each keeps fewer, never fewer than [maxPast].
     */
    val maxCatchupProgrammes: Int = Int.MAX_VALUE,
    /**
     * Channels of short programmes (news, music, shopping) keep up to [maxShortAhead] programmes
     * ahead instead of [maxAhead], so they last the [aheadMs] too, while [maxAheadProgrammes] over
     * all channels allows: big lists keep [maxAhead] each, as before.
     */
    val maxShortAhead: Int = maxAhead,
    val maxAheadProgrammes: Int = 0,
) {
    /** How far back a channel's programmes are kept: [catchupDays] is its catch-up's, null without. */
    fun pastMsFor(catchupDays: Int?): Long =
        if (catchupDays == null) pastMs else maxOf(pastMs, minOf(catchupPastMs, catchupDays * DAY))

    companion object {
        private const val HOUR = 60L * 60 * 1000
        private const val DAY = 24 * HOUR
        val Regular = LiveTvGuideWindow(
            pastMs = 3 * HOUR, maxPast = 6, aheadMs = 12 * HOUR, maxAhead = 18,
            catchupPastMs = DAY, maxCatchupPast = 48,
            detailsMs = 6 * HOUR, maxDescription = 320, detailsPerChannel = 4, detailsBudgetChars = 6_000_000,
            maxCatchupProgrammes = 300_000,
            maxShortAhead = 48, maxAheadProgrammes = 60_000,
        )
        val LowMemory = LiveTvGuideWindow(
            pastMs = 2 * HOUR, maxPast = 4, aheadMs = 8 * HOUR, maxAhead = 10,
            catchupPastMs = 12 * HOUR, maxCatchupPast = 24,
            detailsMs = 3 * HOUR, maxDescription = 200, detailsPerChannel = 2, detailsBudgetChars = 1_500_000,
            maxCatchupProgrammes = 100_000,
            maxShortAhead = 32, maxAheadProgrammes = 20_000,
        )
    }
}

/** What a guide read looks for, built once per channel list. */
internal class LiveTvGuideRequest(
    /** Every channel's [LiveTvChannel.guideKey]. */
    val keys: Set<String>,
    /** [liveTvNameKey] of each channel's name to the keys of the channels with that name. */
    val keysByName: Map<String, List<String>>,
    /** Keys of channels the playlist gives no logo: the guide's own logo is used for them. */
    val keysWithoutLogo: Set<String>,
    /** Keys of channels with catch-up to how many days back they replay: they keep more past programmes. */
    val catchupKeys: Map<String, Int> = emptyMap(),
    /** XMLTV ids to playlist-scoped keys; two sources may use the same XMLTV id. */
    val keysById: Map<String, List<String>> = emptyMap(),
    /** The country tag ([liveTvNameTag]) of channels whose name has one. */
    val tagsByKey: Map<String, String> = emptyMap(),
    /** How far each channel's guide times are moved (the playlist's `tvg-shift`); most have none. */
    val shiftMsByKey: Map<String, Long> = emptyMap(),
) {
    companion object {
        fun from(channels: List<LiveTvChannel>): LiveTvGuideRequest {
            val keys = HashSet<String>(channels.size * 2)
            val byName = HashMap<String, MutableList<String>>(channels.size * 2)
            val withoutLogo = HashSet<String>()
            val catchup = HashMap<String, Int>()
            val byId = HashMap<String, MutableList<String>>()
            val tags = HashMap<String, String>()
            // A few tags shared by thousands of channels: each kept once.
            val tagPool = HashMap<String, String>()
            val shifts = HashMap<String, Long>()
            channels.forEach { channel ->
                // A key two channels share keeps the longer catch-up.
                channel.catchup?.let { catchup[channel.guideKey] = maxOf(it.days, catchup[channel.guideKey] ?: 0) }
                if (!keys.add(channel.guideKey)) return@forEach
                if (channel.guideShiftMinutes != 0) shifts[channel.guideKey] = channel.guideShiftMinutes * 60_000L
                channel.tvgId?.let(::liveTvGuideId)?.takeIf(String::isNotEmpty)?.let { id ->
                    byId.getOrPut(id) { ArrayList(1) } += channel.guideKey
                }
                val name = liveTvNameKey(channel.name)
                if (name.isNotEmpty()) byName.getOrPut(name) { ArrayList(1) } += channel.guideKey
                // Guides often list a channel by the playlist's tvg-name rather than its shown name.
                channel.tvgName?.let(::liveTvNameKey)?.takeIf { it.isNotEmpty() && it != name }?.let { alias ->
                    byName.getOrPut(alias) { ArrayList(1) } += channel.guideKey
                }
                if (channel.logoUrl.isNullOrBlank()) withoutLogo += channel.guideKey
                liveTvNameTag(channel.name)?.let { tags[channel.guideKey] = tagPool.getOrPut(it) { it } }
            }
            return LiveTvGuideRequest(keys, byName, withoutLogo, catchup, byId, tags, shifts)
        }
    }
}

/** Guide matching also changes when a playlist renames channels without changing their ids. */
internal fun liveTvGuideMatchingKey(channels: List<LiveTvChannel>): Long {
    var matching = 0L
    channels.forEach { channel ->
        matching = matching * 31 + channel.guideKey.hashCode()
        matching = matching * 31 + channel.name.hashCode()
        matching = matching * 31 + (channel.tvgName?.hashCode() ?: 0)
        matching = matching * 31 + if (channel.logoUrl.isNullOrBlank()) 1 else 0
        matching = matching * 31 + (channel.catchup?.days ?: 0)
        matching = matching * 31 + channel.guideShiftMinutes
    }
    return matching
}

/** A read guide: programmes, logos for channels without one, and the channels whose kept programmes were cut short. */
internal class LiveTvGuide(
    val schedule: LiveTvSchedule,
    val logos: Map<String, String>,
    val truncated: Set<String>,
    /** False when the file broke off (malformed or cut): what was read before it is kept. */
    val complete: Boolean = true,
    /** How many guide channels and programmes the file had: none means it was no guide (an HTML page). */
    val elements: Int = 0,
    /** A refresh failed and this is the last good saved guide; retry sooner without hiding it. */
    val refreshFailed: Boolean = false,
    /** Keys this guide found only by the channel's name, not by its guide id: another guide's id match wins. */
    val nameMatched: Set<String> = emptySet(),
) {
    val canReplaceSavedGuide: Boolean get() = complete && elements > 0 && !refreshFailed

    /** Whether any channel has a programme still to come: false once a saved guide has run out. */
    fun hasAhead(nowEpochMs: Long): Boolean =
        schedule.values.any { programmes -> programmes.isNotEmpty() && programmes.last().stopEpochMs > nowEpochMs }

    /** The past days of catch-up channels this read met, for [LiveTvArchive]; null when not kept. */
    var spill: LiveTvArchiveSpill? = null

    fun afterFailedRefresh(): LiveTvGuide =
        LiveTvGuide(schedule, logos, truncated, complete, elements, refreshFailed = true, nameMatched = nameMatched).also { it.spill = spill }
}

/** Imports finish independently; neither a slow source nor completion order changes EPG priority. */
internal suspend fun loadLiveTvGuides(
    count: Int,
    read: suspend (Int) -> LiveTvGuide?,
    publish: (Int, LiveTvGuide?) -> Unit,
    /** Guides read at once; 1 on low-memory TVs, so two parses never share the heap. */
    parallel: Int = 2,
) = coroutineScope {
    val permits = Semaphore(parallel.coerceAtLeast(1))
    val results = Channel<Pair<Int, LiveTvGuide?>>(1)
    repeat(count) { index ->
        launch {
            permits.withPermit { results.send(index to read(index)) }
        }
    }
    repeat(count) {
        val (index, guide) = results.receive()
        publish(index, guide)
    }
    results.close()
}

/**
 * Reads a saved XMLTV guide (plain or gzip) through a pull parser, keeping only programmes of
 * the requested channels within [window]. Memory stays flat however large the guide is; guides of
 * 100+ MB are common. Channels are matched on the guide's channel id, or, when the playlist's id
 * is missing or not in the guide, on the channel's name (as IPTV players do).
 */
internal suspend fun readXmlTvGuide(
    file: java.io.File,
    request: LiveTvGuideRequest,
    nowEpochMs: Long,
    window: LiveTvGuideWindow,
    spills: LiveTvArchive.Spills? = null,
): LiveTvGuide =
    LiveTvHttp.readFile(file) { input -> readXmlTvGuide(input, request, nowEpochMs, window, spills = spills) }

/** [readXmlTvGuide] from a stream (blocking): a saved file, or a download as it arrives. */
internal fun readXmlTvGuide(
    input: InputStream,
    request: LiveTvGuideRequest,
    nowEpochMs: Long,
    window: LiveTvGuideWindow,
    parserFactory: () -> XmlPullParser = Xml::newPullParser,
    /** Where past days of catch-up channels go ([LiveTvArchive]); null when they are not kept. */
    spills: LiveTvArchive.Spills? = null,
): LiveTvGuide {
    val spill = if (request.catchupKeys.isEmpty()) null else spills?.create()
    val builder = LiveTvScheduleBuilder(request, nowEpochMs, window, spill)
    // A malformed tail (unknown entity, cut download) keeps what was read before it.
    val complete = try {
        readGuide(LiveTvHttp.gunzipIfNeeded(input), builder, parserFactory)
        !Thread.currentThread().isInterrupted
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        false
    } finally {
        spill?.finish()
    }
    return builder.build(complete).also { it.spill = spill }
}

private fun readGuide(input: InputStream, builder: LiveTvScheduleBuilder, parserFactory: () -> XmlPullParser) {
    val parser = parserFactory()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    // Provider guides often hold a bare "&" or HTML entities (&nbsp;): strict reading would stop there.
    runCatching { parser.setFeature(RELAXED_FEATURE, true) }
    parser.setInput(input, null)
    var events = 0
    var opened = false
    var closed = false
    // Where the last event ended: relaxed reading closes what a cut file left open with end tags
    // that take up no text, and a cut guide must not pass as complete.
    var line = -1
    var column = -1
    // `<tv/>`: its end tag takes up no text either.
    var emptyTv = false
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        // Blocking IO thread: a cancelled load stops at the next check.
        if (++events % CANCEL_CHECK_EVENTS == 0 && Thread.currentThread().isInterrupted) return
        if (event == XmlPullParser.START_TAG) {
            if (parser.depth == 1) {
                // Whatever a panel prints after the guide (a PHP warning) ends it; a further <tv>
                // (guides joined end to end) is read on.
                if (closed && !parser.name.equals("tv", ignoreCase = true)) break
                check(parser.name.equals("tv", ignoreCase = true)) { "Not an XMLTV guide" }
                opened = true
                // A further guide joined on must end properly too.
                closed = false
                emptyTv = runCatching { parser.isEmptyElementTag }.getOrDefault(false)
            }
            when {
                parser.name.equals("programme", ignoreCase = true) -> {
                    builder.elements++
                    val channelId = parser.getAttributeValue(null, "channel")?.let(::liveTvGuideId)
                    val keys = channelId?.let(builder::keysFor)
                    if (keys == null || channelId == null) {
                        parser.skipElement()
                    } else {
                        val start = parser.getAttributeValue(null, "start")?.let(LiveTvClock::parseXmlTvTimestamp)
                        val stop = parser.getAttributeValue(null, "stop")?.let(LiveTvClock::parseXmlTvTimestamp)
                        if (start != null) builder.finishPending(channelId, start)
                        // Most of a week-long guide is outside what is kept: skipped without reading its text.
                        if (start == null || (stop != null && !builder.mayKeep(keys, start, stop))) {
                            parser.skipElement()
                        } else {
                            val programme = parser.readProgramme(builder.wantsDetails(keys, start, stop ?: start + LAST_PROGRAMME_MS))
                            val title = programme.title
                            if (title != null) {
                                builder.programme(channelId, start, stop, title, programme.description, programme.image)
                            }
                        }
                    }
                }
                parser.name.equals("channel", ignoreCase = true) -> {
                    builder.elements++
                    val channelId = parser.getAttributeValue(null, "id")?.let(::liveTvGuideId)
                    if (channelId == null) parser.skipElement() else parser.readChannel(channelId, builder)
                }
            }
        }
        if (event == XmlPullParser.END_TAG && parser.depth == 1 && parser.name.equals("tv", ignoreCase = true)) {
            closed = parser.lineNumber != line || parser.columnNumber != column || emptyTv
        }
        line = parser.lineNumber
        column = parser.columnNumber
        event = parser.next()
    }
    check(opened && closed) { "Incomplete XMLTV guide" }
}

/** From a START_TAG: moves to its matching END_TAG. */
private fun XmlPullParser.skipElement() {
    var depth = 1
    while (depth > 0) {
        when (next()) {
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.END_DOCUMENT -> return
        }
    }
}

/**
 * From a START_TAG: its text, leaving the parser on its END_TAG. Unlike nextText(), markup inside
 * (`<desc>Line<br/>line</desc>`, which guides do send) is skipped instead of throwing, which
 * would end the whole read there.
 */
private fun XmlPullParser.readText(): String {
    var text: StringBuilder? = null
    var first: String? = null
    var depth = 1
    // Markup between two runs of text (a <br/>) reads as a space.
    var gap = false
    while (depth > 0) {
        when (next()) {
            XmlPullParser.START_TAG -> {
                depth++
                gap = true
            }
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                val part = getText() ?: continue
                when {
                    first == null -> first = part
                    text == null -> text = StringBuilder(first)
                    else -> Unit
                }
                if (text != null) {
                    if (gap && text.isNotEmpty() && !text.last().isWhitespace()) text.append(' ')
                    text.append(part)
                }
                gap = false
            }
            XmlPullParser.END_DOCUMENT -> break
        }
    }
    return text?.toString() ?: first.orEmpty()
}

/** From a channel's START_TAG: its names and logo, leaving the parser on its END_TAG. */
private fun XmlPullParser.readChannel(channelId: String, builder: LiveTvScheduleBuilder) {
    val names = ArrayList<String>(2)
    var icon: String? = null
    var depth = 1
    while (depth > 0) {
        when (next()) {
            XmlPullParser.START_TAG -> when {
                depth == 1 && name.equals("display-name", ignoreCase = true) -> {
                    val text = readText().trim()
                    if (text.isNotEmpty()) names.add(text)
                }
                depth == 1 && name.equals("icon", ignoreCase = true) -> {
                    if (icon == null) icon = getAttributeValue(null, "src")?.trim()?.takeIf(String::isHttpUrl)
                    depth++
                }
                else -> depth++
            }
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.END_DOCUMENT -> return
        }
    }
    builder.channel(channelId, names, icon)
}

/** What [readProgramme] takes from a programme. */
private class ProgrammeText {
    var title: String? = null
    var description: String? = null
    var image: String? = null
}

/**
 * From a programme's START_TAG: its first title (and with [details], its first description and
 * picture), leaving the parser on the programme's END_TAG.
 */
private fun XmlPullParser.readProgramme(details: Boolean): ProgrammeText {
    val text = ProgrammeText()
    var depth = 1
    while (depth > 0) {
        when (next()) {
            XmlPullParser.START_TAG -> when {
                // readText() ends on the element's END_TAG, so the depth is unchanged.
                depth == 1 && text.title == null && name.equals("title", ignoreCase = true) ->
                    text.title = readText().trim().takeIf(String::isNotBlank)
                details && depth == 1 && text.description == null && name.equals("desc", ignoreCase = true) ->
                    text.description = readText().trim().takeIf(String::isNotBlank)
                details && depth == 1 && text.image == null && name.equals("icon", ignoreCase = true) -> {
                    text.image = getAttributeValue(null, "src")?.trim()?.takeIf(String::isHttpUrl)
                    depth++
                }
                else -> depth++
            }
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.END_DOCUMENT -> return text
        }
    }
    return text
}

/**
 * One channel's programmes while a guide is read, with how many have ended and how many have not
 * at the read's now, and the latest start: kept as they change, so adding one costs no full pass.
 */
private class KeptProgrammes : ArrayList<LiveTvProgramme>(4) {
    var past = 0
        private set
    var ahead = 0
        private set
    var latestStart = Long.MIN_VALUE
        private set

    fun addKept(past: Boolean, programme: LiveTvProgramme) {
        add(programme)
        if (past) this.past++ else ahead++
        if (programme.startEpochMs > latestStart) latestStart = programme.startEpochMs
    }

    fun removeKept(index: Int, nowEpochMs: Long) {
        val removed = removeAt(index)
        if (removed.stopEpochMs <= nowEpochMs) past-- else ahead--
        if (removed.startEpochMs == latestStart) {
            latestStart = Long.MIN_VALUE
            forEach { if (it.startEpochMs > latestStart) latestStart = it.startEpochMs }
        }
    }

    /** The index of the earliest or [latest] programme that has ended ([past]) or not; one must exist. */
    fun extreme(past: Boolean, latest: Boolean, nowEpochMs: Long): Int {
        var found = -1
        var foundStart = 0L
        for (index in indices) {
            val programme = this[index]
            if ((programme.stopEpochMs <= nowEpochMs) != past) continue
            if (found < 0 || (if (latest) programme.startEpochMs > foundStart else programme.startEpochMs < foundStart)) {
                found = index
                foundStart = programme.startEpochMs
            }
        }
        return found
    }
}

/** Collects programmes for the requested channels while a guide is read. */
internal class LiveTvScheduleBuilder(
    private val request: LiveTvGuideRequest,
    private val nowEpochMs: Long,
    private val window: LiveTvGuideWindow,
    /** Where the past days of catch-up channels go, beyond what memory keeps; null to skip them. */
    private val spill: LiveTvArchiveSpill? = null,
) {
    private val entries = HashMap<String, KeptProgrammes>()
    private val truncated = HashSet<String>()
    /** Guide channels and programmes met, kept or not. */
    var elements = 0
    /**
     * Guide channel ids that may feed channels by name, in the guide's order. Several can name
     * one channel ("VRT 1" and "VRT 1 HD", or an id of its own with no programmes): the first
     * whose programmes come feeds it, see [ownKeys].
     */
    private val aliases = LinkedHashMap<String, List<String>>()
    /** Keys a guide `<channel>` has by id: their logo is that one's, never a name match's. */
    private val claimed = HashSet<String>()
    private val icons = HashMap<String, String>()
    /** The country tag of the name each alias matched with, when it had one. */
    private val aliasTags = HashMap<String, String>()
    private val logos = HashMap<String, String>()
    /** Repeated titles (news, films shown twice) are kept once. */
    private val titles = HashMap<String, String>()
    private var channelsDone = false
    private data class Pending(val start: Long, val title: String, val description: String?, val image: String?)
    /** XMLTV's optional stop is inferred from the next start of the same guide channel. */
    private val pending = HashMap<String, Pending>()

    fun finishPending(channelId: String, nextStart: Long) {
        val previous = pending[channelId] ?: return
        if (nextStart <= previous.start) return
        pending.remove(channelId)
        keysFor(channelId)?.let { keys ->
            if (mayKeep(keys, previous.start, nextStart)) {
                add(keys, previous.title, previous.start, nextStart, previous.description, previous.image)
            }
        }
    }

    fun programme(channelId: String, start: Long, stop: Long?, title: String, description: String? = null, image: String? = null) {
        finishPending(channelId, start)
        val keys = keysFor(channelId) ?: return
        if (stop == null) {
            pending[channelId] = Pending(start, title, description, image)
        } else if (mayKeep(keys, start, stop)) {
            add(keys, title, start, stop, description, image)
        }
    }

    /**
     * A `<channel>` of the guide ([channelId] lower case). Most guides list these before their
     * programmes; some put each before its own programmes, or join guides end to end.
     */
    fun channel(channelId: String, names: List<String>, icon: String?) {
        val direct = directKeys(channelId)
        if (direct != null) {
            claimed.addAll(direct)
            idMatched.addAll(direct)
            if (icon != null) direct.forEach { if (it in request.keysWithoutLogo) logos[it] = icon }
            return
        }
        for (name in names) {
            val keys = request.keysByName[liveTvNameKey(name)] ?: continue
            aliases[channelId] = keys
            liveTvNameTag(name)?.let { aliasTags[channelId] = it }
            if (icon != null) {
                // Past the first programme, logos are given as the channels come.
                if (channelsDone) keys.forEach { if (it !in claimed && it in request.keysWithoutLogo) logos.putIfAbsent(it, icon) } else icons[channelId] = icon
            }
            return
        }
    }

    /**
     * Name matches feed a channel only while its own id brings no programmes, each from one guide
     * channel ([ownKeys]); for a logo, the first guide channel with the name gives it.
     */
    private fun finishChannels() {
        channelsDone = true
        if (aliases.isEmpty()) return
        aliases.forEach { (channelId, keys) ->
            val icon = icons[channelId] ?: return@forEach
            keys.forEach { key -> if (key !in claimed && key in request.keysWithoutLogo) logos.putIfAbsent(key, icon) }
        }
        icons.clear()
    }

    /** The channel keys a programme of guide channel [channelId] (lower case) is kept under, or null. */
    fun keysFor(channelId: String): List<String>? {
        if (!channelsDone) finishChannels()
        directKeys(channelId)?.let { return ownKeys(channelId, it, direct = true) }
        aliases[channelId]?.let { return ownKeys(channelId, it, direct = false) }
        return null
    }

    /** The guide channel feeding each channel key, and whether it matched by id. */
    private val feeders = HashMap<String, String>()
    private val nameFed = HashSet<String>()
    /** Keys fed by a name match whose country tag is the channel's own ("UK: News" for "UK | News"). */
    private val sameTagFed = HashSet<String>()
    /** Keys a guide channel matched by id: by `<channel>` or by programmes. */
    private val idMatched = HashSet<String>()

    /**
     * Of [keys], the ones guide channel [channelId] feeds: one guide channel per channel key. A
     * guide can list programmes for an id it has no `<channel>` for, so a channel can also be
     * matched by name to another guide channel (often a +1 or HD copy): two schedules in one row
     * showed every programme twice. The id match wins, whichever comes first in the file; among
     * name matches, one with the channel's own country tag wins over another country's.
     */
    private fun ownKeys(channelId: String, keys: List<String>, direct: Boolean): List<String>? {
        var all = true
        if (direct) idMatched.addAll(keys)
        val tag = if (direct) null else aliasTags[channelId]
        for (key in keys) {
            val feeder = feeders[key]
            val sameTag = tag != null && tag == request.tagsByKey[key]
            when {
                feeder == null -> {
                    feeders[key] = channelId
                    if (!direct) nameFed += key
                    if (sameTag) sameTagFed += key
                }
                feeder == channelId -> Unit
                (direct && key in nameFed) || (sameTag && key in nameFed && key !in sameTagFed) -> {
                    // What the weaker match brought in so far goes.
                    feeders[key] = channelId
                    if (direct) nameFed -= key
                    if (direct) sameTagFed -= key else sameTagFed += key
                    entries.remove(key)
                    spill?.drop(key)
                    truncated -= key
                }
                else -> all = false
            }
        }
        if (all) return keys
        return keys.filter { feeders[it] == channelId }.takeIf { it.isNotEmpty() }
    }

    private fun directKeys(channelId: String): List<String>? =
        request.keysById[channelId] ?: if (channelId in request.keys) listOf(channelId) else null

    /** Whether a programme from [startEpochMs] to [stopEpochMs] has its description and picture read. */
    /** [wantsDetails] for guide channel [keys], each at its own time ([LiveTvGuideRequest.shiftMsByKey]). */
    fun wantsDetails(keys: List<String>, startEpochMs: Long, stopEpochMs: Long): Boolean =
        if (request.shiftMsByKey.isEmpty()) {
            wantsDetails(startEpochMs, stopEpochMs)
        } else {
            keys.any { key ->
                val shift = request.shiftMsByKey[key] ?: 0L
                wantsDetails(startEpochMs + shift, stopEpochMs + shift)
            }
        }

    fun wantsDetails(startEpochMs: Long, stopEpochMs: Long): Boolean =
        window.detailsPerChannel > 0 && stopEpochMs > nowEpochMs && startEpochMs < nowEpochMs + window.detailsMs

    /** Past programmes each catch-up channel keeps, within [LiveTvGuideWindow.maxCatchupProgrammes] over all. */
    private val catchupPastCount: Int = run {
        val channels = request.catchupKeys.size.coerceAtLeast(1)
        (window.maxCatchupProgrammes / channels).coerceIn(window.maxPast, window.maxCatchupPast)
    }

    /** Programmes ahead each channel keeps, within [LiveTvGuideWindow.maxAheadProgrammes] over all. */
    private val aheadCount: Int = run {
        val channels = request.keys.size.coerceAtLeast(1)
        (window.maxAheadProgrammes / channels).coerceIn(window.maxAhead, maxOf(window.maxAhead, window.maxShortAhead))
    }

    /**
     * Whether a programme from [startEpochMs] to [stopEpochMs] of channels [keys] can be kept at
     * all; one starting too late marks them cut short, as [add] would.
     */
    fun mayKeep(keys: List<String>, startEpochMs: Long, stopEpochMs: Long): Boolean {
        if (stopEpochMs <= startEpochMs) return false
        if (request.shiftMsByKey.isEmpty()) return mayKeepAt(keys, startEpochMs, stopEpochMs)
        // Channels whose guide is moved (tvg-shift) are judged at their own times.
        return keys.any { key ->
            val shift = request.shiftMsByKey[key] ?: 0L
            mayKeepAt(listOf(key), startEpochMs + shift, stopEpochMs + shift)
        }
    }

    private fun mayKeepAt(keys: List<String>, startEpochMs: Long, stopEpochMs: Long): Boolean {
        if (startEpochMs >= nowEpochMs + window.aheadMs) {
            keys.forEach { truncated += it }
            return false
        }
        return stopEpochMs > nowEpochMs - window.pastMs ||
            keys.any { key -> request.catchupKeys[key]?.let { stopEpochMs > nowEpochMs - pastFor(it) } == true }
    }

    /** How far back a catch-up channel's programmes are read: its archive's days, or what memory keeps. */
    private fun pastFor(catchupDays: Int): Long =
        if (spill != null) maxOf(window.pastMsFor(catchupDays), LiveTvArchive.pastMsFor(catchupDays)) else window.pastMsFor(catchupDays)

    fun add(
        keys: List<String>,
        title: String,
        startEpochMs: Long,
        stopEpochMs: Long,
        description: String? = null,
        image: String? = null,
    ) {
        if (request.shiftMsByKey.isEmpty()) {
            keys.forEach { add(it, title, startEpochMs, stopEpochMs, description, image) }
        } else {
            keys.forEach { key ->
                val shift = request.shiftMsByKey[key] ?: 0L
                add(key, title, startEpochMs + shift, stopEpochMs + shift, description, image)
            }
        }
    }

    /** [key] must be one of the request's keys. */
    fun add(
        key: String,
        title: String,
        startEpochMs: Long,
        stopEpochMs: Long,
        description: String? = null,
        image: String? = null,
    ) {
        val catchupDays = request.catchupKeys[key]
        val catchup = catchupDays != null
        if (stopEpochMs <= startEpochMs) return
        // Past days of a catch-up channel go to the archive as they come; memory keeps the last of them.
        if (spill != null && catchupDays != null && stopEpochMs <= nowEpochMs &&
            stopEpochMs > nowEpochMs - LiveTvArchive.pastMsFor(catchupDays)
        ) {
            spill.write(key, startEpochMs, stopEpochMs, title)
        }
        if (stopEpochMs <= nowEpochMs - window.pastMsFor(catchupDays)) return
        if (startEpochMs >= nowEpochMs + window.aheadMs) {
            truncated += key
            return
        }
        val list = entries.getOrPut(key) { KeptProgrammes() }
        val past = stopEpochMs <= nowEpochMs
        // The same slot twice (a guide channel matched by id and by name): keep one. Guides list a
        // channel's programmes in time order, so a start after every kept one needs no search.
        if (startEpochMs <= list.latestStart && list.any { it.startEpochMs == startEpochMs }) return
        if (past && list.past >= (if (catchup) catchupPastCount else window.maxPast)) {
            // Keep the latest programmes that have ended.
            val earliest = list.extreme(past = true, latest = false, nowEpochMs)
            if (list[earliest].startEpochMs >= startEpochMs) return
            list.removeKept(earliest, nowEpochMs)
        } else if (!past && list.ahead >= aheadCount) {
            // Guides are usually in time order; if not, keep the earliest programmes.
            truncated += key
            val latest = list.extreme(past = false, latest = true, nowEpochMs)
            if (list[latest].startEpochMs <= startEpochMs) return
            list.removeKept(latest, nowEpochMs)
        }
        val details = wantsDetails(startEpochMs, stopEpochMs)
        list.addKept(past, LiveTvProgramme(
            title = titles.getOrPut(title) { title },
            startEpochMs = startEpochMs,
            stopEpochMs = stopEpochMs,
            // Repeats share one copy, as titles do.
            description = description?.takeIf { details }?.let(::shortDescription)?.let { titles.getOrPut(it) { it } },
            image = image?.takeIf { details }?.let { titles.getOrPut(it) { it } },
        ))
    }

    /** Cut at a word near [LiveTvGuideWindow.maxDescription], with an ellipsis. */
    private fun shortDescription(text: String): String {
        val max = window.maxDescription
        if (text.length <= max) return text
        val cut = text.lastIndexOf(' ', max - 1).takeIf { it > max / 2 } ?: (max - 1)
        return text.substring(0, cut).trimEnd() + "…"
    }

    fun build(complete: Boolean = true): LiveTvGuide {
        // Name matches settle here too when the guide had no programme for them (their logos still count).
        if (!channelsDone) finishChannels()
        // A channel's last programme without a stop (none follows to end it) is given a usual length
        // rather than dropped: with a guide listing only what is on now, that would be everything.
        if (complete) {
            pending.forEach { (channelId, last) ->
                val keys = keysFor(channelId) ?: return@forEach
                val stop = last.start + LAST_PROGRAMME_MS
                if (mayKeep(keys, last.start, stop)) add(keys, last.title, last.start, stop, last.description, last.image)
            }
        }
        pending.clear()
        val nameMatched = HashSet<String>(nameFed)
        aliases.values.forEach { keys -> keys.forEach { if (it !in idMatched) nameMatched += it } }
        val schedule = HashMap<String, List<LiveTvProgramme>>(entries.size * 2)
        entries.forEach { (key, list) -> schedule[key] = liveTvWithoutOverlaps(list.sortedBy { it.startEpochMs }) }
        if (window.detailsPerChannel > 0) keepDetails(schedule)
        titles.clear()
        return LiveTvGuide(schedule = schedule, logos = logos, truncated = truncated, complete = complete, elements = elements, nameMatched = nameMatched)
    }

    /**
     * Keeps descriptions and pictures for the programme on now of every channel first, then the
     * next ones, up to [LiveTvGuideWindow.detailsPerChannel] each and the budget over all.
     */
    private fun keepDetails(schedule: HashMap<String, List<LiveTvProgramme>>) {
        var budget = window.detailsBudgetChars.toLong()
        val firstAhead = HashMap<String, Int>(schedule.size * 2)
        schedule.forEach { (key, list) ->
            firstAhead[key] = list.indexOfFirst { it.stopEpochMs > nowEpochMs }.let { if (it < 0) list.size else it }
        }
        val keep = HashMap<String, Int>(schedule.size * 2)
        for (rank in 0 until window.detailsPerChannel) {
            for ((key, list) in schedule) {
                // A channel whose earlier programme did not fit keeps none after it.
                if ((keep[key] ?: 0) != rank) continue
                val programme = list.getOrNull(firstAhead.getValue(key) + rank) ?: continue
                val size = (programme.description?.length ?: 0) + (programme.image?.length ?: 0)
                if (budget < size) continue
                budget -= size
                keep[key] = rank + 1
            }
        }
        schedule.entries.forEach { entry ->
            val list = entry.value
            val from = firstAhead.getValue(entry.key)
            val until = from + (keep[entry.key] ?: 0)
            if (list.indices.none { (it < from || it >= until) && list[it].hasDetails() }) return@forEach
            entry.setValue(
                list.mapIndexed { index, programme ->
                    if ((index < from || index >= until) && programme.hasDetails()) programme.copy(description = null, image = null) else programme
                },
            )
        }
    }

    private fun LiveTvProgramme.hasDetails(): Boolean = description != null || image != null
}

/**
 * When the guide must be read again: when the first channel whose kept programmes were cut short
 * reaches the end of them, so "now playing" never runs dry. Channels whose guide simply ends there
 * gain nothing from reading it sooner, unless the whole guide ends before the next refresh (a
 * portal's guide covers only the hours asked for): then it is read, and fetched, again as it runs out.
 */
/**
 * A channel's programmes ([sorted] by start) so that one is on at a time, as the guide grid shows
 * them. Guides overlap: an entry running into the next, a long placeholder ("To Be Announced"
 * all day) with the real programmes inside it, two feeds merged into one. The later entry gets
 * its time and the earlier one keeps what is left before it (and after it, when it lies inside);
 * the same show listed twice is kept once. Without this, the player (what is on now, replays,
 * the channel's programme list) found the placeholder where the grid showed the programme.
 */
internal fun liveTvWithoutOverlaps(sorted: List<LiveTvProgramme>): List<LiveTvProgramme> {
    var overlaps = false
    var latestStop = Long.MIN_VALUE
    for (programme in sorted) {
        if (programme.startEpochMs < latestStop) {
            overlaps = true
            break
        }
        latestStop = maxOf(latestStop, programme.stopEpochMs)
    }
    // The usual case: nothing to do, nothing copied.
    if (!overlaps) return sorted
    val out = ArrayList<LiveTvProgramme>(sorted.size + 2)
    for (programme in sorted) {
        val start = programme.startEpochMs
        val stop = programme.stopEpochMs
        if (stop <= start) continue
        val last = out.lastOrNull()
        if (last != null && last.stopEpochMs > start && last.isSameShow(programme)) {
            if (stop > last.stopEpochMs) out[out.size - 1] = last.copy(stopEpochMs = stop)
            continue
        }
        // Programmes kept so far that this one overlaps all end after its start, and the list
        // is in order with no overlaps, so they are at its end.
        var i = out.size - 1
        while (i >= 0 && out[i].stopEpochMs > start) {
            val kept = out[i]
            if (kept.startEpochMs < stop) {
                out.removeAt(i)
                if (kept.stopEpochMs - stop >= OVERLAP_MIN_PIECE_MS) out.add(i, kept.copy(startEpochMs = stop))
                if (start - kept.startEpochMs >= OVERLAP_MIN_PIECE_MS) out.add(i, kept.copy(stopEpochMs = start))
            }
            i--
        }
        var at = out.size
        while (at > 0 && out[at - 1].startEpochMs >= start) at--
        out.add(at, programme)
    }
    return out
}

/** Pieces left of an overlapped programme shorter than this are dropped. */
private const val OVERLAP_MIN_PIECE_MS = 60_000L

private fun LiveTvProgramme.isSameShow(other: LiveTvProgramme): Boolean =
    title === other.title || title.trim().equals(other.title.trim(), ignoreCase = true)

internal fun nextScheduleReadAt(
    schedule: LiveTvSchedule,
    truncated: Set<String>,
    nowEpochMs: Long,
    minGapMs: Long,
    maxGapMs: Long,
): Long {
    val cutRunsOut = truncated
        .mapNotNull { schedule[it]?.lastOrNull()?.stopEpochMs }
        .minOrNull()
    var guideEnds: Long? = null
    for (programmes in schedule.values) {
        val stop = programmes.lastOrNull()?.stopEpochMs ?: continue
        if (guideEnds == null || stop > guideEnds) guideEnds = stop
    }
    val runsOut = listOfNotNull(cutRunsOut, guideEnds).minOrNull() ?: (nowEpochMs + maxGapMs)
    return runsOut.coerceIn(nowEpochMs + minGapMs, nowEpochMs + maxGapMs)
}

/**
 * The index of the first programme ending after [epochMs] (size when none), found by halving: a
 * schedule is in time order without overlaps, and catch-up channels keep days of past programmes.
 */
internal fun List<LiveTvProgramme>.firstEndingAfter(epochMs: Long): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (this[middle].stopEpochMs > epochMs) high = middle else low = middle + 1
    }
    return low
}

/**
 * When the programme on air next changes for any of [keys] (one ends, or one starts where nothing
 * was on): until then [currentProgrammes] gives the same answer. Long.MAX_VALUE when it never does.
 */
internal fun nextProgrammeChange(
    schedule: LiveTvSchedule,
    keys: Collection<String>,
    nowEpochMs: Long,
): Long {
    var next = Long.MAX_VALUE
    if (schedule.isEmpty()) return next
    for (key in keys) {
        val programmes = schedule[key] ?: continue
        val programme = programmes.getOrNull(programmes.firstEndingAfter(nowEpochMs)) ?: continue
        val change = if (programme.startEpochMs <= nowEpochMs) programme.stopEpochMs else programme.startEpochMs
        if (change < next) next = change
    }
    return next
}

/** The programme on air at [nowEpochMs] for each of [keys]. */
internal fun currentProgrammes(
    schedule: LiveTvSchedule,
    keys: Collection<String>,
    nowEpochMs: Long,
): Map<String, LiveTvProgramme> {
    if (schedule.isEmpty()) return emptyMap()
    val current = HashMap<String, LiveTvProgramme>()
    for (key in keys) {
        val programmes = schedule[key] ?: continue
        val programme = programmes.getOrNull(programmes.firstEndingAfter(nowEpochMs)) ?: continue
        if (nowEpochMs >= programme.startEpochMs) current[key] = programme
    }
    return current
}

/** Quality and format words that differ between a playlist's and a guide's name of one channel. */
private val NAME_NOISE = hashSetOf(
    "hd", "fhd", "uhd", "sd", "hq", "4k", "8k", "hevc", "h265", "h264", "1080p", "1080i", "720p", "576p", "50fps", "60fps",
    "1080", "720", "2160", "hd+",
)
/** Of [NAME_NOISE], the words a channel's guide key ([liveTvGuideKey]) keeps, as it did before they were matched. */
private val NAME_NOISE_IN_KEYS = hashSetOf("1080", "720", "2160", "hd+")

/** The leading country tag of [name] in lower case ("uk" for "UK: BBC One"), or null. */
internal fun liveTvNameTag(name: String): String? {
    if (name.indexOfAny(NAME_TAG_ENDS) < 0) return null
    val tag = NAME_TAG.find(name)?.value ?: return null
    return tag.filter(Char::isLetter).lowercase()
}

/** A leading country tag: "UK:", "UK |", "|UK|", "[UK]", "(UK)", "UK - ". */
private val NAME_TAG = Regex("""^\s*(?:[\[(|]\s*[A-Za-z]{2,3}\s*[\])|]|[A-Za-z]{2,3}\s*[:|]|[A-Z]{2}\s+-\s+)\s*""")
private val NAME_TAG_ENDS = charArrayOf(':', '|', ']', ')', '-')
/** [NAME_TAG] without "UK - ", for guide keys: see [liveTvGuideKey]. */
private val KEY_NAME_TAG = Regex("""^\s*(?:[\[(|]\s*[A-Za-z]{2,3}\s*[\])|]|[A-Za-z]{2,3}\s*[:|])\s*""")
/**
 * A country code or audio note in brackets after the name ("BBC One (UK)", "RTL [Multi-Audio]"). Not
 * a region ("BBC One (East)") nor "(+1)": those are other channels, with guides of their own.
 */
private val NAME_BRACKETED = Regex("""\s*[\[(]\s*(?:[A-Za-z]{2,3}|multi[\s\-]?(?:audio|sub|lang)|backup|vip)\s*[\])]""", RegexOption.IGNORE_CASE)

/**
 * A channel name reduced for matching a playlist's name with a guide's: lower case, no country
 * tag, no quality words, letters and digits only ("UK: BBC One HD" and "BBC One" are both "bbcone").
 */
internal fun liveTvNameKey(raw: String): String = nameKey(raw, forGuideKey = false)

/**
 * [liveTvNameKey], or with [forGuideKey] the form a channel's guide key has always had: keys are
 * saved (the catch-up archive), so finding more guides by name must not change them.
 */
private fun nameKey(raw: String, forGuideKey: Boolean): String {
    val name = if (forGuideKey) raw.composed() else raw.folded()
    // Every tag ends in one of these: most names have none and skip the pattern (it runs per channel and per guide name).
    val tagless = when {
        name.indexOfAny(NAME_TAG_ENDS) < 0 -> name
        forGuideKey -> KEY_NAME_TAG.replaceFirst(name, "")
        else -> NAME_TAG.replaceFirst(name, "")
    }
    val untagged = if (forGuideKey || (tagless.indexOf('(') < 0 && tagless.indexOf('[') < 0)) {
        tagless
    } else {
        NAME_BRACKETED.replace(tagless, " ").ifBlank { tagless }
    }
    val out = StringBuilder(untagged.length)
    var word = StringBuilder()
    fun flush() {
        if (word.isNotEmpty()) {
            val token = word.toString()
            if (token !in NAME_NOISE || (forGuideKey && token in NAME_NOISE_IN_KEYS)) out.append(token)
            word = StringBuilder()
        }
    }
    untagged.forEach { char ->
        when {
            char.isLetterOrDigit() -> word.append(char.lowercaseChar())
            // "+1" is a different channel from the one it shifts.
            char == '+' -> word.append(char)
            else -> flush()
        }
    }
    flush()
    return out.toString()
}

/**
 * An XMLTV id as matched: trimmed, lower case, accents in one form. A playlist and a guide made by
 * different tools can write "één.be" with the accent as its own mark, which looks the same.
 */
internal fun liveTvGuideId(id: String): String = id.trim().composed().lowercase()

/**
 * [this] without accents and in plain forms ("Télé" as "Tele", "ᴴᴰ" as "HD"), for names written by
 * different tools; plain ASCII, nearly every name, is returned as is.
 */
private fun String.folded(): String =
    if (all { it < '\u0080' }) this else COMBINING_MARKS.replace(java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFKD), "")

private val COMBINING_MARKS = Regex("""\p{Mn}+""")

/** [this] in Unicode's composed form (NFC); plain ASCII, nearly every id and name, is returned as is. */
private fun String.composed(): String =
    if (all { it < '\u0080' }) this else java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFC)

/** The key a channel's guide is kept under: its guide id in lower case, else its name. */
internal fun liveTvGuideKey(tvgId: String?, name: String, sourceId: String = ""): String {
    val key = tvgId?.trim()?.takeIf(String::isNotEmpty)?.lowercase() ?: (NAME_KEY_PREFIX + nameKey(name, forGuideKey = true))
    return if (sourceId.isEmpty()) key else "$sourceId/$key"
}

/**
 * Guide keys of channels listed with the same link under different keys, one list per link
 * (usually none). Stalker channels are left out: their links are made per play.
 */
internal fun liveTvSameStreamKeys(channels: List<LiveTvChannel>): List<List<String>> {
    val firstKey = HashMap<String, String>(channels.size * 2)
    var groups: HashMap<String, LinkedHashSet<String>>? = null
    channels.forEach { channel ->
        if (channel.stalkerCommand != null) return@forEach
        val first = firstKey.putIfAbsent(channel.streamUrl, channel.guideKey) ?: return@forEach
        if (first == channel.guideKey) return@forEach
        val all = groups ?: HashMap<String, LinkedHashSet<String>>().also { groups = it }
        all.getOrPut(channel.streamUrl) { linkedSetOf(first) } += channel.guideKey
    }
    return groups?.values?.map { it.toList() }.orEmpty()
}

/** Keys of each of [groups] with no programmes get those of the first key in it that has some. */
internal fun LiveTvSchedule.sharedAcrossStreams(groups: List<List<String>>): LiveTvSchedule {
    if (groups.isEmpty()) return this
    var filled: HashMap<String, List<LiveTvProgramme>>? = null
    for (keys in groups) {
        val programmes = keys.firstNotNullOfOrNull { key -> this[key]?.takeIf { it.isNotEmpty() } } ?: continue
        for (key in keys) {
            if (this[key].isNullOrEmpty()) (filled ?: HashMap(this).also { filled = it })[key] = programmes
        }
    }
    return filled ?: this
}

/** A cell's visible span; provider timestamps never turn into unbounded layout constraints. */
internal fun liveTvGuideSpan(start: Long, stop: Long, from: Long, to: Long): Pair<Long, Long>? {
    val left = maxOf(start, from)
    val right = minOf(stop, to)
    return if (right > left) left to right else null
}

/** Never the start of a guide's channel id, so a name key can't meet one. */
private const val NAME_KEY_PREFIX = "\u0001"

internal object LiveTvClock {
    /** The locale's short time until [followDeviceHourFormat] has read the TV's 12/24-hour setting. */
    @Volatile private var clockFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    /** Follows the TV's own 24-hour setting, which the locale's format ignores (en-US set to 24 h). */
    fun followDeviceHourFormat(context: android.content.Context) {
        val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        clockFormatter = DateTimeFormatter.ofPattern(pattern, java.util.Locale.getDefault())
    }

    fun nowEpochMs(): Long = System.currentTimeMillis()

    /** The TV's own time format (13:00 or 1:00 PM) in its time zone. */
    fun formatClock(epochMs: Long): String =
        clockFormatter.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    /** "21:00 – 22:30". */
    fun formatSpan(programme: LiveTvProgramme): String =
        "${formatClock(programme.startEpochMs)} – ${formatClock(programme.stopEpochMs)}"

    /**
     * XMLTV `20260927213000 +0200`, also written `+02:00`, `+0200` without the space, `Z`/`UTC`/`GMT`,
     * or with no offset (then local time). Seconds may be left out. Parsed by hand: a guide has
     * hundreds of thousands of these.
     */
    fun parseXmlTvTimestamp(value: String): Long? {
        val text = value.trim()
        var digits = 0
        while (digits < text.length && digits < 14 && text[digits].isDigit()) digits++
        if (digits != 12 && digits != 14) return null
        fun number(from: Int, length: Int): Int {
            var result = 0
            for (i in from until from + length) result = result * 10 + (text[i] - '0')
            return result
        }
        val year = number(0, 4)
        val month = number(4, 2)
        val day = number(6, 2)
        val hour = number(8, 2)
        val minute = number(10, 2)
        val second = if (digits == 14) number(12, 2) else 0
        if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null
        val local = runCatching { LocalDateTime.of(year, month, day, hour, minute, minOf(second, 59)) }.getOrNull() ?: return null
        // A fraction of a second (".000") some guides add is skipped.
        var index = digits
        if (index < text.length && text[index] == '.') {
            index++
            while (index < text.length && text[index].isDigit()) index++
        }
        val zone = text.substring(index).trim()
        val offsetSeconds = when {
            zone.isEmpty() -> return local.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            zone.equals("Z", true) || zone.equals("UTC", true) || zone.equals("GMT", true) -> 0
            zone[0] == '+' || zone[0] == '-' -> {
                // "+0200", "+02:00", "+02", also followed by a zone name ("+0200 CEST").
                val hhmm = zone.substring(1).substringBefore(' ').replace(":", "")
                if ((hhmm.length != 2 && hhmm.length != 4) || !hhmm.all(Char::isDigit)) return null
                val hours = hhmm.take(2).toInt()
                val minutes = if (hhmm.length >= 4) hhmm.substring(2, 4).toInt() else 0
                val total = hours * 3600 + minutes * 60
                if (zone[0] == '-') -total else total
            }
            // A region ("Europe/London"); abbreviations like "BST" are ambiguous and left out.
            else -> return runCatching { local.atZone(ZoneId.of(zone)).toInstant().toEpochMilli() }.getOrNull()
        }
        return (local.toEpochSecond(ZoneOffset.UTC) - offsetSeconds) * 1000L
    }
}
