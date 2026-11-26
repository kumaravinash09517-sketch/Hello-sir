package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.model.ActivityNotification
import com.example.data.model.ActivityType
import com.example.data.model.BlockedUser
import com.example.data.model.ChatMessage
import com.example.data.model.ChatUser
import com.example.data.model.LocationPayload
import com.example.data.model.MediaPayload
import com.example.data.model.MessageDeliveryStatus
import com.example.data.model.MessageKind
import com.example.data.model.SocialComment
import com.example.data.model.SocialMediaType
import com.example.data.model.SocialPost
import com.example.data.model.SocialRelation
import com.example.data.model.UserProfileExtras
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val userId: String,
    val name: String,
    val lastSeenEpochMs: Long?,
    val explicitOnline: Boolean?,
    val statusText: String?,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val phone: String? = null
) {
    fun toDomain(): ChatUser = ChatUser(
        userId = userId,
        name = name,
        lastSeenEpochMs = lastSeenEpochMs,
        explicitOnline = explicitOnline,
        statusText = statusText,
        avatarUrl = avatarUrl,
        bio = bio,
        phone = phone
    )

    companion object {
        fun fromDomain(user: ChatUser): UserEntity = UserEntity(
            userId = user.userId,
            name = user.name,
            lastSeenEpochMs = user.lastSeenEpochMs,
            explicitOnline = user.explicitOnline,
            statusText = user.statusText,
            avatarUrl = user.avatarUrl,
            bio = user.bio,
            phone = user.phone
        )
    }
}

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val dedupeKey: String,
    val id: String,
    val sender: String,
    val senderName: String?,
    val receiver: String,
    val text: String,
    val timestampMs: Long,
    val kind: String,
    val deliveryStatus: String,
    val locLat: Double? = null,
    val locLng: Double? = null,
    val locAccuracy: Float? = null,
    val locIsLive: Boolean = false,
    val locExpiresAtMs: Long = 0L,
    val locLabel: String? = null,
    val mediaName: String? = null,
    val mediaMime: String? = null,
    val mediaSizeBytes: Long = 0L,
    val mediaLocalUri: String? = null,
    val mediaInlineBase64: String? = null
) {
    fun toDomain(): ChatMessage {
        val msgKind = runCatching { MessageKind.valueOf(kind) }.getOrDefault(MessageKind.TEXT)
        val status = runCatching { MessageDeliveryStatus.valueOf(deliveryStatus) }
            .getOrDefault(MessageDeliveryStatus.DELIVERED)
        val locationPayload = if (msgKind == MessageKind.LOCATION && locLat != null && locLng != null) {
            LocationPayload(
                latitude = locLat,
                longitude = locLng,
                accuracyMeters = locAccuracy ?: 0f,
                isLive = locIsLive,
                expiresAtMs = locExpiresAtMs,
                label = locLabel ?: "Shared Location"
            )
        } else {
            null
        }
        val mediaPayload = if (msgKind in setOf(
                MessageKind.IMAGE,
                MessageKind.VIDEO,
                MessageKind.AUDIO,
                MessageKind.DOCUMENT
            )
        ) {
            MediaPayload(
                kind = msgKind,
                displayName = mediaName ?: "Attachment",
                mimeType = mediaMime ?: "*/*",
                sizeBytes = mediaSizeBytes,
                localUri = mediaLocalUri.orEmpty(),
                inlineDataBase64 = mediaInlineBase64.orEmpty()
            )
        } else {
            null
        }

        return ChatMessage(
            id = id,
            sender = sender,
            senderName = senderName,
            receiver = receiver,
            text = text,
            timestampMs = timestampMs,
            kind = msgKind,
            deliveryStatus = status,
            location = locationPayload,
            media = mediaPayload
        )
    }

    companion object {
        fun fromDomain(msg: ChatMessage): MessageEntity = MessageEntity(
            dedupeKey = msg.dedupeKey,
            id = msg.id,
            sender = msg.sender,
            senderName = msg.senderName,
            receiver = msg.receiver,
            text = msg.text,
            timestampMs = msg.timestampMs,
            kind = msg.kind.name,
            deliveryStatus = msg.deliveryStatus.name,
            locLat = msg.location?.latitude,
            locLng = msg.location?.longitude,
            locAccuracy = msg.location?.accuracyMeters,
            locIsLive = msg.location?.isLive ?: false,
            locExpiresAtMs = msg.location?.expiresAtMs ?: 0L,
            locLabel = msg.location?.label,
            mediaName = msg.media?.displayName,
            mediaMime = msg.media?.mimeType,
            mediaSizeBytes = msg.media?.sizeBytes ?: 0L,
            mediaLocalUri = msg.media?.localUri,
            mediaInlineBase64 = msg.media?.inlineDataBase64
        )
    }
}

