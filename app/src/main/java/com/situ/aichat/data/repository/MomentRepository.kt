package com.situ.aichat.data.repository

import com.situ.aichat.data.local.dao.MomentDao
import com.situ.aichat.data.local.entity.MomentCommentEntity
import com.situ.aichat.data.local.entity.MomentLikeEntity
import com.situ.aichat.data.local.entity.MomentNotificationEntity
import com.situ.aichat.data.local.entity.MomentPostEntity
import com.situ.aichat.data.local.entity.MomentPostWithRelations
import com.situ.aichat.data.model.MomentAuthorType
import com.situ.aichat.data.model.MomentNotificationType
import com.situ.aichat.data.model.imagePaths
import com.situ.aichat.util.ContentImageStore
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 朋友圈 (M06) aggregate read/write, mirroring how iOS persists `MomentPost` + its `MomentComment`s /
 * `MomentLike`s / `MomentNotification`s.
 *
 * Value-add over the raw DAO (the iOS model inits do these):
 * - generates uuids for posts/comments (likes have none — they dedup on post+character),
 * - truncates notification previews to 100 chars (`String(contentPreview.prefix(100))`),
 * - stores notification `postTimestamp` in **seconds** (`post.timestamp / 1000.0`),
 * - on cleanup, deletes a post's image files from disk (iOS lets external-storage GC the `[Data]`).
 *
 * Day/range bounds are computed by callers (they hold the `ZoneId`); this layer takes raw epoch ms.
 */
