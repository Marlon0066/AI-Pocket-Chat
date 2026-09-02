package com.situ.aichat.maintenance

import android.util.Log
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.MessageDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「第一次聊天时间」补账（相识天数图纸 §4.1·D-3/D-4）：把 `characters.firstMessageDate` 补成「该角色最早一条
 * 非空内容消息」的时间戳。安卓移植期漏了这个字段的写入点，存量角色一律为空 —— 日程关系块、相识纪念日礼物、
 * 时间锚相识行三处消费者因此静默缺席。
 *
 * 每次冷启（[com.situ.aichat.ui.AppViewModel] 的 COLD_START_HEAL 块）与备份恢复成功后各跑一次，不设节流：
 * 一条 `GROUP BY` 查询 + ≤角色数条**带守卫**的 UPDATE（[CharacterDao.markFirstMessageDate] 只往早改），
 * 幂等、毫秒级；单角色写失败只跳过它，不拖垮整批。返回真写回的行数（观测行无条件打，见 D-10）。
 */
@Singleton
class FirstMessageDateBackfill @Inject constructor(
    private val messageDao: MessageDao,
    private val characterDao: CharacterDao,
) {

    /** 扫全部「有非空消息」的角色 → 逐个只往早改；返回真改动的行数。 */
    suspend fun run(): Int {
        val rows = messageDao.earliestNonEmptyTimestampByCharacter()
        var written = 0
        for (row in rows) {
            runCatching { written += characterDao.markFirstMessageDate(row.characterUuid, row.ts) }
                .onFailure { Log.w(TAG, "首聊时间补账 角色 ${row.characterUuid} 失败，跳过", it) }
        }
        Log.i(TAG, "首聊时间补账：扫 ${rows.size} 个有消息的角色，写回 $written")
        return written
    }

    private companion object {
        const val TAG = "FirstMessageDate"
    }
}
