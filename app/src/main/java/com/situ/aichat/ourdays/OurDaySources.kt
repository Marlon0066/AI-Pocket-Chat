package com.situ.aichat.ourdays

import com.situ.aichat.data.local.dao.DiaryDao
import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.MilestoneDao
import com.situ.aichat.data.local.dao.MomentDao
import com.situ.aichat.data.local.dao.OfflineMeetingMemoryDao
import com.situ.aichat.data.local.dao.PromiseDao
import com.situ.aichat.data.local.dao.RedPacketDao
import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.data.local.entity.GiftRecordEntity
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.data.local.entity.PromiseEntity
import com.situ.aichat.data.local.entity.RedPacketRecordEntity
import javax.inject.Inject

/**
 * 「我们的日子」事实层各源的**单角色全量**载入（卷一图纸 V-1）：一次载入服务整轮 catch-up（活动索引 + 每日事实共用），
 * 再在内存按日切。消息只取时间戳列表（正文按日另查·V-2）；红包行的金额列随行读出但**绝不进 facts**（§9.5 grep 钉）。
 */
data class OurDaySources(
    /** 该角色全部非空消息时间戳（含 system·活动索引粗筛用·细口径在 [OurDayFactsBuilder]）。 */
    val messageTimestamps: List<Long>,
    val meetings: List<OfflineMeetingMemoryEntity>,
    val gifts: List<GiftRecordEntity>,
    val redPackets: List<RedPacketRecordEntity>,
    val promises: List<PromiseEntity>,
    val milestones: List<MilestoneEntity>,
    val momentPostTimestamps: List<Long>,
    val momentInteractionTimestamps: List<Long>,
    val exchangeDiaries: List<DiaryEntryEntity>,
) {
    companion object {
        val EMPTY = OurDaySources(
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
    }
}

/** 九路源 → [OurDaySources]（总图纸 §3.5 只读查询 + 既有复用；不碰 Clock、不判日）。 */
class OurDaySourceLoader @Inject constructor(
    private val messageDao: MessageDao,
    private val offlineMeetingMemoryDao: OfflineMeetingMemoryDao,
    private val giftDao: GiftDao,
    private val redPacketDao: RedPacketDao,
    private val promiseDao: PromiseDao,
    private val milestoneDao: MilestoneDao,
    private val momentDao: MomentDao,
    private val diaryDao: DiaryDao,
) {
    suspend fun load(characterUuid: String): OurDaySources = OurDaySources(
        messageTimestamps = messageDao.nonEmptyTimestampsForCharacter(characterUuid),
        meetings = offlineMeetingMemoryDao.byCharacter(characterUuid),
        gifts = giftDao.allForCharacter(characterUuid),
        redPackets = redPacketDao.allForCharacter(characterUuid),
        promises = promiseDao.allByCharacter(characterUuid),
        milestones = milestoneDao.getForCharacter(characterUuid),
        momentPostTimestamps = momentDao.postTimestampsByCharacter(characterUuid),
        momentInteractionTimestamps = momentDao.interactionTimestampsForCharacter(characterUuid),
        exchangeDiaries = diaryDao.exchangeEntriesForCharacter(characterUuid),
    )
}
