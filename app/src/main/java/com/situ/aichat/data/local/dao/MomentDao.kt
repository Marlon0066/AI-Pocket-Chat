package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.situ.aichat.data.local.entity.MomentCommentEntity
import com.situ.aichat.data.local.entity.MomentLikeEntity
import com.situ.aichat.data.local.entity.MomentNotificationEntity
import com.situ.aichat.data.local.entity.MomentPostEntity
import com.situ.aichat.data.local.entity.MomentPostWithRelations
import kotlinx.coroutines.flow.Flow

/**
 * 朋友圈 (M06) read/write. Posts carry their comments + likes via [MomentPostWithRelations] (one-shot,
 * no N+1). The reactive `observe*` queries auto-refresh when a background-generated post / comment /
 * like lands — improving on iOS's sensor-driven manual refetch.
 *
 * Query semantics are pinned to the iOS source:
 * - Feed excludes soft-deleted posts; `countPostsForCharacterSince` / `lastPostTimestampForCharacter`
 *   do NOT (a post still counts toward the daily cap / 4h cooldown even after the user deletes it —
 *   iOS `countTodayPosts` / `lastPost` have no `isSoftDeleted` predicate).
 * - `lastGiftPostTimestampForCharacter` likewise includes soft-deleted posts (per-character gift-moment
 *   cooldown dedup, P9.2e).
 */
@Dao
interface MomentDao {

    // ---- Posts: feed & detail (with comments + likes, one shot) ----

    /** Feed: non-deleted posts, newest first. iOS `FriendCircleView` caps the query at 200. */
    @Transaction
    @Query("SELECT * FROM moment_post WHERE isSoftDeleted = 0 ORDER BY timestamp DESC LIMIT :limit")
    fun observeFeedWithRelations(limit: Int): Flow<List<MomentPostWithRelations>>

