package com.situ.aichat.ui.world

import com.situ.aichat.world.WorldSeeds
import com.situ.aichat.world.stage.StageMode
import com.situ.aichat.world.stage.StagedCharacter
import com.situ.aichat.world.stage.StagedNative
import com.situ.aichat.world.stage.StagedPet
import com.situ.aichat.world.stage.WorldTownCast
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** 小镇头像卡类型（§4.6A·[atHome] = R1 🟡-2 白天在家·呼吸卡无月牙·下标「在家」·与 [sleeping] 互斥）。 */
internal sealed interface CastCardKind {
    data class Character(val staged: StagedCharacter, val sleeping: Boolean, val atHome: Boolean = false) : CastCardKind
    data class Native(val staged: StagedNative) : CastCardKind
    data class Pet(val staged: StagedPet) : CastCardKind
}

/** 稳定身份键（🔴-2·站点卡按 id 从当前 cast 解析最新卡·发现后神秘卡自动变名卡）。 */
internal fun CastCardKind.id(): String = when (this) {
    is CastCardKind.Character -> staged.uuid
    is CastCardKind.Native -> staged.nativeId
    is CastCardKind.Pet -> staged.petUuid
}

/**
 * 一张定位后的小镇卡（世界锚坐标）。[walking] = 三期卷一「居民走动」：true 仅「真在镇上闲逛」的环位客
 * （IN_TOWN 角色 + 无固定点原住民·含未发现神秘人）——web 页拿它开走动（x/z 退作静止锚）；
 * E5 退环位的睡着/在家卡恒 false（不许月牙满街跑）。GL 兜底忽略此字段照旧环摆。
 */
internal data class CastCard(val kind: CastCardKind, val x: Float, val y: Float, val z: Float, val walking: Boolean = false)

/** 溢出「+N」chip（同锚 >3 时·§4.6A·锚在组中心）。 */
internal data class CastOverflow(val x: Float, val y: Float, val z: Float, val count: Int)

internal data class CastPlacement(val cards: List<CastCard>, val overflows: List<CastOverflow>)

/**
 * 小镇演员卡位摆放（W9d 图纸 §4.6A·纯函数·§9 锁死规则）：AT_PLACE = 地点锚 top+2.0（环境地点 +1.6）·
 * SLEEPING = 认领民居（民居 index 稳定映射 uuid·y4.3·fillers 空 → 退 IN_TOWN 环位·E5）·IN_TOWN = 城中环位
 * （angle 2π·k/n·半径 2.6·y2.2·中心 per 城）。同锚多卡 world-x 扇形偏移 (k−(n−1)/2)×1.1·上限 3 张 + 溢出 chip（E12）。
 * 原住民 AT_PLACE 同规则、程序城落 IN_TOWN 环位（角色之后接续编号）。宠物落用户家 [homePlaceId]（§0.2「团子在你的家」）。
 */
internal object WorldCastAnchors {

    private const val CARD_CAP = 3
    private const val FAN_STEP = 1.1f

    /** 城中锚（§4.6A·yunye/taoqiu/xiyu/程序城）。 */
    private fun townCenter(cityId: String): Pair<Float, Float> = when (cityId) {
        "city_yunye" -> -1.8f to 1.8f
        "city_taoqiu" -> 0.0f to 0.6f
        "city_xiyu" -> 1.4f to 1.8f
        else -> 0.0f to 0.0f
    }

