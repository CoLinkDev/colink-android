package com.colink.android.network.message

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
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
            """{"type":"swim.ping","payload":{"seq":1,"from":"device-a","gossip":[{"deviceId":"device-a","state":"alive","incarnation":2}]}}""",
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
                target = "device-b",
                gossip = emptyList(),
            ),
        )

        val encoded = json.encodeToString(envelope)

        assertEquals(
            """{"type":"swim.ping-req","payload":{"seq":2,"from":"device-a","target":"device-b","gossip":[]}}""",
            encoded,
        )
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
}