    /** 最新「角色」朋友圈动态（across 所有角色，非删，最新优先）。桌面小组件 13.9b：provideGlance 一次性快照。 */
    @Query("SELECT * FROM moment_post WHERE isSoftDeleted = 0 AND authorTypeRaw = 'character' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun latestCharacterPosts(limit: Int): List<MomentPostEntity>

    /** Single post (with comments + likes), reactive — detail page auto-refreshes as AI interacts. */
    @Transaction
    @Query("SELECT * FROM moment_post WHERE uuid = :uuid LIMIT 1")
    fun observePostWithRelations(uuid: String): Flow<MomentPostWithRelations?>

    /** A character's own non-deleted posts, newest first — the character moments page (7.2.8). */
    @Transaction
    @Query("SELECT * FROM moment_post WHERE isSoftDeleted = 0 AND authorTypeRaw = 'character' AND characterUuid = :characterUuid ORDER BY timestamp DESC")
    fun observeCharacterFeedWithRelations(characterUuid: String): Flow<List<MomentPostWithRelations>>

    /** The user's own non-deleted posts, newest first — the "My Posts" page (7.2.8). */
    @Transaction
    @Query("SELECT * FROM moment_post WHERE isSoftDeleted = 0 AND authorTypeRaw = 'user' ORDER BY timestamp DESC")
    fun observeUserFeedWithRelations(): Flow<List<MomentPostWithRelations>>

    /**
     * 用户非删帖**计数**（K5·2026-07-12）：仪表盘「我的动态」只要数字——此前订阅 [observeUserFeedWithRelations]
     * 全量帖+评论+点赞再 `.size`，任一互动写入即全量重搬。WHERE 与其**逐字相同**（平价测试钉死）；且只观察
     * moment_post 一张表（原 @Transaction 关系查询观察 3 张）。
     */
    @Query("SELECT COUNT(*) FROM moment_post WHERE isSoftDeleted = 0 AND authorTypeRaw = 'user'")
    fun observeUserFeedCount(): Flow<Int>

    @Transaction
    @Query("SELECT * FROM moment_post WHERE uuid = :uuid LIMIT 1")
    suspend fun getPostWithRelations(uuid: String): MomentPostWithRelations?

    @Query("SELECT * FROM moment_post WHERE uuid = :uuid LIMIT 1")
    suspend fun getPost(uuid: String): MomentPostEntity?

    // ---- 全量导出（13.6 备份）：含软删帖，原样往返（恢复后清理 pass 自会处理 30 天硬删） ----

    @Query("SELECT * FROM moment_post ORDER BY timestamp ASC")
    suspend fun getAllPosts(): List<MomentPostEntity>

    @Query("SELECT * FROM moment_comment ORDER BY timestamp ASC")
    suspend fun getAllComments(): List<MomentCommentEntity>

    @Query("SELECT * FROM moment_like ORDER BY timestamp ASC")
    suspend fun getAllLikes(): List<MomentLikeEntity>

    /** Non-deleted feed size — pull-to-refresh diffs before/after to report "N new posts" (iOS `FriendCircleView`). */
    @Query("SELECT COUNT(*) FROM moment_post WHERE isSoftDeleted = 0")
    suspend fun feedCount(): Int

    // ---- Posts: generation guards (7.2.3) ----

    /** Daily post cap: posts by a character since start-of-day. Soft-deleted included, per iOS. */
    @Query("SELECT COUNT(*) FROM moment_post WHERE characterUuid = :characterUuid AND timestamp >= :since")
    suspend fun countPostsForCharacterSince(characterUuid: String, since: Long): Int

    /** 全表动态条数（性能采集规模数 `momentPosts`·图纸 §3.2·含软删，量的是库有多大）。 */
    @Query("SELECT COUNT(*) FROM moment_post")
    suspend fun countAllPosts(): Int

    /** 4h cooldown: timestamp of the character's most recent post. Soft-deleted included, per iOS. */
    @Query("SELECT timestamp FROM moment_post WHERE characterUuid = :characterUuid ORDER BY timestamp DESC LIMIT 1")
    suspend fun lastPostTimestampForCharacter(characterUuid: String): Long?

    /**
     * Gift-moment cooldown dedup (P9.2e): timestamp of THIS character's most recent gift-triggered post
     * (soft-deleted included, so deleting a post can't reset the 24h cooldown). Per-character, matching iOS
     * `lastGiftMomentTime(characterUUID:)` — the cooldown is per character, not global.
     */
    @Query(
        "SELECT timestamp FROM moment_post WHERE characterUuid = :characterUuid " +
            "AND triggerTypeRaw = 'gift_received' ORDER BY timestamp DESC LIMIT 1",
    )
    suspend fun lastGiftPostTimestampForCharacter(characterUuid: String): Long?

    /**
     * 宠物商店朋友圈冷却 dedup（P9.3c）：该角色最近一条 triggerType=pet_shop_purchase 帖时间戳（含软删，删帖不能
     * 重置 24h 冷却）。1:1 iOS `lastPetShopMomentTime(characterUUID:)`——per 角色（与 gift-moment 同结构）。
     */
    @Query(
        "SELECT timestamp FROM moment_post WHERE characterUuid = :characterUuid " +
            "AND triggerTypeRaw = 'pet_shop_purchase' ORDER BY timestamp DESC LIMIT 1",
    )
    suspend fun lastPetShopPostTimestampForCharacter(characterUuid: String): Long?

    /** A character's most recent posts (any soft-delete state) — the "don't repeat" dedup in the prompt (7.2.3). */
    @Query("SELECT * FROM moment_post WHERE characterUuid = :characterUuid ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentPostsForCharacter(characterUuid: String, limit: Int): List<MomentPostEntity>

    /** Most recent user-authored, non-deleted posts — injected into the post prompt for context (7.2.3). */
    @Query("SELECT * FROM moment_post WHERE authorTypeRaw = 'user' AND isSoftDeleted = 0 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentUserPosts(limit: Int): List<MomentPostEntity>

    /**
     * Recovery scenario B (7.2.5): non-deleted user posts in `(after, before)` — i.e. older than ~5min
     * (so we don't race a freshly-scheduled interaction) but within ~24h. Newest first.
     */
    @Query(
        "SELECT * FROM moment_post WHERE authorTypeRaw = 'user' AND isSoftDeleted = 0 " +
            "AND timestamp > :after AND timestamp < :before ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun recentUserPostsInWindow(after: Long, before: Long, limit: Int): List<MomentPostEntity>

    /** Recovery scenario C (7.2.5): same window as [recentUserPostsInWindow] but AI-authored posts. */
    @Query(
        "SELECT * FROM moment_post WHERE authorTypeRaw = 'character' AND isSoftDeleted = 0 " +
            "AND timestamp > :after AND timestamp < :before ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun recentCharacterPostsInWindow(after: Long, before: Long, limit: Int): List<MomentPostEntity>

    /** Chat-context (7.2.6): a character's own non-deleted posts newer than [cutoff], newest first. */
    @Query(
        "SELECT * FROM moment_post WHERE characterUuid = :characterUuid AND authorTypeRaw = 'character' " +
            "AND isSoftDeleted = 0 AND timestamp > :cutoff ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun recentCharacterOwnPosts(characterUuid: String, cutoff: Long, limit: Int): List<MomentPostEntity>

    /** Chat-context (7.2.6): non-deleted user posts newer than [cutoff], newest first (then filter by AI interaction). */
    @Query("SELECT * FROM moment_post WHERE authorTypeRaw = 'user' AND isSoftDeleted = 0 AND timestamp > :cutoff ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentUserPostsSince(cutoff: Long, limit: Int): List<MomentPostEntity>

    /**
     * 朋友圈消化候选（记忆改造一期·图纸 §3.5-B·升序）：水位线开区间 (from, to] 内、非软删的**该角色自发帖或用户帖**
     * （用户帖是否收由服务侧按该角色赞/评互动过滤）。窗口收窄逻辑与水位推进在 [com.situ.aichat.prompt.memory.MemoryDigestMaterialService]。
     */
    @Query(
        "SELECT * FROM moment_post WHERE isSoftDeleted = 0 AND timestamp > :from AND timestamp <= :to " +
            "AND (characterUuid = :charUuid OR authorTypeRaw = 'user') ORDER BY timestamp ASC",
    )
    suspend fun postsForDigest(charUuid: String, from: Long, to: Long): List<MomentPostEntity>

    // ---- Posts: writes & cleanup ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPost(post: MomentPostEntity)

    /** User-delete: hide from feed; the cleanup pass hard-deletes 30 days later. */
    @Query("UPDATE moment_post SET isSoftDeleted = 1 WHERE uuid = :uuid")
    suspend fun softDeletePost(uuid: String)

    /** Hard delete; comments + likes cascade via FK. */
    @Query("DELETE FROM moment_post WHERE uuid = :uuid")
    suspend fun hardDeletePost(uuid: String)

    /** All soft-deleted posts — the cleanup pass releases their image files early (7.2.5). */
    @Query("SELECT * FROM moment_post WHERE isSoftDeleted = 1")
    suspend fun allSoftDeletedPosts(): List<MomentPostEntity>

    /** Drop a post's image-path list after its files are deleted (cleanup, 7.2.5). */
    @Query("UPDATE moment_post SET imagePathsJson = '' WHERE uuid = :uuid")
    suspend fun clearPostImages(uuid: String)

    /** Hard-delete soft-deleted posts older than [cutoff] (cascade comments + likes); 30-day GC. */
    @Query("DELETE FROM moment_post WHERE isSoftDeleted = 1 AND timestamp < :cutoff")
    suspend fun hardDeleteSoftDeletedOlderThan(cutoff: Long)

    // ---- Comments ----

    @Query("SELECT * FROM moment_comment WHERE postUuid = :postUuid ORDER BY timestamp ASC")
    suspend fun commentsForPost(postUuid: String): List<MomentCommentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComment(comment: MomentCommentEntity)

    /** Refetch a reply target before commenting on it, to confirm it still exists (7.2.4). */
    @Query("SELECT * FROM moment_comment WHERE uuid = :uuid LIMIT 1")
    suspend fun getComment(uuid: String): MomentCommentEntity?

    @Query("DELETE FROM moment_comment WHERE uuid = :uuid")
    suspend fun deleteComment(uuid: String)

    /** Same-character dedup: skip a character that already commented on this post (7.2.4). */
    @Query("SELECT COUNT(*) FROM moment_comment WHERE postUuid = :postUuid AND characterUuid = :characterUuid")
    suspend fun commentCountByCharacter(postUuid: String, characterUuid: String): Int

    /** AI comments already on a post → remaining comment budget = max(0, limit - this) (7.2.4). */
    @Query("SELECT COUNT(*) FROM moment_comment WHERE postUuid = :postUuid AND authorTypeRaw = 'character'")
    suspend fun aiCommentCount(postUuid: String): Int

    /** Recovery scenario A (7.2.5): user comments newer than [cutoff], oldest first (compensate missing AI replies). */
    @Query("SELECT * FROM moment_comment WHERE authorTypeRaw = 'user' AND timestamp > :cutoff ORDER BY timestamp ASC LIMIT :limit")
    suspend fun recentUserComments(cutoff: Long, limit: Int): List<MomentCommentEntity>

    /** Recovery A: does a user comment already have an AI reply? (character comments whose parent is it). */
    @Query("SELECT COUNT(*) FROM moment_comment WHERE parentCommentUuid = :parentCommentUuid AND authorTypeRaw = 'character'")
    suspend fun characterReplyCount(parentCommentUuid: String): Int

    /** Recovery scenario C (7.2.5): character comments by someone OTHER than the post's author. */
    @Query("SELECT COUNT(*) FROM moment_comment WHERE postUuid = :postUuid AND authorTypeRaw = 'character' AND characterUuid != :excludeCharacterUuid")
    suspend fun otherCharacterCommentCount(postUuid: String, excludeCharacterUuid: String): Int

    // ---- Likes ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLike(like: MomentLikeEntity)

    /** All likes on a post (chat-context 7.2.6 needs each like's author + timestamp to describe reactions). */
    @Query("SELECT * FROM moment_like WHERE postUuid = :postUuid")
    suspend fun likesForPost(postUuid: String): List<MomentLikeEntity>

    /** Dedup: has this character already liked this post? (>0 = yes) (7.2.4). */
    @Query("SELECT COUNT(*) FROM moment_like WHERE postUuid = :postUuid AND characterUuid = :characterUuid")
    suspend fun existsLike(postUuid: String, characterUuid: String): Int

    /** Has the user liked this post? Drives the `.coLike` notification on a character's own post (7.2.4). */
    @Query("SELECT COUNT(*) FROM moment_like WHERE postUuid = :postUuid AND authorTypeRaw = 'user'")
    suspend fun existsUserLike(postUuid: String): Int

    /** Total likes on a post — the hardcoded per-post AI like cap is 5 (7.2.4). */
    @Query("SELECT COUNT(*) FROM moment_like WHERE postUuid = :postUuid")
    suspend fun likeCountForPost(postUuid: String): Int

    /** Un-like: remove the user's own like on a post (iOS `toggleLike` deletes the existing user like). */
    @Query("DELETE FROM moment_like WHERE postUuid = :postUuid AND authorTypeRaw = 'user'")
    suspend fun deleteUserLike(postUuid: String)

    /** Recovery scenario C (7.2.5): character likes by someone OTHER than the post's author. */
    @Query("SELECT COUNT(*) FROM moment_like WHERE postUuid = :postUuid AND authorTypeRaw = 'character' AND characterUuid != :excludeCharacterUuid")
    suspend fun otherCharacterLikeCount(postUuid: String, excludeCharacterUuid: String): Int

    // ---- 删角色清理（1:1 iOS MomentCleanupService.deleteCharacterMoments）----
    // 按序：先删该角色散落在所有帖（含他人帖）下的点赞 → 评论，再删该角色自己发的帖
    // （本人帖下他人的评论/点赞由 moment_comment/moment_like → moment_post 的 FK CASCADE 带走）。

    /** P1-44：角色名下全部帖 uuid（与 deletePostsByCharacter 同谓词=精确覆盖；删行前预捕获，撤「新动态」已弹通知）。 */
    @Query("SELECT uuid FROM moment_post WHERE characterUuid = :characterUuid")
    suspend fun postUuidsByCharacter(characterUuid: String): List<String>

    /** P1-44：该角色点赞/评论过的 distinct 帖 uuid（UNION 自带去重；撤互动已弹通知的枚举源——互动通知挂的是被互动的帖）。 */
    @Query(
        "SELECT postUuid FROM moment_like WHERE characterUuid = :characterUuid AND postUuid IS NOT NULL " +
            "UNION SELECT postUuid FROM moment_comment WHERE characterUuid = :characterUuid AND postUuid IS NOT NULL",
    )
    suspend fun interactedPostUuidsByCharacter(characterUuid: String): List<String>

    @Query("DELETE FROM moment_like WHERE characterUuid = :characterUuid")
    suspend fun deleteLikesByCharacter(characterUuid: String)

    @Query("DELETE FROM moment_comment WHERE characterUuid = :characterUuid")
    suspend fun deleteCommentsByCharacter(characterUuid: String)

    @Query("DELETE FROM moment_post WHERE characterUuid = :characterUuid")
    suspend fun deletePostsByCharacter(characterUuid: String)

    // ---- Notifications (drive red dot; routed to system notification in 7.2.4) ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(notification: MomentNotificationEntity)

    @Query("SELECT COUNT(*) FROM moment_notification WHERE isRead = 0")
    fun observeUnreadNotificationCount(): Flow<Int>

    @Query("SELECT * FROM moment_notification ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentNotifications(limit: Int): List<MomentNotificationEntity>

    /** Unread notifications, newest first — drives the in-app notification list (7.2.8, iOS caps at 200). */
    @Query("SELECT * FROM moment_notification WHERE isRead = 0 ORDER BY timestamp DESC LIMIT :limit")
    fun observeUnreadNotifications(limit: Int): Flow<List<MomentNotificationEntity>>

    /**
     * Locate a non-deleted post by its timestamp window (notifications store `postTimestamp` in seconds,
     * loosely coupled; iOS `markAsReadAndNavigate` matches ±0.001s). Null → post was deleted → toast (7.2.8).
     */
    @Query("SELECT uuid FROM moment_post WHERE isSoftDeleted = 0 AND timestamp >= :lo AND timestamp <= :hi ORDER BY timestamp DESC LIMIT 1")
    suspend fun findNonDeletedPostUuidByTimestamp(lo: Long, hi: Long): String?

    @Query("UPDATE moment_notification SET isRead = 1 WHERE id = :id")
    suspend fun markNotificationRead(id: Long)

    @Query("UPDATE moment_notification SET isRead = 1 WHERE isRead = 0")
    suspend fun markAllNotificationsRead()

    /** Clean up read notifications older than [cutoff], at most [limit] per pass (iOS: 30d, ≤50). */
    @Query(
        "DELETE FROM moment_notification WHERE id IN " +
            "(SELECT id FROM moment_notification WHERE isRead = 1 AND timestamp < :cutoff ORDER BY timestamp ASC LIMIT :limit)"
    )
    suspend fun deleteOldReadNotifications(cutoff: Long, limit: Int)

    /** 我们的日子·卷一·只读：该角色非软删自发帖的时间戳（事实层 momentPosts 按日计数·总图纸 §3.5）。 */
    @Query("SELECT timestamp FROM moment_post WHERE isSoftDeleted = 0 AND authorTypeRaw = 'character' AND characterUuid = :characterUuid")
    suspend fun postTimestampsByCharacter(characterUuid: String): List<Long>

    /** 我们的日子·卷一·只读：该角色的评论 + 点赞，加用户对该角色帖的评论 + 点赞，四路时间戳并集（事实层 momentInteractions 按日计数·总图纸 §3.5）。 */
    @Query(
        "SELECT c.timestamp FROM moment_comment c WHERE c.authorTypeRaw = 'character' AND c.characterUuid = :characterUuid " +
            "UNION ALL SELECT l.timestamp FROM moment_like l WHERE l.authorTypeRaw = 'character' AND l.characterUuid = :characterUuid " +
            "UNION ALL SELECT c.timestamp FROM moment_comment c JOIN moment_post p ON c.postUuid = p.uuid WHERE c.authorTypeRaw = 'user' AND p.characterUuid = :characterUuid " +
            "UNION ALL SELECT l.timestamp FROM moment_like l JOIN moment_post p ON l.postUuid = p.uuid WHERE l.authorTypeRaw = 'user' AND p.characterUuid = :characterUuid",
    )
    suspend fun interactionTimestampsForCharacter(characterUuid: String): List<Long>
}