@Entity(tableName = "thread_reads")
data class ThreadReadEntity(
    @PrimaryKey val threadId: String,
    val lastReadMs: Long
)

@Entity(tableName = "social_posts")
data class SocialPostEntity(
    @PrimaryKey val id: String,
    val authorId: String,
    val authorName: String,
    val mediaType: String,
    val mediaUri: String,
    val inlineImageBase64: String,
    val caption: String,
    val shayariTag: String,
    val timestampMs: Long,
    val likeCount: Int,
    val commentCount: Int,
    val shareCount: Int,
    val reachCount: Int,
    val isLikedByMe: Boolean,
    val isBookmarkedByMe: Boolean,
    val isRepost: Boolean,
    val repostedFromName: String?
) {
    fun toDomain(): SocialPost = SocialPost(
        id = id,
        authorId = authorId,
        authorName = authorName,
        mediaType = runCatching { SocialMediaType.valueOf(mediaType) }.getOrDefault(SocialMediaType.PHOTO),
        mediaUri = mediaUri,
        inlineImageBase64 = inlineImageBase64,
        caption = caption,
        shayariTag = shayariTag,
        timestampMs = timestampMs,
        likeCount = likeCount,
        commentCount = commentCount,
        shareCount = shareCount,
        reachCount = reachCount,
        isLikedByMe = isLikedByMe,
        isBookmarkedByMe = isBookmarkedByMe,
        isRepost = isRepost,
        repostedFromName = repostedFromName
    )

    companion object {
        fun fromDomain(post: SocialPost): SocialPostEntity = SocialPostEntity(
            id = post.id,
            authorId = post.authorId,
            authorName = post.authorName,
            mediaType = post.mediaType.name,
            mediaUri = post.mediaUri,
            inlineImageBase64 = post.inlineImageBase64,
            caption = post.caption,
            shayariTag = post.shayariTag,
            timestampMs = post.timestampMs,
            likeCount = post.likeCount,
            commentCount = post.commentCount,
            shareCount = post.shareCount,
            reachCount = post.reachCount,
            isLikedByMe = post.isLikedByMe,
            isBookmarkedByMe = post.isBookmarkedByMe,
            isRepost = post.isRepost,
            repostedFromName = post.repostedFromName
        )
    }
}

@Entity(tableName = "social_comments")
data class SocialCommentEntity(
    @PrimaryKey val id: String,
    val postId: String,
    val authorId: String,
    val authorName: String,
    val text: String,
    val timestampMs: Long
) {
    fun toDomain(): SocialComment = SocialComment(
        id = id,
        postId = postId,
        authorId = authorId,
        authorName = authorName,
        text = text,
        timestampMs = timestampMs
    )

    companion object {
        fun fromDomain(comment: SocialComment): SocialCommentEntity = SocialCommentEntity(
            id = comment.id,
            postId = comment.postId,
            authorId = comment.authorId,
            authorName = comment.authorName,
            text = comment.text,
            timestampMs = comment.timestampMs
        )
    }
}

