package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.SessionManager
import com.example.data.model.BROADCAST_TARGET_ID
import com.example.data.model.ChatUser
import com.example.data.model.LocationPayload
import com.example.data.model.MediaPayload
import com.example.data.model.MessageKind
import com.example.data.model.SignalingEnvelope
import com.example.data.network.BackendJsonParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackendJsonParserTest {

    @Test
    fun parseFeed_handlesStandardObjectWithUsersAndMessages() {
        val nowSec = System.currentTimeMillis() / 1000L
        val json = """
            {
              "users": [
                {"user_id": "alice", "name": "Alice Verma", "last_seen": $nowSec},
                {"user_id": "bob", "name": "Bob Khan", "last_seen": ${nowSec - 3600}}
              ],
              "messages": [
                {"id": "m1", "sender": "alice", "receiver": "all", "text": "Hello Sir!", "timestamp": $nowSec},
                {"id": "m2", "sender": "bob", "receiver": "alice", "text": "Hi Alice", "timestamp": ${nowSec + 1}}
              ]
            }
        """.trimIndent()

        val feed = BackendJsonParser.parseFeed(json)
        assertEquals(2, feed.users.size)
        assertEquals(2, feed.messages.size)

        val alice = feed.users.first { it.userId == "alice" }
        val bob = feed.users.first { it.userId == "bob" }
        assertTrue(alice.isOnline())
        assertFalse(bob.isOnline())

        assertTrue(feed.messages[0].isBroadcast)
        assertEquals(BROADCAST_TARGET_ID, feed.messages[0].receiver)
        assertFalse(feed.messages[1].isBroadcast)
    }

    @Test
    fun parseFeed_separatesWebRtcSignalsLocationAndMediaFromText() {
        val signalText = BackendJsonParser.encodeSignal(
            SignalingEnvelope(
                signalId = "sig_1",
                type = "offer",
                sender = "bob",
                receiver = "alice",
                sdp = "v=0...",
                isVideo = true
            )
        ).replace("\"", "\\\"")

        val locText = BackendJsonParser.encodeLocation(
            LocationPayload(latitude = 28.6139, longitude = 77.2090, isLive = true)
        ).replace("\"", "\\\"")

        val mediaText = BackendJsonParser.encodeMedia(
            MediaPayload(
                kind = MessageKind.DOCUMENT,
                displayName = "spec.pdf",
                mimeType = "application/pdf",
                sizeBytes = 4096L
            )
        ).replace("\"", "\\\"")

        val nowSec = System.currentTimeMillis() / 1000L
        val json = """
            {
              "users": [],
              "messages": [
                {"id": "s1", "sender": "bob", "receiver": "alice", "text": "$signalText", "timestamp": $nowSec},
                {"id": "m1", "sender": "bob", "receiver": "alice", "text": "$locText", "timestamp": $nowSec},
                {"id": "m2", "sender": "alice", "receiver": "bob", "text": "$mediaText", "timestamp": $nowSec}
              ]
            }
        """.trimIndent()

        val feed = BackendJsonParser.parseFeed(json)
        assertEquals(1, feed.signals.size)
        assertEquals("offer", feed.signals.first().type)
        assertTrue(feed.signals.first().isVideo)

        assertEquals(2, feed.messages.size)
        assertEquals(MessageKind.LOCATION, feed.messages[0].kind)
        assertEquals(28.6139, feed.messages[0].location!!.latitude, 0.0001)
        assertEquals(MessageKind.DOCUMENT, feed.messages[1].kind)
        assertEquals("spec.pdf", feed.messages[1].media!!.displayName)
    }

    @Test
    fun sessionManager_enforcesSingleDeviceLockAndReset() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = SessionManager(context)
        manager.resetSessionLock()
        assertFalse(manager.isDeviceLocked())

        val locked = manager.lockSession("aarav_01", "Aarav")
        assertTrue(manager.isDeviceLocked())
        assertEquals("aarav_01", locked.userId)
        assertTrue(locked.deviceLockId.startsWith("DEV-"))

        manager.resetSessionLock()
        assertFalse(manager.isDeviceLocked())
    }

    @Test
    fun chatUser_initialsAndPresenceLabels() {
        val u = ChatUser(userId = "js01", name = "John Smith", explicitOnline = true)
        assertEquals("JS", u.initials)
        assertEquals("Online", u.presenceLabel())
    }
}
