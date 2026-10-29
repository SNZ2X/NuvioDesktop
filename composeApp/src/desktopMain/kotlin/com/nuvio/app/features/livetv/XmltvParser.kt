package com.nuvio.app.features.livetv

import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.xml.stream.FactoryConfigurationError
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader
import kotlinx.coroutines.CancellationException

internal const val LIVE_TV_XMLTV_INVALID: String = "EPG document is invalid"
internal const val LIVE_TV_XMLTV_UNSAFE: String = "EPG document uses a forbidden DTD"

/**
 * Streaming XMLTV parser.
 *
 * Only `channel` and `programme` elements are consumed; everything else is skipped without
 * buffering. External DTD loading and entities are disabled before creating the reader.
 * Standard XMLTV document-type declarations are accepted without loading their DTD;
 * internal subsets and entity references are rejected. Depth, programme count and text
 * lengths are bounded.
 */
internal object XmltvParser {
    private const val WINDOW_BEFORE_MS = 6L * 60 * 60 * 1000
    private const val WINDOW_AFTER_MS = 48L * 60 * 60 * 1000
    private const val MAX_DEPTH = 32
    /** Bound on scanned programme elements, whether or not they are kept. */
    private const val MAX_PROGRAMMES = 2_000_000
    private const val MAX_PROGRAMMES_PER_CHANNEL = 48
    private const val MAX_CHANNELS = 50_000
    private const val MAX_TEXT_LENGTH = 2_048
    private const val MAX_TITLE_LENGTH = 512