@Entity(tableName = "profile_extras")
data class ProfileExtraEntity(
    @PrimaryKey val userId: String,
    val bio: String,
    val followersCount: Int,
    val followingCount: Int,
    val isFollowedByMe: Boolean
) {
    fun toDomain(): UserProfileExtras = UserProfileExtras(
        userId = userId,
        bio = bio,
        followersCount = followersCount,
        followingCount = followingCount,
        isFollowedByMe = isFollowedByMe
    )

    companion object {
        fun fromDomain(p: UserProfileExtras): ProfileExtraEntity = ProfileExtraEntity(
            userId = p.userId,
            bio = p.bio,
            followersCount = p.followersCount,
            followingCount = p.followingCount,
            isFollowedByMe = p.isFollowedByMe
        )
    }
}

@Entity(tableName = "activity_notifications")
data class ActivityNotificationEntity(
    @PrimaryKey val id: String,
    val type: String,
    val actorId: String,
    val actorName: String,
    val postId: String?,
    val postSnippet: String,
    val timestampMs: Long,
    val isRead: Boolean
) {
    fun toDomain(): ActivityNotification = ActivityNotification(
        id = id,
        type = runCatching { ActivityType.valueOf(type) }.getOrDefault(ActivityType.LIKE),
        actorId = actorId,
        actorName = actorName,
        postId = postId,
        postSnippet = postSnippet,
        timestampMs = timestampMs,
        isRead = isRead
    )

    companion object {
        fun fromDomain(item: ActivityNotification): ActivityNotificationEntity = ActivityNotificationEntity(
            id = item.id,
            type = item.type.name,
            actorId = item.actorId,
            actorName = item.actorName,
            postId = item.postId,
            postSnippet = item.postSnippet,
            timestampMs = item.timestampMs,
            isRead = item.isRead
        )
    }
}

@Entity(tableName = "applied_social_actions")
data class AppliedSocialActionEntity(
    @PrimaryKey val actionId: String,
    val appliedAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "social_relations", primaryKeys = ["followerId", "followingId"])
data class SocialRelationEntity(
    val followerId: String,
    val followingId: String,
    val timestampMs: Long = System.currentTimeMillis()
) {
    fun toDomain(): SocialRelation = SocialRelation(
        followerId = followerId,
        followingId = followingId,
        timestampMs = timestampMs
    )
}

