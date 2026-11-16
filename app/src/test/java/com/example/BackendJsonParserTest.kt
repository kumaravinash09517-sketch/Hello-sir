package com.example

import com.example.data.model.BROADCAST_TARGET_ID
import com.example.data.model.ChatUser
import com.example.data.network.BackendJsonParser
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
    fun parseFeed_handlesFlatUserMapAndIsoTimestamps() {
        val json = """
            {
              "users": {
                "u_10": {"name": "Zoya", "online": true, "last_seen": "2026-11-16T03:00:00Z"},
                "u_20": {"name": "Rohan", "online": false}
              },
              "chats": [
                {"sender": "u_10", "receiver": "u_20", "message": "Ping", "created_at": "2026-11-16T03:01:00.000Z"}
              ]
            }
        """.trimIndent()

        val feed = BackendJsonParser.parseFeed(json)
        assertEquals(2, feed.users.size)
        assertEquals(1, feed.messages.size)
        assertTrue(feed.users.first { it.userId == "u_10" }.isOnline())
        assertFalse(feed.users.first { it.userId == "u_20" }.isOnline())
        assertEquals("Ping", feed.messages.first().text)
    }

    @Test
    fun chatUser_initialsAndPresenceLabels() {
        val u = ChatUser(userId = "js01", name = "John Smith", explicitOnline = true)
        assertEquals("JS", u.initials)
        assertEquals("Online", u.presenceLabel())
    }
}
