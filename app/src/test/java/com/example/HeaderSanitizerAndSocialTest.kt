package com.example

import com.example.data.model.ChatMessage
import com.example.data.model.ChatUser
import com.example.data.model.DailyShayariProvider
import com.example.data.model.MessageDeliveryStatus
import com.example.data.model.MessageKind
import com.example.data.model.SocialActionEnvelope
import com.example.data.model.SocialMediaType
import com.example.data.model.SocialPost
import com.example.data.model.UserSession
import com.example.data.network.BackendConfig
import com.example.data.network.BackendJsonParser
import com.example.data.network.HeaderSanitizer
import com.example.data.network.MediaUploadService
import com.example.data.network.SupabaseRestClient
import com.example.ui.ChatUiState
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HeaderSanitizerAndSocialTest {

    @Test
    fun headerSanitizer_preventsOkHttpExceptionForDevanagariAndNewlines() {
        val rawId = "अविनाश_01 🚀\n"
        val safeValue = HeaderSanitizer.sanitizeHeaderValue(rawId)
        assertTrue(safeValue.all { it.code in 0x20..0x7E || it.code == 0x09 })

        // Verify OkHttp accepts the sanitized header without IllegalArgumentException
        val request = Request.Builder()
            .url("https://rendar2-o9gq.onrender.com/api/users")
            .header("X-User-Id", safeValue)
            .build()
        assertEquals(safeValue, request.header("X-User-Id"))
    }

    @Test
    fun backendConfig_resolvesRelativeUploadPathsAndCloudinaryUrls() {
        assertEquals(
            "https://rendar2-o9gq.onrender.com/uploads/photo.jpg",
            BackendConfig.resolveUploadUrl("photo.jpg")
        )
        assertEquals(
            "https://rendar2-o9gq.onrender.com/uploads/video.mp4",
            BackendConfig.resolveUploadUrl("/uploads/video.mp4")
        )
        assertEquals(
            "https://res.cloudinary.com/dvjhguyzo/image/upload/v1/hello_sir/test.png",
            BackendConfig.resolveUploadUrl("https://res.cloudinary.com/dvjhguyzo/image/upload/v1/hello_sir/test.png")
        )
    }

    @Test
    fun mediaUploadService_computesCloudinarySha1AndParsesRenderUpload() {
        // Known SHA-1 of "folder=hello_sir&timestamp=1700000000mysecret"
        val sig = MediaUploadService.computeCloudinarySignature(
            params = mapOf("timestamp" to "1700000000", "folder" to "hello_sir"),
            apiSecret = "mysecret"
        )
        assertEquals(40, sig.length)
        assertTrue(sig.all { it in '0'..'9' || it in 'a'..'f' })

        val resolved = MediaUploadService.parseRenderUploadResponse(
            """{"ok": true, "url": "/uploads/clip_01.mp4"}"""
        )
        assertEquals("https://rendar2-o9gq.onrender.com/uploads/clip_01.mp4", resolved)
    }

    @Test
    fun backendJsonParser_parsesIceServersCallSignalsUsersAndNativeMessageFields() {
        val iceJson = """
            {
              "iceServers": [
                {"urls": ["stun:stun.l.google.com:19302"]},
                {"urls": "turn:turn.example.com:3478", "username": "u1", "credential": "p1"}
              ]
            }
        """.trimIndent()
        val iceServers = BackendJsonParser.parseIceServers(iceJson)
        assertEquals(2, iceServers.size)

        val signalJson = """
            {
              "signals": [
                {
                  "signal_id": "sig_1",
                  "type": "offer",
                  "sender_id": "rohit",
                  "sender_name": "Rohit",
                  "receiver_id": "priya",
                  "sdp": "v=0...",
                  "is_video": true,
                  "timestamp": 1700000000
                }
              ]
            }
        """.trimIndent()
        val signals = BackendJsonParser.parseCallSignals(signalJson)
        assertEquals(1, signals.size)
        assertEquals("offer", signals.first().type)
        assertEquals("rohit", signals.first().sender)
        assertTrue(signals.first().isVideo)

        val usersJson = """
            {
              "users": [
                {
                  "user_id": "ankit",
                  "name": "Ankit",
                  "avatar_url": "/uploads/ankit.png",
                  "phone": "+919999999999",
                  "online": true
                }
              ]
            }
        """.trimIndent()
        val users = BackendJsonParser.parseUsers(usersJson)
        assertEquals(1, users.size)
        assertEquals("https://rendar2-o9gq.onrender.com/uploads/ankit.png", users.first().avatarUrl)
        assertEquals("+919999999999", users.first().phone)

        val msgJson = """
            {
              "messages": [
                {
                  "id": "m_media",
                  "sender_id": "ankit",
                  "receiver_id": "me",
                  "message": "pic.jpg",
                  "media_url": "/uploads/pic.jpg",
                  "msg_type": "image",
                  "is_read": true,
                  "timestamp": 1700000000
                },
                {
                  "id": "m_loc",
                  "sender_id": "ankit",
                  "receiver_id": "me",
                  "message": "Here",
                  "latitude": 28.6139,
                  "longitude": 77.2090,
                  "location_label": "Live GPS",
                  "is_read": false,
                  "timestamp": 1700000005
                }
              ]
            }
        """.trimIndent()
        val feed = BackendJsonParser.parseFeed(msgJson)
        assertEquals(2, feed.messages.size)
        val imgMsg = feed.messages[0]
        assertEquals(MessageKind.IMAGE, imgMsg.kind)
        assertEquals(MessageDeliveryStatus.READ, imgMsg.deliveryStatus)
        assertEquals("https://rendar2-o9gq.onrender.com/uploads/pic.jpg", imgMsg.media?.localUri)

        val locMsg = feed.messages[1]
        assertEquals(MessageKind.LOCATION, locMsg.kind)
        assertNotNull(locMsg.location)
        assertEquals(28.6139, locMsg.location!!.latitude, 0.0001)
        assertTrue(locMsg.location!!.isLive)
    }

    @Test
    fun supabaseRestClient_parsesUsersResponse() {
        val raw = """
            [
              {
                "user_id": "priya_99",
                "name": "Priya",
                "avatar_url": "priya.jpg",
                "bio": "Hello Sir creator",
                "phone": "9876543210",
                "last_seen": 1700000000000
              }
            ]
        """.trimIndent()
        val parsed = SupabaseRestClient.parseSupabaseUsers(raw)
        assertEquals(1, parsed.size)
        assertEquals("priya_99", parsed.first().userId)
        assertEquals("https://rendar2-o9gq.onrender.com/uploads/priya.jpg", parsed.first().avatarUrl)
        assertEquals("Hello Sir creator", parsed.first().bio)
    }

    @Test
    fun parseFeed_decodesSocialPostsAndSocialActions() {
        val post = SocialPost(
            id = "post_1",
            authorId = "rohit",
            authorName = "Rohit Sharma",
            mediaType = SocialMediaType.QUOTE,
            caption = "Hello Sir Social",
            shayariTag = "प्रेरणा",
            timestampMs = 1700000000000L
        )
        val action = SocialActionEnvelope(
            actionId = "act_1",
            actionType = "comment",
            actorId = "priya",
            actorName = "Priya",
            targetUserId = "rohit",
            postId = "post_1",
            payloadText = "Awesome quote!",
            timestampMs = 1700000005000L
        )

        val encodedPost = BackendJsonParser.encodePost(post).replace("\"", "\\\"")
        val encodedAction = BackendJsonParser.encodeSocialAction(action).replace("\"", "\\\"")
        val nowSec = System.currentTimeMillis() / 1000L

        val json = """
            {
              "users": [],
              "messages": [
                {"id": "m10", "sender": "rohit", "receiver": "all", "text": "$encodedPost", "timestamp": $nowSec},
                {"id": "m11", "sender": "priya", "receiver": "all", "text": "$encodedAction", "timestamp": $nowSec}
              ]
            }
        """.trimIndent()

        val feed = BackendJsonParser.parseFeed(json)
        assertTrue(feed.messages.isEmpty())
        assertEquals(1, feed.posts.size)
        assertEquals("post_1", feed.posts.first().id)
        assertEquals(SocialMediaType.QUOTE, feed.posts.first().mediaType)
        assertEquals(1, feed.socialActions.size)
        assertEquals("comment", feed.socialActions.first().actionType)
        assertEquals("Awesome quote!", feed.socialActions.first().payloadText)
    }

    @Test
    fun chatUiState_sortsActiveThreadsToTopAndSearchesWithAtPrefix() {
        val me = UserSession(userId = "me", name = "Me", deviceLockId = "DEV-1", lockedAtMs = 1L)
        val u1 = ChatUser(userId = "ankit_01", name = "Ankit", explicitOnline = true)
        val u2 = ChatUser(userId = "avinash", name = "अविनाश Verma", explicitOnline = false)

        val state = ChatUiState(
            session = me,
            users = listOf(u1, u2),
            messages = listOf(
                ChatMessage(
                    id = "m1",
                    sender = "avinash",
                    receiver = "me",
                    text = "Namaste!",
                    timestampMs = 9999999999999L
                )
            ),
            exploreQuery = "@AVINASH"
        )

        // Most recent message thread ("avinash") bubbles above online "ankit_01"
        assertEquals(listOf("avinash", "ankit_01"), state.sortedChatUsers.map { it.userId })

        // Explore supports @-prefix + case-insensitive lookup
        assertEquals(listOf("avinash"), state.exploredUsers.map { it.userId })
    }

    @Test
    fun dailyShayariProvider_returnsNonBlankShayari() {
        val s0 = DailyShayariProvider.shayariForToday(0)
        val s1 = DailyShayariProvider.shayariForToday(1)
        assertFalse(s0.hindiText.isBlank())
        assertFalse(s1.hindiText.isBlank())
    }
}