@Singleton
class MomentRepository @Inject constructor(
    private val dao: MomentDao,
) {
    companion object {
        /** iOS `FriendCircleView` @Query caps at 200; UI then paginates in 20s. */
        const val FEED_LIMIT = 200
        private const val PREVIEW_MAX = 100
    }

    // ---- Posts: reads ----

    fun observeFeed(limit: Int = FEED_LIMIT): Flow<List<MomentPostWithRelations>> =
        dao.observeFeedWithRelations(limit)

    fun observePost(uuid: String): Flow<MomentPostWithRelations?> = dao.observePostWithRelations(uuid)

    /** A character's own non-deleted posts (character moments page, 7.2.8)；[limit] = 显示窗口（图纸 §3.2·无默认值·K3）。 */
    fun observeCharacterFeed(characterUuid: String, limit: Int): Flow<List<MomentPostWithRelations>> =
        dao.observeCharacterFeedWithRelations(characterUuid, limit)

    /**
     * 「我们的日子」日页 → 那一天的朋友圈（图纸 2026-09-03 §3.2）：半开窗口 `[startMillis, endMillis)`。
     * 窗口由调用方从 `OurDayKey.dayBounds(dayKey, zone)` 取（`first` 与 `last + 1`）。
     */
    fun observeDayMoments(characterUuid: String, startMillis: Long, endMillis: Long): Flow<List<MomentPostWithRelations>> =
        dao.observeDayMomentsWithRelations(characterUuid, startMillis, endMillis)

    /** The user's own non-deleted posts ("My Posts" page, 7.2.8)；[limit] = 显示窗口（图纸 §3.2·无默认值·K3）。 */
    fun observeUserFeed(limit: Int): Flow<List<MomentPostWithRelations>> = dao.observeUserFeedWithRelations(limit)

    /** 用户非删帖计数（仪表盘统计·K5）：只要数字时用它，别订全量关系流。 */
    fun observeUserFeedCount(): Flow<Int> = dao.observeUserFeedCount()

    /** 某角色非删帖计数（作者动态页头部·图纸 §3.2）：只观察 `moment_post` 一张表，互动写入不惊动它。 */
    fun observeCharacterFeedCount(characterUuid: String): Flow<Int> = dao.observeCharacterFeedCount(characterUuid)
    suspend fun getPostWithRelations(uuid: String): MomentPostWithRelations? = dao.getPostWithRelations(uuid)
    suspend fun getPost(uuid: String): MomentPostEntity? = dao.getPost(uuid)

    /** 最新角色朋友圈动态快照（桌面小组件 13.9b；across 所有角色，非删，最新优先）。 */
    suspend fun latestCharacterPosts(limit: Int): List<MomentPostEntity> = dao.latestCharacterPosts(limit)

    /** 最新角色帖的响应式版（桌面小组件同步桥专用·图纸 §3.4）：只观察 `moment_post` 一张表，互动写入不惊动它。 */
    fun observeLatestCharacterPost(): Flow<MomentPostEntity?> = dao.observeLatestCharacterPost()

    /** Non-deleted feed size; pull-to-refresh diffs this before/after generation to report new-post count. */
    suspend fun feedCount(): Int = dao.feedCount()

    // ---- Posts: generation guards (7.2.3) ----

    suspend fun countPostsForCharacterSince(characterUuid: String, since: Long): Int =
        dao.countPostsForCharacterSince(characterUuid, since)

    suspend fun lastPostTimestampForCharacter(characterUuid: String): Long? =
        dao.lastPostTimestampForCharacter(characterUuid)

    /** A character's most recent posts (for the "don't repeat" prompt dedup, 7.2.3). */
    suspend fun recentPostsForCharacter(characterUuid: String, limit: Int): List<MomentPostEntity> =
        dao.recentPostsForCharacter(characterUuid, limit)

    /** Most recent user-authored, non-deleted posts (injected into the post prompt, 7.2.3). */
    suspend fun recentUserPosts(limit: Int): List<MomentPostEntity> = dao.recentUserPosts(limit)

    // ---- Posts: writes ----

    /** Insert/replace a post (caller builds the entity with its uuid, like the diary module). */
    suspend fun upsert(post: MomentPostEntity) = dao.insertPost(post)

    /** User-delete: soft-delete (hidden from feed; hard-deleted 30 days later by [cleanupSoftDeleted]). */
    suspend fun softDelete(uuid: String) = dao.softDeletePost(uuid)

    /**
     * iOS `cleanupSoftDeletedPosts`: first release every soft-deleted post's image files (free disk
     * early), then hard-delete the soft-deleted posts older than [cutoff] (comments + likes cascade).
     * Called by the generation/cleanup pass (7.2.5).
     */
    suspend fun cleanupSoftDeleted(cutoff: Long) {
        for (post in dao.allSoftDeletedPosts()) {
            ContentImageStore.delete(post.imagePaths)
            if (post.imagePathsJson.isNotEmpty()) dao.clearPostImages(post.uuid)
        }
        dao.hardDeleteSoftDeletedOlderThan(cutoff)
    }

    // ---- Comments ----

    suspend fun commentsForPost(postUuid: String): List<MomentCommentEntity> = dao.commentsForPost(postUuid)
    suspend fun getComment(uuid: String): MomentCommentEntity? = dao.getComment(uuid)

    /** Insert a comment, generating its uuid (iOS sets `uuid = UUID().uuidString` in the init). */
    suspend fun addComment(
        postUuid: String,
        content: String,
        authorType: MomentAuthorType,
        characterUuid: String?,
        replyToName: String? = null,
        parentCommentUuid: String? = null,
        timestamp: Long = System.currentTimeMillis(),
    ): MomentCommentEntity {
        val comment = MomentCommentEntity(
            uuid = UUID.randomUUID().toString(),
            content = content,
            timestamp = timestamp,
            authorTypeRaw = authorType.raw,
            characterUuid = characterUuid,
            replyToName = replyToName,
            postUuid = postUuid,
            parentCommentUuid = parentCommentUuid,
        )
        dao.insertComment(comment)
        return comment
    }

    suspend fun deleteComment(uuid: String) = dao.deleteComment(uuid)

    suspend fun commentCountByCharacter(postUuid: String, characterUuid: String): Int =
        dao.commentCountByCharacter(postUuid, characterUuid)

    suspend fun aiCommentCount(postUuid: String): Int = dao.aiCommentCount(postUuid)

    // ---- Likes ----

    suspend fun addLike(
        postUuid: String,
        authorType: MomentAuthorType,
        characterUuid: String?,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        dao.insertLike(
            MomentLikeEntity(
                timestamp = timestamp,
                authorTypeRaw = authorType.raw,
                characterUuid = characterUuid,
                postUuid = postUuid,
            )
        )
    }

    suspend fun hasLiked(postUuid: String, characterUuid: String): Boolean =
        dao.existsLike(postUuid, characterUuid) > 0

    /** iOS `postHasUserLike`: drives the `.coLike` notification when a character likes a user-liked AI post. */
    suspend fun hasUserLike(postUuid: String): Boolean = dao.existsUserLike(postUuid) > 0

    suspend fun likeCountForPost(postUuid: String): Int = dao.likeCountForPost(postUuid)

    /** iOS `toggleLike` un-like branch: delete the user's own like on a post. */
    suspend fun removeUserLike(postUuid: String) = dao.deleteUserLike(postUuid)

    // ---- Recovery (7.2.5): foreground compensation for delayed products lost to app-kill ----

    /** Scenario A: user comments newer than [cutoff] (oldest first) — candidates for a missing AI reply. */
    suspend fun recentUserComments(cutoff: Long, limit: Int): List<MomentCommentEntity> =
        dao.recentUserComments(cutoff, limit)

    /** Scenario A: does this user comment already have an AI reply? */
    suspend fun hasCharacterReply(parentCommentUuid: String): Boolean =
        dao.characterReplyCount(parentCommentUuid) > 0

    /** Scenario B: non-deleted user posts in `(after, before)` (newest first). */
    suspend fun recentUserPostsInWindow(after: Long, before: Long, limit: Int): List<MomentPostEntity> =
        dao.recentUserPostsInWindow(after, before, limit)

    /** Scenario C: non-deleted AI posts in `(after, before)` (newest first). */
    suspend fun recentCharacterPostsInWindow(after: Long, before: Long, limit: Int): List<MomentPostEntity> =
        dao.recentCharacterPostsInWindow(after, before, limit)

    /** Scenario C: has any character OTHER than [excludeCharacterUuid] commented on this post? */
    suspend fun hasOtherCharacterComment(postUuid: String, excludeCharacterUuid: String): Boolean =
        dao.otherCharacterCommentCount(postUuid, excludeCharacterUuid) > 0

    /** Scenario C: has any character OTHER than [excludeCharacterUuid] liked this post? */
    suspend fun hasOtherCharacterLike(postUuid: String, excludeCharacterUuid: String): Boolean =
        dao.otherCharacterLikeCount(postUuid, excludeCharacterUuid) > 0

    // ---- Chat-context (7.2.6): last-7-day moments summaries injected into the chat system prompt ----

    /** A character's own non-deleted posts newer than [cutoff], newest first. */
    suspend fun recentCharacterOwnPosts(characterUuid: String, cutoff: Long, limit: Int): List<MomentPostEntity> =
        dao.recentCharacterOwnPosts(characterUuid, cutoff, limit)

    /** Non-deleted user posts newer than [cutoff], newest first (caller filters to those with AI interaction). */
    suspend fun recentUserPostsSince(cutoff: Long, limit: Int): List<MomentPostEntity> =
        dao.recentUserPostsSince(cutoff, limit)

    /** 朋友圈消化候选（记忆改造一期·图纸 §3.5-B·(from, to] 升序·该角色帖或用户帖）。 */
    suspend fun postsForDigest(charUuid: String, from: Long, to: Long): List<MomentPostEntity> =
        dao.postsForDigest(charUuid, from, to)

    /** All likes on a post (with author + timestamp), for describing chat-context reactions. */
    suspend fun likesForPost(postUuid: String): List<MomentLikeEntity> = dao.likesForPost(postUuid)

    // ---- Notifications ----

    /** Create an interaction notification; preview truncated to 100 chars + ts→seconds, per iOS init. */
    suspend fun addNotification(
        type: MomentNotificationType,
        characterUuid: String,
        contentPreview: String = "",
        postTimestampMillis: Long,
    ) {
        dao.insertNotification(
            MomentNotificationEntity(
                typeRaw = type.raw,
                isRead = false,
                characterUuid = characterUuid,
                contentPreview = contentPreview.take(PREVIEW_MAX),
                postTimestamp = postTimestampMillis / 1000.0,
            )
        )
    }

    fun observeUnreadNotificationCount(): Flow<Int> = dao.observeUnreadNotificationCount()
    suspend fun recentNotifications(limit: Int): List<MomentNotificationEntity> = dao.recentNotifications(limit)

    /** Unread notifications (newest first, ≤[limit]) — the in-app notification list (7.2.8). */
    fun observeUnreadNotifications(limit: Int = 200): Flow<List<MomentNotificationEntity>> =
        dao.observeUnreadNotifications(limit)

    /** Resolve a notification's post by its stored timestamp (seconds→ms), within ±[toleranceMillis]; null = deleted. */
    suspend fun findPostUuidNear(timestampMillis: Long, toleranceMillis: Long = 2L): String? =
        dao.findNonDeletedPostUuidByTimestamp(timestampMillis - toleranceMillis, timestampMillis + toleranceMillis)
    suspend fun markNotificationRead(id: Long) = dao.markNotificationRead(id)
    suspend fun markAllNotificationsRead() = dao.markAllNotificationsRead()
    suspend fun deleteOldReadNotifications(cutoff: Long, limit: Int) =
        dao.deleteOldReadNotifications(cutoff, limit)
}
