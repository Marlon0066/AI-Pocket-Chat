package com.situ.aichat.moments

import android.content.Context
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.MomentPostEntity
import com.situ.aichat.data.model.imagePaths
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.notification.Notifier
import com.situ.aichat.util.ContentImageStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「X 发了新动态」系统通知编排（13.7e·**安卓超越 iOS**：iOS 对角色自己的新帖零提醒、只在 feed 静默出现）。由
 * [MomentGenerationService] 在**后台周期** worker 这一轮发完帖后调用（回前台补发不调）。
 *
 * 职责：读每角色每天≤1 节流台账（[MomentNewPostNotifiedStore]）→ 纯函数决策（[MomentNewPostNotificationPlanner]）
 * → 单条（有图 BigPicture 首图 / 无图 BigText，深链该帖）或合并「N 位好友发了新动态」（深链 feed）→ 发出（[Notifier]，
 * 内部守卫 POST_NOTIFICATIONS + 建渠道）→ 标记台账。**不碰发帖/数值/概率/钱**——只在帖子已落库后多投一条通知。
 *
 * 注：自动发帖目前是纯文本（无图），故 BigPicture 几乎总退化为 BigText；首图仅在帖子真有图时出现（优雅降级）。
 */
@Singleton
class MomentNewPostNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val characterRepo: CharacterRepository,
) {

    /** 本轮新帖 → 按节流/合并规则推「新动态」通知。空列表 / 全被当天节流挡下 → 无操作。 */
    suspend fun notifyNewPosts(
        createdPosts: List<MomentPostEntity>,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        if (createdPosts.isEmpty()) return
        // 前台判定（卷一 C1）：App 真在前台（含线下见面剧场里）不弹「X 发了新动态」横幅——圈子页红点即提示
        // （2-5b 拍板同源·对照 ChatReplyDeliverer.notifyIfNotViewing）。**先于节流台账**：不弹就不该消耗
        // 「每角色每天≤1」的名额。ProcessLifecycleOwner 在纯 JVM 单测缺席 → runCatching 兜底（等效不弹）。
        val appForeground = runCatching {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState
                .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        }.getOrDefault(false)
        if (appForeground) return
        val alreadyNotified = createdPosts
            .mapNotNull { it.characterUuid }
            .distinct()
            .filter { MomentNewPostNotifiedStore.wasNotifiedToday(context, it, nowMillis, zone) }
            .toSet()

        when (val plan = MomentNewPostNotificationPlanner.plan(createdPosts, alreadyNotified)) {
            MomentNewPostNotificationPlanner.Plan.None -> return
            is MomentNewPostNotificationPlanner.Plan.Single -> notifySingle(plan.post, nowMillis)
            is MomentNewPostNotificationPlanner.Plan.Merged -> notifyMerged(plan.posts, nowMillis)
        }
    }

    private suspend fun notifySingle(post: MomentPostEntity, nowMillis: Long) {
        val charId = post.characterUuid ?: return
        val name = characterRepo.get(charId)?.name ?: return // 角色刚发完帖，正常存在；极端被删则跳过
        val body = post.content.ifBlank { context.getString(R.string.moment_newpost_default_body) }
        val image = ContentImageStore.load(post.imagePaths.firstOrNull())
        Notifier.postNewMomentPost(
            context,
            notificationId = singleNotificationId(post.uuid),
            title = name,
            body = body,
            image = image,
            postUuid = post.uuid,
        )
        MomentNewPostNotifiedStore.markNotified(context, charId, nowMillis)
    }

    private fun notifyMerged(posts: List<MomentPostEntity>, nowMillis: Long) {
        Notifier.postMergedMomentPosts(
            context,
            notificationId = MERGED_NOTIFICATION_ID,
            title = context.getString(R.string.moment_newpost_merged_title),
            body = context.getString(R.string.moment_newpost_merged_text, posts.size),
        )
        posts.forEach { post ->
            post.characterUuid?.let { MomentNewPostNotifiedStore.markNotified(context, it, nowMillis) }
        }
    }

    companion object {
        /** 合并通知用固定 id：后一条合并替换前一条（不堆叠）。P1-44 有意豁免不撤（文案无角色名+深链 feed 永活）。 */
        private val MERGED_NOTIFICATION_ID = "moment_newpost_merged".hashCode()

        /** 单帖通知用稳定 id（按帖 uuid）；P1-44 删角色撤已弹（MomentNotificationPurger）与发出共用单源。 */
        internal fun singleNotificationId(postUuid: String): Int = "moment_newpost:$postUuid".hashCode()
    }
}
