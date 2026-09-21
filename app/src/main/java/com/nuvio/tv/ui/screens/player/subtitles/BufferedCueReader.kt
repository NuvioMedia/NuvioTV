package com.nuvio.tv.ui.screens.player.subtitles

import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup

/**
 * Reads the cues ExoPlayer's text renderer has already decoded but not yet displayed, by reflecting
 * into its internal cue buffer. Used for AI pre-translation lookahead.
 */
internal object BufferedCueReader {

    private val BRACKET_REGEX = Regex("""\[.*?\]""")
    private val MUSIC_REGEX = Regex("[♪♫]+")

    private val FIELD_CACHE = java.util.concurrent.ConcurrentHashMap<String, java.lang.reflect.Field>()
    private val MISSING_FIELDS: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /**
     * Text of the cues currently in the renderer's buffer, at most [maxCount].
     */
    fun allCueTexts(renderer: Any, removeHI: Boolean, maxCount: Int = Int.MAX_VALUE): List<String> {
        val texts = mutableSetOf<String>()

        try {
            val resolverField = findField(renderer.javaClass, "cuesResolver")
            val resolver = resolverField?.get(renderer)
            if (resolver != null) {
                var extracted = false
                for (candidate in listOf(
                    "cuesWithTimingList", "cuesWithTimings",
                    "cueGroupsByStartTime", "cueGroups", "cueGroupList", "groups"
                )) {
                    val f = findField(resolver.javaClass, candidate) ?: continue
                    val v = f.get(resolver) ?: continue
                    val count = extractFromCollectionOrMap(v, texts, removeHI, maxCount)
                    if (count > 0) {
                        extracted = true
                        break
                    }
                }
                if (!extracted) {
                    var cls: Class<*>? = resolver.javaClass
                    outer@ while (cls != null && cls != Any::class.java) {
                        for (f in cls.declaredFields) {
                            try {
                                f.isAccessible = true
                                val v = f.get(resolver) ?: continue
                                if (extractFromCollectionOrMap(v, texts, removeHI, maxCount) > 0) break@outer
                            } catch (_: Exception) {
                            }
                        }
                        cls = cls.superclass
                    }
                }
            }
        } catch (_: Exception) {
        }

        if (texts.isNotEmpty()) return texts.take(maxCount)

        fun extractFromSubtitleField(fieldName: String) {
            try {
                if (texts.size >= maxCount) return
                val field = findField(renderer.javaClass, fieldName) ?: return
                val subtitle = field.get(renderer) ?: return
                val getEventTimeCount = subtitle.javaClass.getMethod("getEventTimeCount")
                val getEventTime = subtitle.javaClass.getMethod("getEventTime", Int::class.java)
                val getCues = subtitle.javaClass.getMethod("getCues", Long::class.java)
                val count = getEventTimeCount.invoke(subtitle) as Int
                for (i in 0 until count) {
                    if (texts.size >= maxCount) break
                    val timeUs = getEventTime.invoke(subtitle, i) as Long
                    @Suppress("UNCHECKED_CAST")
                    val cues = getCues.invoke(subtitle, timeUs) as? List<Cue> ?: continue
                    val joined = joinCues(cues, removeHI)
                    if (joined.isNotBlank()) texts.add(joined)
                }
            } catch (_: Exception) {
            }
        }
        extractFromSubtitleField("subtitle")
        extractFromSubtitleField("nextSubtitle")
        return texts.take(maxCount)
    }

    private fun extractFromCollectionOrMap(
        v: Any,
        texts: MutableSet<String>,
        removeHI: Boolean,
        maxCount: Int = Int.MAX_VALUE,
    ): Int {
        val items: Collection<*> = when (v) {
            is Map<*, *> -> v.values
            is Collection<*> -> v
            else -> return 0
        }
        var count = 0
        for (item in items) {
            if (texts.size >= maxCount) break
            if (extractCueGroupTexts(item, texts, removeHI)) count++
        }
        return count
    }

    private fun extractCueGroupTexts(obj: Any?, texts: MutableSet<String>, removeHI: Boolean): Boolean {
        if (obj == null) return false
        if (obj is CueGroup) {
            val joined = joinCues(obj.cues, removeHI)
            if (joined.isNotBlank()) texts.add(joined)
            return true
        }
        if (obj is List<*>) {
            val cues = obj.filterIsInstance<Cue>()
            val joined = joinCues(cues, removeHI)
            if (joined.isNotBlank()) texts.add(joined)
            return obj.isNotEmpty()
        }
        try {
            val cuesField = findField(obj.javaClass, "cues")
            val cues = cuesField?.get(obj)
            if (cues is List<*>) {
                val joined = joinCues(cues.filterIsInstance<Cue>(), removeHI)
                if (joined.isNotBlank()) texts.add(joined)
                return cues.isNotEmpty()
            }
        } catch (_: Exception) {
        }
        return false
    }

    private fun joinCues(cues: List<Cue>, removeHI: Boolean): String =
        cues.mapNotNull { it.text?.toString()?.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .let { if (removeHI) stripHI(it) else it }

    private fun stripHI(text: String): String =
        text.replace(BRACKET_REGEX, "")
            .replace(MUSIC_REGEX, "")
            .trim()

    private fun findField(startClass: Class<*>, name: String): java.lang.reflect.Field? {
        val key = startClass.name + '#' + name
        FIELD_CACHE[key]?.let { return it }
        if (MISSING_FIELDS.contains(key)) return null
        var cls: Class<*>? = startClass
        while (cls != null && cls != Any::class.java) {
            try {
                val f = cls.getDeclaredField(name)
                f.isAccessible = true
                FIELD_CACHE[key] = f
                return f
            } catch (_: NoSuchFieldException) {
            }
            cls = cls.superclass
        }
        MISSING_FIELDS.add(key)
        return null
    }
}