    fun parse(
        input: InputStream,
        channels: List<LiveTvChannel>,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Map<String, List<LiveTvProgramme>> {
        if (channels.isEmpty()) return emptyMap()

        val targets = channelTargets(channels)
        val programmes = HashMap<String, MutableList<LiveTvProgramme>>()
        val windowStart = nowEpochMs - WINDOW_BEFORE_MS
        val windowEnd = nowEpochMs + WINDOW_AFTER_MS
        // Keep current and earliest upcoming entries before filling any spare history slots.
        val priority = compareBy<LiveTvProgramme>(
            { when {
                it.startEpochMs <= nowEpochMs && it.endEpochMs > nowEpochMs -> 0
                it.startEpochMs > nowEpochMs -> 1
                else -> 2
            } },
            { if (it.endEpochMs <= nowEpochMs) -it.endEpochMs else it.startEpochMs },
            { it.endEpochMs },
        )

        val reader = try {
            secureFactory().createXMLStreamReader(input)
        } catch (failure: XMLStreamException) {
            rethrowXmltvReadFailure(failure)
        } catch (_: IllegalArgumentException) {
            throw LiveTvParseException(LIVE_TV_XMLTV_INVALID)
        }

        try {
            var depth = 0
            var scanned = 0
            var currentChannelId: String? = null
            var currentStart: String? = null
            var currentStop: String? = null
            var currentTitle: String? = null
            var currentDescription: String? = null
            var elementDepth = -1
            var textDepth = -1
            var textName: String? = null
            val text = StringBuilder()

            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.DTD -> {
                        // XMLTV exports commonly declare xmltv.dtd. The disabled DTD/entity
                        // settings and rejecting resolver prevent loading it; never accept an
                        // internal subset that could declare entities or defaults.
                        if (!SAFE_XMLTV_DOCTYPE.matches(reader.text.orEmpty().trim())) {
                            throw LiveTvParseException(LIVE_TV_XMLTV_UNSAFE)
                        }
                    }
                    XMLStreamConstants.ENTITY_REFERENCE -> throw LiveTvParseException(LIVE_TV_XMLTV_UNSAFE)

                    XMLStreamConstants.START_ELEMENT -> {
                        depth++
                        if (depth > MAX_DEPTH) throw LiveTvParseException(LIVE_TV_XMLTV_INVALID)
                        when (reader.localName) {
                            "programme" -> {
                                if (elementDepth >= 0) throw LiveTvParseException(LIVE_TV_XMLTV_INVALID)
                                currentChannelId = reader.attribute("channel")?.trim()?.takeIf { it.isNotEmpty() }
                                currentStart = reader.attribute("start")
                                currentStop = reader.attribute("stop")
                                currentTitle = null
                                currentDescription = null
                                elementDepth = depth
                            }

                            "title", "desc" -> if (elementDepth >= 0 && textDepth < 0) {
                                textDepth = depth
                                textName = reader.localName
                                text.setLength(0)
                            }
                        }
                    }

                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        if (textDepth >= 0 && text.length < MAX_TEXT_LENGTH) {
                            text.append(reader.text.take(MAX_TEXT_LENGTH - text.length))
                        }
                    }

                    XMLStreamConstants.END_ELEMENT -> {
                        val name = reader.localName
                        if (elementDepth >= 0 && depth == elementDepth && name == "programme") {
                            programme(
                                sourceChannelId = currentChannelId,
                                start = currentStart,
                                stop = currentStop,
                                title = currentTitle,
                                description = currentDescription,
                                targets = targets,
                                windowStart = windowStart,
                                windowEnd = windowEnd,
                                into = programmes,
                                priority = priority,
                            )
                            scanned++
                            if (scanned > MAX_PROGRAMMES) throw LiveTvParseException("EPG programme limit exceeded")
                            elementDepth = -1
                            textDepth = -1
                            textName = null
                        } else if (textDepth >= 0 && depth == textDepth) {
                            val value = boundedText(text, if (textName == "title") MAX_TITLE_LENGTH else MAX_TEXT_LENGTH)
                            if (textName == "title") {
                                if (currentTitle == null) currentTitle = value
                            } else {
                                if (currentDescription == null) currentDescription = value
                            }
                            textDepth = -1
                            textName = null
                        }
                        depth--
                        if (depth < 0) throw LiveTvParseException(LIVE_TV_XMLTV_INVALID)
                    }
                }
            }
        } catch (failure: LiveTvParseException) {
            throw failure
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: XMLStreamException) {
            rethrowXmltvReadFailure(failure)
        } catch (_: IllegalStateException) {
            throw LiveTvParseException(LIVE_TV_XMLTV_INVALID)
        } finally {
            try {
                reader.close()
            } catch (_: Exception) {
                // Closing a broken reader cannot change the already-decided outcome.
            }
        }

        if (programmes.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, List<LiveTvProgramme>>(programmes.size)
        for ((channelId, list) in programmes) {
            result[channelId] = list.sortedWith(compareBy({ it.startEpochMs }, { it.endEpochMs }))
        }
        return result
    }

    private fun programme(
        sourceChannelId: String?,
        start: String?,
        stop: String?,
        title: String?,
        description: String?,
        targets: Map<String, List<String>>,
        windowStart: Long,
        windowEnd: Long,
        into: MutableMap<String, MutableList<LiveTvProgramme>>,
        priority: Comparator<LiveTvProgramme>,
    ) {
        if (sourceChannelId == null) return
        val channelIds = targets[sourceChannelId] ?: return
        val cleanTitle = title?.takeIf { it.isNotBlank() } ?: return
        val startMs = parseTimestamp(start) ?: return
        val stopMs = parseTimestamp(stop) ?: return
        if (stopMs <= startMs) return
        if (stopMs < windowStart || startMs > windowEnd) return

        val programme = LiveTvProgramme(
            title = cleanTitle,
            startEpochMs = startMs,
            endEpochMs = stopMs,
            description = description.orEmpty(),
        )
        for (channelId in channelIds) {
            val entries = into.getOrPut(channelId) { ArrayList() }
            if (entries.size >= MAX_PROGRAMMES_PER_CHANNEL && priority.compare(programme, entries.last()) >= 0) continue
            val found = entries.binarySearch(programme, priority)
            if (found >= 0 && entries[found] == programme) continue
            entries.add(if (found < 0) -found - 1 else found, programme)
            if (entries.size > MAX_PROGRAMMES_PER_CHANNEL) entries.removeAt(entries.lastIndex)
        }
    }

    /** XMLTV `channel` elements are optional; channels carried over from the playlist are enough. */
    private fun channelTargets(channels: List<LiveTvChannel>): Map<String, List<String>> {
        val targets = HashMap<String, MutableList<String>>(channels.size)
        var declared = 0
        for (channel in channels) {
            val epgId = channel.epgId?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (declared >= MAX_CHANNELS) break
            targets.getOrPut(epgId) { ArrayList(1) }.add(channel.id)
            declared++
        }
        return targets
    }

    /**
     * XMLTV timestamps are `yyyyMMddHHmmss` with an optional zone: `Z`, `+HHMM`, `-HHMM`
     * or `+HH:MM`. A missing zone is UTC, as the XMLTV specification requires.
     */
    internal fun parseTimestamp(value: String?): Long? {
        val raw = value?.trim() ?: return null
        if (raw.length < 14) return null
        val digits = raw.substring(0, 14)
        if (!digits.all { it in '0'..'9' }) return null

        val year = digits.substring(0, 4).toIntOrNull() ?: return null
        val month = digits.substring(4, 6).toIntOrNull() ?: return null
        val day = digits.substring(6, 8).toIntOrNull() ?: return null
        val hour = digits.substring(8, 10).toIntOrNull() ?: return null
        val minute = digits.substring(10, 12).toIntOrNull() ?: return null
        val second = digits.substring(12, 14).toIntOrNull() ?: return null

        var offsetSeconds = 0
        val zone = raw.substring(14).trim()
        if (zone.isNotEmpty() && !zone.equals("Z", ignoreCase = true)) {
            val sign = when (zone[0]) {
                '+' -> 1
                '-' -> -1
                else -> return null
            }
            val rest = zone.substring(1).replace(":", "")
            if (rest.length != 4 || !rest.all { it in '0'..'9' }) return null
            val offsetHours = rest.substring(0, 2).toIntOrNull() ?: return null
            val offsetMinutes = rest.substring(2, 4).toIntOrNull() ?: return null
            if (offsetHours > 14 || offsetMinutes > 59) return null
            if (offsetHours == 14 && offsetMinutes != 0) return null
            offsetSeconds = sign * (offsetHours * 3600 + offsetMinutes * 60)
        }

        return try {
            LocalDateTime.of(year, month, day, hour, minute, second)
                .toInstant(ZoneOffset.ofTotalSeconds(offsetSeconds))
                .toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    private fun boundedText(text: StringBuilder, maxLength: Int): String? {
        if (text.isEmpty()) return null
        val value = text.toString().trim().replace(WHITESPACE, " ")
        if (value.isEmpty()) return null
        return value.take(maxLength).takeIf { it.isNotBlank() }
    }

    private fun secureFactory(): XMLInputFactory {
        val factory = try {
            XMLInputFactory.newFactory()
        } catch (_: FactoryConfigurationError) {
            throw LiveTvParseException(LIVE_TV_XMLTV_INVALID)
        }
        // These two are the security boundary: if the implementation cannot disable DTDs or
        // external entities, parsing must not continue at all.
        try {
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        } catch (_: IllegalArgumentException) {
            throw LiveTvParseException(LIVE_TV_XMLTV_UNSAFE)
        }
        runCatching { factory.setProperty(XMLInputFactory.IS_COALESCING, true) }
        runCatching { factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true) }
        factory.setXMLResolver { _, _, _, _ -> throw LiveTvParseException(LIVE_TV_XMLTV_UNSAFE) }
        return factory
    }

    private fun XMLStreamReader.attribute(name: String): String? {
        for (index in 0 until attributeCount) {
            if (getAttributeLocalName(index).equals(name, ignoreCase = true)) {
                return getAttributeValue(index)
            }
        }
        return null
    }

    private val SAFE_XMLTV_DOCTYPE = Regex(
        """<!DOCTYPE\s+tv(?:\s+SYSTEM\s+(?:"[^"]*"|'[^']*')|\s+PUBLIC\s+(?:"[^"]*"|'[^']*')\s+(?:"[^"]*"|'[^']*'))?\s*>""",
    )
    private val WHITESPACE = Regex("\\s+")
}

/** StAX may wrap I/O limits or coroutine cancellation; preserve those outcomes. */
private fun rethrowXmltvReadFailure(failure: XMLStreamException): Nothing {
    var cause: Throwable? = failure
    repeat(8) {
        when (val current = cause) {
            is CancellationException -> throw current
            is LiveTvLoadException -> throw current
        }
        cause = cause?.cause
    }
    throw LiveTvParseException(LIVE_TV_XMLTV_INVALID)
}