@Entity(tableName = "blocked_users")
data class BlockedUserEntity(
    @PrimaryKey val blockedUserId: String,
    val blockedName: String,
    val timestampMs: Long = System.currentTimeMillis()
) {
    fun toDomain(): BlockedUser = BlockedUser(
        userId = blockedUserId,
        name = blockedName,
        timestampMs = timestampMs
    )
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM users")
    fun observeUsers(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users")
    suspend fun getAllUsers(): List<UserEntity>

    @Query("SELECT * FROM users WHERE userId = :userId LIMIT 1")
    suspend fun getUserById(userId: String): UserEntity?

    @Query("SELECT * FROM messages ORDER BY timestampMs ASC")
    fun observeAllMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM thread_reads")
    fun observeThreadReads(): Flow<List<ThreadReadEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUsers(users: List<UserEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUser(user: UserEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(messages: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE dedupeKey = :dedupeKey OR id = :messageId")
    suspend fun deleteMessage(dedupeKey: String, messageId: String = dedupeKey)

    @Query("UPDATE messages SET deliveryStatus = :status WHERE sender = :senderId AND receiver = :receiverId")
    suspend fun updateOutgoingStatus(senderId: String, receiverId: String, status: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markThreadRead(entity: ThreadReadEntity)

    // Social Media Queries
    @Query("SELECT * FROM social_posts ORDER BY timestampMs DESC")
    fun observeSocialPosts(): Flow<List<SocialPostEntity>>

    @Query("SELECT * FROM social_posts WHERE id = :postId LIMIT 1")
    suspend fun getPostById(postId: String): SocialPostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSocialPost(post: SocialPostEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSocialPostsIfAbsent(posts: List<SocialPostEntity>)

    @Query("SELECT * FROM social_comments ORDER BY timestampMs ASC")
    fun observeAllComments(): Flow<List<SocialCommentEntity>>

    @Query("SELECT * FROM social_comments WHERE id = :commentId LIMIT 1")
    suspend fun getCommentById(commentId: String): SocialCommentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComment(comment: SocialCommentEntity)

    @Query("SELECT * FROM profile_extras")
    fun observeProfileExtras(): Flow<List<ProfileExtraEntity>>

    @Query("SELECT * FROM profile_extras WHERE userId = :userId LIMIT 1")
    suspend fun getProfileExtra(userId: String): ProfileExtraEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfileExtra(extra: ProfileExtraEntity)

    @Query("SELECT * FROM activity_notifications ORDER BY timestampMs DESC")
    fun observeActivityNotifications(): Flow<List<ActivityNotificationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActivityNotification(item: ActivityNotificationEntity)

    @Query("UPDATE activity_notifications SET isRead = 1")
    suspend fun markAllActivityRead()

    @Query("SELECT actionId FROM applied_social_actions")
    suspend fun getAppliedSocialActionIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markSocialActionApplied(entry: AppliedSocialActionEntity): Long

    @Query("DELETE FROM applied_social_actions")
    suspend fun clearAppliedSocialActions()

    @Query("DELETE FROM users")
    suspend fun clearUsers()

    @Query("DELETE FROM messages")
    suspend fun clearMessages()

    @Query("DELETE FROM thread_reads")
    suspend fun clearThreadReads()

    @Query("DELETE FROM social_posts")
    suspend fun clearSocialPosts()

    @Query("DELETE FROM social_comments")
    suspend fun clearSocialComments()

    @Query("DELETE FROM profile_extras")
    suspend fun clearProfileExtras()

    @Query("DELETE FROM activity_notifications")
    suspend fun clearActivityNotifications()

    @Query("SELECT * FROM social_relations ORDER BY timestampMs DESC")
    fun observeSocialRelations(): Flow<List<SocialRelationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSocialRelation(relation: SocialRelationEntity)

    @Query("DELETE FROM social_relations WHERE followerId = :followerId AND followingId = :followingId")
    suspend fun deleteSocialRelation(followerId: String, followingId: String)

    @Query("SELECT * FROM social_relations WHERE followingId = :userId")
    suspend fun getFollowersOf(userId: String): List<SocialRelationEntity>

    @Query("SELECT * FROM social_relations WHERE followerId = :userId")
    suspend fun getFollowingOf(userId: String): List<SocialRelationEntity>

    @Query("DELETE FROM social_relations")
    suspend fun clearSocialRelations()

    @Query("SELECT * FROM blocked_users ORDER BY timestampMs DESC")
    fun observeBlockedUsers(): Flow<List<BlockedUserEntity>>

    @Query("SELECT blockedUserId FROM blocked_users")
    suspend fun getBlockedUserIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBlockedUser(entity: BlockedUserEntity)

    @Query("DELETE FROM blocked_users WHERE blockedUserId = :userId")
    suspend fun deleteBlockedUser(userId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_users WHERE blockedUserId = :userId)")
    suspend fun isUserBlocked(userId: String): Boolean

    @Query("DELETE FROM blocked_users")
    suspend fun clearBlockedUsers()
}

@Database(
    entities = [
        UserEntity::class,
        MessageEntity::class,
        ThreadReadEntity::class,
        SocialPostEntity::class,
        SocialCommentEntity::class,
        ProfileExtraEntity::class,
        ActivityNotificationEntity::class,
        AppliedSocialActionEntity::class,
        SocialRelationEntity::class,
        BlockedUserEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var instance: ChatDatabase? = null

        fun getInstance(context: Context): ChatDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "hello_sir_sqlite.db"
                ).fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
