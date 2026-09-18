package com.situ.aichat.pet

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.situ.aichat.data.local.dao.NotificationTemplateDao
import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.PetRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.notification.NotificationAlarmScheduler
import com.situ.aichat.notification.NotificationFallbackText
import com.situ.aichat.notification.NotificationPayload
import com.situ.aichat.notification.Notifier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 宠物饿/病提醒的精确闹钟调度器（13.7c；安卓超越 iOS·到点真叫）。
 *
 * 用 [PetReminderPredictor] 算出每只宠物下一条饿/病提醒的绝对时刻，烤进 [NotificationAlarmScheduler] 精确闹钟
 * （到点由 [com.situ.aichat.notification.NotificationAlarmReceiver] 经 [Notifier.postPet] 发出，App 被杀也弹）。
 * 文案用已生成的角色口吻模板（`pet_hungry`/`pet_sick`，随机取一条，**不标 used**——对齐 iOS `randomElement`，
 * 且本调度高频重排不该烧光模板池）；池里缺该分类时回落 [NotificationFallbackText] 的宠物保底短句。
 *
 * 重排时机由 [PetReminderSync]（App 级观察宠物流，覆盖喂食/护理/衰减/聊天等所有写路径）+
 * [com.situ.aichat.work.NotificationRescheduleWorker]（开机/每日/启动，精确闹钟不跨重启）驱动。喂食/护理后状态
 * 变化 → 自动重算重排（用户拍板「中途喂了就取消重排」）。每次重排**先撤两类旧闹钟**再排选中类，杜绝类别切换
 * （hungry↔sick）留下悬挂错类闹钟。宠物系统关 → 全撤（[reschedule] 撤后早退）。
 */
@Singleton
class PetReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val petRepository: PetRepository,
    private val characterRepository: CharacterRepository,
    private val templateDao: NotificationTemplateDao,
    private val alarmScheduler: NotificationAlarmScheduler,
) {

    /** 为所有宠物重算重排（读一次 settings；宠物系统关时逐只撤销）。 */
    suspend fun rescheduleAll(now: Long = System.currentTimeMillis()) {
        val settings = settingsRepo.getAppSettings()
        val pets = petRepository.getAll()
        Log.d(TAG, "宠物提醒全量重排·共 ${pets.size} 只")
        for (pet in pets) reschedule(pet, settings, now)
    }

    /** 重排单只宠物：先撤两类旧闹钟，再按预测排选中类（系统关/角色删/无文案/无需提醒 → 维持已撤）。 */
    suspend fun reschedule(pet: CharacterPetEntity, settings: AppSettings, now: Long = System.currentTimeMillis()) {
        cancelForCharacter(pet.characterUuid)
        if (!settings.petSystemEnabled) {
            Log.i(TAG, "宠物提醒跳过·宠物系统未开 char=${pet.characterUuid}")
            return
        }

        val plan = PetReminderPredictor.computePlan(pet, settings, now) ?: run {
            Log.d(TAG, "宠物提醒跳过·无需提醒 char=${pet.characterUuid}")
            return
        }
        val character = characterRepository.get(pet.characterUuid) ?: run {
            Log.i(TAG, "宠物提醒跳过·角色已删 char=${pet.characterUuid}")
            return // 角色已删 → 不排（1:1 iOS guard let character）
        }
        // 池里缺该分类（修复 JSON 丢了宠物键 / 生成途中留下空池…）→ 回落宠物保底短句，不再整条跳过：
        // 饿/病提醒是照顾功能，缺文案就不叫 = 宠物悄悄饿着（2026-09-18 改·原 1:1 iOS 跳过）。保底也取不到才跳过。
        val body = templateDao.forCategory(pet.characterUuid, plan.category).randomOrNull()?.content
            ?: NotificationFallbackText.pick(context, plan.category).takeIf { it.isNotBlank() }?.also {
                Log.i(TAG, "宠物提醒·无文案模板→保底短句 char=${pet.characterUuid} cat=${plan.category}")
            }
            ?: run {
                Log.i(TAG, "宠物提醒跳过·无文案模板且无保底 char=${pet.characterUuid} cat=${plan.category}")
                return
            }

        val key = requestKey(pet.characterUuid, plan.category)
        val payload = NotificationPayload(
            notificationId = key.hashCode(),
            title = pet.name.ifBlank { character.name }, // 1:1 iOS：宠物名空则用角色名
            body = body,
            characterId = pet.characterUuid, // 到点 postPet 深链进该角色宠物详情
            category = Notifier.CATEGORY_PET,
            requestKey = key,
            scheduledAtMillis = plan.fireAtMillis,
        )
        alarmScheduler.scheduleExact(key, plan.fireAtMillis, payload)
    }

    /**
     * 撤掉某角色宠物的两类提醒闹钟（13.7c 复核 MED 修复）。**删角色前必调**——删角色会 CASCADE 删宠物行，
     * 届时 [rescheduleAll] 遍历 `getAll()` 再也够不着该宠物的 key，已排的精确闹钟会变孤儿、到点错弹 stale 文案 +
     * 深链进已不存在的宠物详情。由 [com.situ.aichat.data.repository.CharacterDeletionCleaner] 在删除前调用。
     */
    fun cancelForCharacter(characterUuid: String) {
        alarmScheduler.cancel(requestKey(characterUuid, PetReminderPredictor.CATEGORY_HUNGRY))
        alarmScheduler.cancel(requestKey(characterUuid, PetReminderPredictor.CATEGORY_SICK))
    }

    /**
     * P1-25：删角色专用——撤闹钟之外，连**已弹**饿/病提醒一并撤下通知栏（cancel 不存在的 id=no-op）。
     * 普通重排 [reschedule] 绝不可改调本方法：它被喂食/护理/衰减高频触发，会把用户未读提醒从通知栏抹掉。
     */
    fun purgeForCharacter(characterUuid: String) {
        cancelForCharacter(characterUuid)
        val nm = NotificationManagerCompat.from(context)
        purgeRequestKeys(characterUuid).forEach { nm.cancel(it.hashCode()) }
    }

    companion object {
        private const val TAG = "PetReminderScheduler"

        /** 稳定唯一 key（对齐 iOS identifier `aichat_pet_<charId>_<cat>`；同 key 重排覆盖、撤销也用它）。 */
        private fun requestKey(characterUuid: String, category: String): String =
            "aichat_pet_${characterUuid}_$category"

        /** P1-25：删角色撤已弹宠物通知的候选 key 闭集（两类）。纯函数便于单测。 */
        internal fun purgeRequestKeys(characterUuid: String): List<String> = listOf(
            requestKey(characterUuid, PetReminderPredictor.CATEGORY_HUNGRY),
            requestKey(characterUuid, PetReminderPredictor.CATEGORY_SICK),
        )
    }
}
