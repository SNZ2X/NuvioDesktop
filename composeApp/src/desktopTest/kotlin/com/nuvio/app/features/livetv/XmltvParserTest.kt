package com.nuvio.app.features.livetv

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XmltvParserTest {
    private val now = Instant.parse("2026-03-01T12:00:00Z").toEpochMilli()

    private fun channels(vararg pairs: Pair<String, String?>): List<LiveTvChannel> = pairs.mapIndexed { index, (id, epgId) ->
        LiveTvChannel(
            id = id,
            sourceId = "source",
            name = "Channel $index",
            streamUrl = "https://example.com/$index.ts",
            epgId = epgId,
        )
    }

    private fun parse(xml: String, vararg channels: LiveTvChannel) =
        XmltvParser.parse(xml.byteInputStream(), channels.toList(), now)

    @Test
    fun mapsProgrammesToSourceQualifiedChannelIdsAndSortsThem() {
        val channel = channels("lt:one" to "bbc1.uk", "lt:two" to null)
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="bbc1.uk"><display-name>BBC One</display-name></channel>
              <programme start="20260301140000 +0000" stop="20260301150000 +0000" channel="bbc1.uk">
                <title>Later</title>
                <desc>Second
                     line</desc>
              </programme>
              <programme start="20260301120000 +0000" stop="20260301130000 +0000" channel="bbc1.uk">
                <title>Now</title>
              </programme>
              <programme start="20260301120000 +0000" stop="20260301130000 +0000" channel="unknown.channel">
                <title>Unmatched</title>
              </programme>
            </tv>
        """.trimIndent()

        val result = parse(xml, *channel.toTypedArray())

        assertEquals(setOf("lt:one"), result.keys)
        val programmes = result.getValue("lt:one")
        assertEquals(listOf("Now", "Later"), programmes.map { it.title })
        assertEquals(Instant.parse("2026-03-01T12:00:00Z").toEpochMilli(), programmes[0].startEpochMs)
        assertEquals(Instant.parse("2026-03-01T13:00:00Z").toEpochMilli(), programmes[0].endEpochMs)
        assertEquals("Second line", programmes[1].description)
    }

    @Test
    fun timestampsHonourZonesAndTreatMissingZoneAsUtc() {
        fun programme(start: String, stop: String): Long? {
            val xml = """
                <tv>
                  <programme start="$start" stop="$stop" channel="c"><title>T</title></programme>
                </tv>
            """.trimIndent()
            return parse(xml, *channels("lt:c" to "c").toTypedArray())["lt:c"]?.single()?.startEpochMs
        }

        assertEquals(Instant.parse("2026-03-01T10:00:00Z").toEpochMilli(), programme("20260301120000 +0200", "20260301130000 +0200"))
        assertEquals(Instant.parse("2026-03-01T17:30:00Z").toEpochMilli(), programme("20260301120000 -0530", "20260301130000 -0530"))
        assertEquals(Instant.parse("2026-03-01T12:00:00Z").toEpochMilli(), programme("20260301120000 Z", "20260301130000 Z"))
        assertEquals(Instant.parse("2026-03-01T12:00:00Z").toEpochMilli(), programme("20260301120000", "20260301130000"))
        assertEquals(Instant.parse("2026-03-01T10:00:00Z").toEpochMilli(), programme("20260301120000 +02:00", "20260301130000 +02:00"))

        // Malformed values are discarded rather than defaulted to "now".
        assertNull(programme("2026-03-01T12:00:00Z", "20260301130000 +0000"))
        assertNull(programme("20260301120000 +9999", "20260301130000 +0000"))
        assertNull(programme("20260230120000 +0000", "20260230130000 +0000"))
    }

    @Test
    fun discardsInvalidIntervalsAndOutOfWindowProgrammes() {
        val xml = """
            <tv>
              <programme start="20260301120000 +0000" stop="20260301120000 +0000" channel="c"><title>Zero length</title></programme>
              <programme start="20260301130000 +0000" stop="20260301120000 +0000" channel="c"><title>Backwards</title></programme>
              <programme start="20260201000000 +0000" stop="20260202000000 +0000" channel="c"><title>Too old</title></programme>
              <programme start="20260310000000 +0000" stop="20260310010000 +0000" channel="c"><title>Too far</title></programme>
              <programme start="20260301060000 +0000" stop="20260301070000 +0000" channel="c"><title>Edge before</title></programme>
              <programme start="20260301115000 +0000" stop="20260301125000 +0000" channel="c"><title>Inside</title></programme>
              <programme start="20260303110000 +0000" stop="20260303120000 +0000" channel="c"><title>Edge after</title></programme>
            </tv>
        """.trimIndent()

        val titles = parse(xml, *channels("lt:c" to "c").toTypedArray())
            .getValue("lt:c")
            .map { it.title }

        assertEquals(listOf("Edge before", "Inside", "Edge after"), titles)
    }

    @Test
    fun sharedEpgIdFeedsEveryChannelAndTextIsBounded() {
        val shared = channels("lt:a" to "shared.id", "lt:b" to "shared.id")
        val longTitle = "T".repeat(900)
        val longDescription = "D".repeat(5_000)
        val xml = """
            <tv>
              <programme start="20260301120000 +0000" stop="20260301130000 +0000" channel="shared.id">
                <title>$longTitle</title>
                <desc>$longDescription</desc>
              </programme>
            </tv>
        """.trimIndent()

        val result = parse(xml, *shared.toTypedArray())

        assertEquals(setOf("lt:a", "lt:b"), result.keys)
        val programme = result.getValue("lt:a").single()
        assertEquals(512, programme.title.length)
        assertEquals(2_048, programme.description.length)
        assertEquals(programme, result.getValue("lt:b").single())
    }

    @Test
    fun standardXmltvDocumentTypesAreAcceptedWithoutFetchingTheirExternalSubset() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/xmltv.dtd") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            val dtd = "http://127.0.0.1:${server.address.port}/xmltv.dtd"
            for (declaration in listOf(
                "<!DOCTYPE tv>",
                """<!DOCTYPE tv SYSTEM "$dtd">""",
                "<!DOCTYPE tv PUBLIC '-//XMLTV//DTD XMLTV//EN' '$dtd'>",
            )) {
                val xml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    $declaration
                    <tv><programme channel="c" start="20260301120000 +0000" stop="20260301130000 +0000"><title>Now</title></programme></tv>
                """.trimIndent()
                assertEquals("Now", parse(xml, *channels("lt:c" to "c").toTypedArray())["lt:c"]?.single()?.title)
            }
            assertEquals(0, requests.get(), "A guide declaration must never fetch a remote DTD")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun internalSubsetsAndExternalEntitiesFailClosed() {
        val xxe = """
            <?xml version="1.0"?>
            <!DOCTYPE tv [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <tv>
              <programme start="20260301120000 +0000" stop="20260301130000 +0000" channel="c">
                <title>&xxe;</title>
              </programme>
            </tv>
        """.trimIndent()

        val failure = assertFailsWith<LiveTvParseException> {
            parse(xxe, *channels("lt:c" to "c").toTypedArray())
        }
        assertTrue(
            failure.message == LIVE_TV_XMLTV_UNSAFE || failure.message == LIVE_TV_XMLTV_INVALID,
            "unexpected message: ${failure.message}",
        )

        val laughs = """
            <?xml version="1.0"?>
            <!DOCTYPE tv [
              <!ENTITY a "aaaaaaaaaa">
              <!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">
              <!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;">
            ]>
            <tv><programme start="20260301120000 +0000" stop="20260301130000 +0000" channel="c"><title>&c;</title></programme></tv>
        """.trimIndent()

        assertFailsWith<LiveTvParseException> {
            parse(laughs, *channels("lt:c" to "c").toTypedArray())
        }
    }

    @Test
    fun excessiveNestingIsRejected() {
        val depth = 80
        val xml = buildString {
            append("<tv>")
            repeat(depth) { append("<wrapper>") }
            append("<programme start=\"20260301120000 +0000\" stop=\"20260301130000 +0000\" channel=\"c\"><title>T</title></programme>")
            repeat(depth) { append("</wrapper>") }
            append("</tv>")
        }

        val failure = assertFailsWith<LiveTvParseException> {
            parse(xml, *channels("lt:c" to "c").toTypedArray())
        }
        assertEquals(LIVE_TV_XMLTV_INVALID, failure.message)
    }

    @Test
    fun malformedXmlAndMissingTitleUseFixedErrors() {
        val failure = assertFailsWith<LiveTvParseException> {
            parse("<tv><programme channel=\"c\">", *channels("lt:c" to "c").toTypedArray())
        }
        assertEquals(LIVE_TV_XMLTV_INVALID, failure.message)

        val noTitle = """
            <tv>
              <programme start="20260301120000 +0000" stop="20260301130000 +0000" channel="c">
                <desc>No title here</desc>
              </programme>
            </tv>
        """.trimIndent()
        assertTrue(parse(noTitle, *channels("lt:c" to "c").toTypedArray()).isEmpty())
    }

    @Test
    fun channelsLaterThanTheOldProgrammeScanLimitStillReceiveTheirGuide() {
        val parts = sequence {
            yield("<tv>")
            repeat(200_001) {
                yield("""<programme channel="not-subscribed" start="20260301120000 +0000" stop="20260301130000 +0000"><title>Other</title></programme>""")
            }
            yield("""<programme channel="c" start="20260301120000 +0000" stop="20260301130000 +0000"><title>Current</title></programme></tv>""")
        }.iterator()
        val input = java.io.SequenceInputStream(object : java.util.Enumeration<java.io.InputStream> {
            override fun hasMoreElements() = parts.hasNext()
            override fun nextElement() = parts.next().byteInputStream()
        })
        val result = input.use { XmltvParser.parse(it, channels("channel" to "c"), now) }
        assertEquals("Current", result.getValue("channel").single().title)
    }

    @Test
    fun retainedGuideIsBoundedAndKeepsCurrentAndNearestFutureEvenWhenUnsorted() {
        val format = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(java.time.ZoneOffset.UTC)
        fun stamp(value: Long) = format.format(Instant.ofEpochMilli(value))
        val xml = buildString {
            append("<tv>")
            for (index in 100 downTo -10) {
                val start = now + index * 1_800_000L
                append("""<programme channel="c" start="${stamp(start)}" stop="${stamp(start + 1_800_000)}"><title>Programme $index</title></programme>""")
            }
            append("</tv>")
        }
        val result = parse(xml, *channels("channel" to "c").toTypedArray()).getValue("channel")
        assertEquals(48, result.size)
        assertEquals("Programme 0", result.first().title)
        assertEquals("Programme 1", result[1].title)
        assertEquals("Programme 47", result.last().title)
    }

    @Test
    fun cancellationWhileReadingGuideIsNotReportedAsInvalidXml() {
        val xml = """<tv><programme channel="c" start="20260301120000 +0000" stop="20260301130000 +0000"><title>Current</title></programme></tv>"""
        val input = object : java.io.FilterInputStream(xml.byteInputStream()) {
            private var total = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (total > 70) throw kotlinx.coroutines.CancellationException("Synthetic cancellation")
                return super.read(buffer, offset, minOf(length, 8)).also { if (it > 0) total += it }
            }
        }
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            input.use { XmltvParser.parse(it, channels("channel" to "c"), now) }
        }
    }

    @Test
    fun noChannelsMeansNoWork() {
        val result = XmltvParser.parse(
            ByteArrayInputStream(ByteArray(0)),
            emptyList(),
            now,
        )
        assertTrue(result.isEmpty())
    }
}
