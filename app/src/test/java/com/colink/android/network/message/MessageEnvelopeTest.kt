package com.colink.android.network.message

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageEnvelopeTest {
    private val json = Json

    @Test
    fun serializesBusinessEnvelopeWithProtocolTypeField() {
        val envelope = BusinessEnvelope(
            type = TEXT_MESSAGE_TYPE,
            payload = json.encodeToJsonElement(
                TextMessagePayload(
                    messageId = "m1",
                    text = "hi",
                ),
            ),
        )

        val encoded = json.encodeToString(envelope)

        assertEquals(
            """{"type":"message.v1.text","payload":{"messageId":"m1","text":"hi"}}""",
            encoded,
        )
    }

    @Test
    fun serializesFileOfferPayloadInCamelCase() {
        val envelope = BusinessEnvelope(
            type = FILE_OFFER_TYPE,
            payload = json.encodeToJsonElement(
                FileOfferPayload(
                    sessionId = "s1",
                    fileName = "report.pdf",
                    fileSize = 10,
                    totalChunks = 1,
                    chunkSize = 524288,
                    checksum = "blake3:abc",
                ),
            ),
        )

        val encoded = json.encodeToString(envelope)

        assertEquals(
            """{"type":"file.v2.offer","payload":{"sessionId":"s1","fileName":"report.pdf","fileSize":10,"totalChunks":1,"chunkSize":524288,"checksum":"blake3:abc"}}""",
            encoded,
        )
    }

    @Test
    fun serializesSwimEnvelope() {
        val envelope = SwimEnvelope(
            type = "swim.ping",
            payload = SwimPayload(
                seq = 1,
                from = "device-a",
                incarnation = 2,
                gossip = listOf(
                    SwimGossip(
                        deviceId = "device-a",
                        state = "alive",
                        incarnation = 2,
                    ),
                ),
            ),
        )

        val encoded = json.encodeToString(envelope)

        assertEquals(
            """{"type":"swim.ping","payload":{"seq":1,"from":"device-a","incarnation":2,"gossip":[{"deviceId":"device-a","state":"alive","incarnation":2}]}}""",
            encoded,
        )
    }

    @Test
    fun serializesSwimPingReqEnvelope() {
        val envelope = SwimEnvelope(
            type = "swim.ping-req",
            payload = SwimPayload(
                seq = 2,
                from = "device-a",
                incarnation = 3,
                target = "device-b",
                gossip = emptyList(),
            ),
        )

        val encoded = json.encodeToString(envelope)

        assertEquals(
            """{"type":"swim.ping-req","payload":{"seq":2,"from":"device-a","incarnation":3,"target":"device-b","gossip":[]}}""",
            encoded,
        )
    }

    @Test
    fun deserializesLegacySwimEnvelopeWithoutSenderIncarnation() {
        val envelope = json.decodeFromString(
            SwimEnvelope.serializer(),
            """{"type":"swim.ping","payload":{"seq":1,"from":"device-a","gossip":[]}}""",
        )

        assertNull(envelope.payload.incarnation)
    }

    @Test
    fun serializesClipboardSyncPayload() {
        val envelope = BusinessEnvelope(
            type = CLIPBOARD_SYNC_TYPE,
            payload = json.encodeToJsonElement(
                ClipboardSyncPayload(
                    contentType = "text/plain",
                    content = "copied",
                    data = null,
                ),
            ),
        )

        val encoded = json.encodeToString(envelope)

        assertEquals(
            """{"type":"clipboard.v1.sync","payload":{"contentType":"text/plain","content":"copied"}}""",
            encoded,
        )
    }

    @Test
    fun serializesMusicTrackPayload() {
        val envelope = BusinessEnvelope(
            type = MUSIC_TRACK_TYPE,
            payload = json.encodeToJsonElement(
                MusicTrackPayload(
                    trackId = "abc123",
                    title = "Song Title",
                    artists = listOf("Artist A", "Artist B"),
                    album = "Album Name",
                    source = "ncm",
                    coverUrl = "https://example.com/cover.jpg",
                    coverData = "iVBORw0KGgoAAAANSUhEUgAA",
                    duration = 234500,
                ),
            ),
        )

        val encoded = json.encodeToString(envelope)

        assertEquals(
            """{"type":"music.v1.track","payload":{"trackId":"abc123","title":"Song Title","artists":["Artist A","Artist B"],"album":"Album Name","source":"ncm","coverUrl":"https://example.com/cover.jpg","coverData":"iVBORw0KGgoAAAANSUhEUgAA","duration":234500}}""",
            encoded,
        )
    }

    @Test
    fun serializesMusicLyricPayload() {
        val envelope = BusinessEnvelope(
            type = MUSIC_LYRIC_TYPE,
            payload = json.encodeToJsonElement(
                MusicLyricPayload(
                    trackId = "abc123",
                    lines = listOf(
                        MusicLyricLinePayload(time = 12_500, text = "First line"),
                    ),
                    translatedLines = listOf(
                        MusicLyricLinePayload(time = 12_500, text = "第一行"),
                    ),
                ),
            ),
        )

        val encoded = json.encodeToString(envelope)

        assertEquals(
            """{"type":"music.v1.lyric","payload":{"trackId":"abc123","lines":[{"time":12500,"text":"First line"}],"translatedLines":[{"time":12500,"text":"第一行"}]}}""",
            encoded,
        )
    }

    @Test
    fun serializesMusicProgressPayload() {
        val envelope = BusinessEnvelope(
            type = MUSIC_PROGRESS_TYPE,
            payload = json.encodeToJsonElement(
                MusicProgressPayload(
                    trackId = "abc123",
                    progress = 45_200,
                    paused = false,
                ),
            ),
        )

        val encoded = json.encodeToString(envelope)

        assertEquals(
            """{"type":"music.v1.progress","payload":{"trackId":"abc123","progress":45200,"paused":false}}""",
            encoded,
        )
    }

    @Test
    fun serializesMusicAlivePayload() {
        val envelope = BusinessEnvelope(
            type = MUSIC_ALIVE_TYPE,
            payload = json.encodeToJsonElement(MusicAlivePayload),
        )

        val encoded = json.encodeToString(envelope)

        assertEquals(
            """{"type":"music.v1.alive","payload":{}}""",
            encoded,
        )
    }

    @Test
    fun serializesMusicRequestPayload() {
        val envelope = BusinessEnvelope(
            type = MUSIC_REQUEST_TYPE,
            payload = json.encodeToJsonElement(MusicRequestPayload),
        )

        val encoded = json.encodeToString(envelope)

        assertEquals(
            """{"type":"music.v1.request","payload":{}}""",
            encoded,
        )
    }

    @Test
    fun serializesFileRetransmitPayload() {
        val envelope = BusinessEnvelope(
            type = FILE_RETRANSMIT_TYPE,
            payload = json.encodeToJsonElement(
                FileRetransmitPayload(
                    sessionId = "s1",
                    chunkIndex = 2,
                ),
            ),
        )

        val encoded = json.encodeToString(envelope)

        assertEquals(
            """{"type":"file.v2.retransmit","payload":{"sessionId":"s1","chunkIndex":2}}""",
            encoded,
        )
    }

    @Test
    fun serializesSystemControlCommand() {
        val envelope = BusinessEnvelope(
            type = SYSTEM_CONTROL_COMMAND_TYPE,
            payload = json.encodeToJsonElement(
                SystemControlCommandPayload(action = SystemControlAction.Shutdown.wireValue),
            ),
        )

        val encoded = json.encodeToString(envelope)

        assertEquals(
            """{"type":"system-control.v1.command","payload":{"action":"shutdown"}}""",
            encoded,
        )
    }

    @Test
    fun serializesVolumeSystemControlCommand() {
        val envelope = BusinessEnvelope(
            type = SYSTEM_CONTROL_COMMAND_TYPE,
            payload = json.encodeToJsonElement(
                SystemControlCommandPayload(
                    action = SystemControlAction.SetVolume.wireValue,
                    volume = 72,
                ),
            ),
        )

        val encoded = json.encodeToString(envelope)

        assertEquals(
            """{"type":"system-control.v1.command","payload":{"action":"set-volume","volume":72}}""",
            encoded,
        )
    }

    @Test
    fun serializesWakeOnLanSystemControlCommand() {
        val envelope = BusinessEnvelope(
            type = SYSTEM_CONTROL_COMMAND_TYPE,
            payload = json.encodeToJsonElement(
                SystemControlCommandPayload(
                    action = SystemControlAction.WakeOnLan.wireValue,
                    targetMac = "01:23:45:67:89:ab",
                ),
            ),
        )

        val encoded = json.encodeToString(envelope)

        assertEquals(
            """{"type":"system-control.v1.command","payload":{"action":"wake-on-lan","targetMac":"01:23:45:67:89:ab"}}""",
            encoded,
        )
    }

    @Test
    fun validatesWakeOnLanMacAddress() {
        assertEquals(true, isValidWakeOnLanMac("01:23:45:67:89:ab"))
        assertEquals(true, isValidWakeOnLanMac("01:23:45:67:89:AB"))
        assertEquals(false, isValidWakeOnLanMac("01-23-45-67-89-ab"))
        assertEquals(false, isValidWakeOnLanMac("01:23:45:67:89"))
    }

    @Test
    fun serializesSystemControlStateQuery() {
        val envelope = BusinessEnvelope(
            type = SYSTEM_CONTROL_QUERY_TYPE,
            payload = json.encodeToJsonElement(
                SystemControlQueryPayload(fields = listOf("volume", "muted", "playback")),
            ),
        )

        val encoded = json.encodeToString(envelope)

        assertEquals(
            """{"type":"system-control.v1.query","payload":{"fields":["volume","muted","playback"]}}""",
            encoded,
        )
    }

    @Test
    fun serializesSystemControlStateResult() {
        val envelope = BusinessEnvelope(
            type = SYSTEM_CONTROL_RESULT_TYPE,
            payload = json.encodeToJsonElement(
                SystemControlResultPayload(
                    volume = 72,
                    muted = false,
                    playback = "playing",
                ),
            ),
        )

        val encoded = json.encodeToString(envelope)

        assertEquals(
            """{"type":"system-control.v1.result","payload":{"volume":72,"muted":false,"playback":"playing"}}""",
            encoded,
        )
    }
}
