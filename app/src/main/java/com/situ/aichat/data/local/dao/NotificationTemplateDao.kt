package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.situ.aichat.data.local.entity.NotificationTemplateEntity

/** 通知文案模板读写（P6.1b）。[pickUnused] 实现「随机取未用 → 用完整批重置」的循环逻辑。 */
@Dao
interface NotificationTemplateDao {

    /** 该角色模板池最老一条的 createdAt（30 天时效翻新判定）。池空 → null。 */
    @Query("SELECT MIN(createdAt) FROM notification_templates WHERE characterId = :characterId")
    suspend fun oldestCreatedAt(characterId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(templates: List<NotificationTemplateEntity>)

    @Query("DELETE FROM notification_templates WHERE characterId = :characterId")
    suspend fun deleteForCharacter(characterId: String)

    /**
     * 原子替换该角色整池文案（删旧 + 插新同一事务）。生成器在新文案**到手之后**才调它——生成途中进程被杀，
     * 旧池原样保留，不会留下空池（2026-09-18 修「先删后写」）。
     */
    @Transaction
    suspend fun replaceForCharacter(characterId: String, templates: List<NotificationTemplateEntity>) {
        deleteForCharacter(characterId)
        insertAll(templates)
    }

    @Query("SELECT * FROM notification_templates WHERE characterId = :characterId")
    suspend fun allForCharacter(characterId: String): List<NotificationTemplateEntity>

    @Query("SELECT * FROM notification_templates WHERE characterId = :characterId AND category = :category")
    suspend fun forCategory(characterId: String, category: String): List<NotificationTemplateEntity>

    @Query("UPDATE notification_templates SET isUsed = :used WHERE id = :id")
    suspend fun setUsed(id: String, used: Boolean)

    @Query("UPDATE notification_templates SET isUsed = 0 WHERE characterId = :characterId AND category = :category")
    suspend fun resetCategory(characterId: String, category: String)

    /**
     * 取一条未使用文案并标记已用；该 category 全部用完则整批重置后再取；一条都没有返回 null
     * （调用方回退到保底文案）。对齐 iOS `SmartNotificationScheduler.pickTemplate`。
     * 随机选取在 Kotlin 侧做（SQLite RANDOM() 不便单测、跨实现不一致）。
     */
    @Transaction
    suspend fun pickUnused(characterId: String, category: String): String? {
        val all = forCategory(characterId, category)
        if (all.isEmpty()) return null
        val unused = all.filter { !it.isUsed }
        val pool = if (unused.isNotEmpty()) {
            unused
        } else {
            resetCategory(characterId, category)
            all
        }
        val picked = pool.random()
        setUsed(picked.id, true)
        return picked.content
    }
}