    /**
     * @param places placeId → (x, top, z)（小镇地点·top = 建筑锚高）
     * @param fillers 民居 (cx, cz) 列表
     * @param homePlaceId 用户家 placeId（宠物落此·通常 yunye_home·非家城传 null）
     */
    fun place(
        cast: WorldTownCast,
        places: Map<String, Triple<Float, Float, Float>>,
        fillers: List<Pair<Float, Float>>,
        homePlaceId: String?,
        hasInterior: (String) -> Boolean,
    ): CastPlacement {
        // 分桶。
        val ringChars = cast.characters.filter { it.mode == StageMode.IN_TOWN }.sortedBy { it.uuid }
        val ringNatives = cast.natives.filter { it.placeId == null }.sortedBy { it.nativeId }
        val ringN = ringChars.size + ringNatives.size
        // 三期卷一：真环位客（≠ E5 退环位的睡着/在家卡）即走动客。
        val walkingIds = buildSet {
            ringChars.forEach { add(it.uuid) }
            ringNatives.forEach { add(it.nativeId) }
        }
        val (cx, cz) = townCenter(cast.cityId)

        fun ringAnchor(k: Int): Triple<Float, Float, Float> {
            val a = 2.0 * PI * k / maxOf(1, ringN)
            return Triple(cx + 2.6f * cos(a).toFloat(), 2.2f, cz + 2.6f * sin(a).toFloat())
        }

        fun placeAnchor(placeId: String?): Triple<Float, Float, Float>? {
            val p = places[placeId] ?: return null
            val lift = if (hasInterior(placeId!!)) 2.0f else 1.6f
            return Triple(p.first, p.second + lift, p.third)
        }

        // uuid → 民居锚（稳定·fillers 空 → null 退环位）。
        fun houseAnchor(uuid: String): Triple<Float, Float, Float>? {
            if (fillers.isEmpty()) return null
            val idx = Math.floorMod(WorldSeeds.fnv1a64(uuid), fillers.size)
            val (fx, fz) = fillers[idx]
            return Triple(fx, 4.3f, fz)
        }

        val based = mutableListOf<Pair<CastCardKind, Triple<Float, Float, Float>>>()
        var ringK = 0
        for (c in ringChars) { based += CastCardKind.Character(c, sleeping = false) to ringAnchor(ringK); ringK++ }

        for (c in cast.characters) {
            when (c.mode) {
                StageMode.IN_TOWN -> Unit // 已入环
                StageMode.AT_PLACE -> placeAnchor(c.placeId)?.let { based += CastCardKind.Character(c, false) to it }
                StageMode.SLEEPING -> {
                    val h = houseAnchor(c.uuid)
                    if (h != null) based += CastCardKind.Character(c, sleeping = true) to h
                    else { based += CastCardKind.Character(c, false) to ringAnchor(ringK); ringK++ } // E5 兜底
                }
                StageMode.AT_HOME -> { // R1 🟡-2：同民居锚·呼吸卡·下标「在家」（fillers 空退环位=普通卡）
                    val h = houseAnchor(c.uuid)
                    if (h != null) based += CastCardKind.Character(c, sleeping = false, atHome = true) to h
                    else { based += CastCardKind.Character(c, false) to ringAnchor(ringK); ringK++ } // E5 兜底
                }
            }
        }
        for (n in cast.natives) {
            if (n.placeId == null) { based += CastCardKind.Native(n) to ringAnchor(ringK); ringK++ }
            else placeAnchor(n.placeId)?.let { based += CastCardKind.Native(n) to it }
        }
        for (p in cast.pets) placeAnchor(homePlaceId)?.let { based += CastCardKind.Pet(p) to it }

        return fanOut(based, walkingIds)
    }

    /** 同锚分组 → 扇形偏移 + 上限 3 + 溢出 chip（§4.6A·E12）。 */
    private fun fanOut(based: List<Pair<CastCardKind, Triple<Float, Float, Float>>>, walkingIds: Set<String>): CastPlacement {
        val groups = based.groupBy { key(it.second) }
        val cards = mutableListOf<CastCard>()
        val overflows = mutableListOf<CastOverflow>()
        for ((_, members) in groups) {
            val n = members.size
            val shown = members.take(CARD_CAP)
            shown.forEachIndexed { k, (kind, anchor) ->
                val dx = (k - (minOf(n, CARD_CAP) - 1) / 2f) * FAN_STEP
                cards += CastCard(kind, anchor.first + dx, anchor.second, anchor.third, walking = kind.id() in walkingIds)
            }
            if (n > CARD_CAP) {
                val a = members.first().second
                overflows += CastOverflow(a.first, a.second, a.third, n - CARD_CAP)
            }
        }
        return CastPlacement(cards, overflows)
    }

    private fun key(a: Triple<Float, Float, Float>): Long {
        // 0.1 世界单位量化的锚键（同地点/同民居归同组）。
        fun q(v: Float) = (v * 10f).roundToInt().toLong()
        return q(a.first) * 1_000_003L + q(a.third) * 1009L + q(a.second)
    }
}
