package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.backup.UserProfileExport
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.AppSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/**
 * 四小件图纸（2026-07-16）件①③装配级行为测试：角色身份块的**示例对话限定句**与用户人设块的
 * **相处偏好行 / 生日行**。断言从图纸 §4.1 物料区独立反推（锁定文案在此「重新打字」为字面量，
 * 不引实现常量），非照搬实现输出。
 *
 * 「同生同灭」是本卷件①的核心承诺（用户拍板①）：限定句住在 `pb_ident_examples` 段头资源里，
 * 示例为空时既有 `takeIf` 结构让整块（含段头）消失 → 限定句必然一并消失，零新逻辑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN") // 断言用中文物料（生产主语言）
class PromptBuilderPersonaIdentityTest {

    private val fixedNow = Instant.ofEpochMilli(1_750_000_000_000)
    /** 老包反序列化用（宽容未知字段）；提到类级，避免每条用例各造一个 Json 实例。 */
    private val lenientJson = Json { ignoreUnknownKeys = true }
    private fun strings() = PromptStrings(RuntimeEnvironment.getApplication())

    private fun character(examples: String = "") = CharacterEntity(
        uuid = "c1", name = "小雨", creationDate = 0L, exampleDialogues = examples,
    )

    private fun history(): List<MessageEntity> = listOf(
        MessageEntity(
            messageUUID = "u1", conversationUuid = "conv1", roleRaw = "user",
            content = "在干嘛", timestamp = fixedNow.toEpochMilli() - 60_000,
        ),
    )

    private fun systemText(
        character: CharacterEntity = character(),
        profile: UserProfileEntity? = null,
    ): String = PromptBuilder.buildMessages(
        character = character, sortedMessages = history(), userProfile = profile,
        appSettings = AppSettings(), strings = strings(), now = fixedNow,
    ).filter { it.role == "system" }.joinToString("\n\n") { it.content.orEmpty() }

    // ── 件①：示例对话限定句 ──

    @Test
    fun 示例非空_注入段头限定句与示例原文() {
        val text = systemText(character(examples = "用户：今天累吗\n小雨：还行，就是有点困"))
        assertTrue("段头限定句在", text.contains("示例对话（仅供你学习说话的语气和用词；消息条数与格式仍按【聊天格式】的规则来）："))
        assertTrue("示例原文在", text.contains("小雨：还行，就是有点困"))
        // 限定句指向的【聊天格式】块确实同装配存在（否则限定句指了个空气）。
        assertTrue("被指向的【聊天格式】块真实存在", text.contains("【聊天格式】"))
    }

    @Test
    fun 示例为空_限定句与段头一并消失() {
        // 用户拍板①：示例删空 → 限定句必须一并消失（既有 takeIf 结构保证「同生同灭」）。
        val text = systemText(character(examples = ""))
        assertFalse("限定句不出现", text.contains("仅供你学习说话的语气和用词"))
        assertFalse("段头整体不出现", text.contains("示例对话（"))
    }

    @Test
    fun 示例纯空白_现状仍注入_守卫是isNotEmpty非isNotBlank() {
        // 现状刻画（非规格背书·见图纸 §11 D-1）：`PromptBuilderContent.kt:90` 的守卫是
        // `takeIf { it.isNotEmpty() }`，身份块 10 个字段全部同构——纯空白（非空字符串）不触发消失。
        // 图纸 E1 写「为空/空白 → 整块消失」，「空白」一侧与真码不符；改成 isNotBlank 会同时改动
        // 另外 9 个字段的既有行为（违 §2.3 字节级一致 + 超本卷范围），故本卷不改，钉住真实行为待复核裁决。
        // 用户拍板①的「删空」= 真·空串，由上一条用例覆盖，承诺不受影响。
        val text = systemText(character(examples = "   \n  "))
        assertTrue("纯空白仍注入段头（现状）", text.contains("仅供你学习说话的语气和用词"))
    }

    @Test
    fun 示例含宏_照常解析后填入段头() {
        // E2：%s 的值先经 applyPromptMacros 解析再填充，注入顺序与既有行为不变。
        val text = systemText(character(examples = "{{user}}：在吗\n{{char}}：在的"))
        assertTrue("{{char}} 解析为角色名", text.contains("小雨：在的"))
        assertTrue("{{user}} 解析为用户名回退", text.contains("用户：在吗"))
        assertFalse("宏字面量不残留", text.contains("{{char}}"))
        assertTrue("限定句仍在", text.contains("仅供你学习说话的语气和用词"))
    }

    // ── 件③：相处偏好行 / 生日行 ──

    /** persona 段的准入门槛是 nickname 非空（`buildUserPersonaContent` 首两行），故各例统一带昵称。 */
    private fun profile(
        preference: String = "",
        birthday: Long? = null,
    ) = UserProfileEntity(
        id = 1, nickname = "阿宝", bio = "爱喝手冲",
        birthday = birthday, companionPreference = preference,
    )

    @Test
    fun 偏好非空_注入相处偏好行() {
        val text = systemText(profile = profile(preference = "想被哄，别讲道理"))
        assertTrue("偏好行在", text.contains("阿宝希望你这样和TA相处：想被哄，别讲道理"))
    }

    @Test
    fun 偏好为空_不注入偏好行_persona其余照旧() {
        // E3：空 → persona 段输出与现状一致（新行条件注入）。
        val text = systemText(profile = profile(preference = ""))
        assertFalse("偏好行不出现", text.contains("希望你这样和TA相处"))
        assertTrue("persona 既有内容照旧", text.contains("关于对方的信息：爱喝手冲"))
    }

    @Test
    fun 偏好纯空白_不注入偏好行() {
        // E3：本卷新行守卫用 isNotBlank（新写代码，不受 D-1 的既有 isNotEmpty 纹路约束）。
        val text = systemText(profile = profile(preference = "   "))
        assertFalse("纯空白不注入", text.contains("希望你这样和TA相处"))
    }

    @Test
    fun 偏好含宏_经解析后注入() {
        // E4：照 bio 纹路走 applyPromptMacros。
        val text = systemText(profile = profile(preference = "希望{{char}}多夸夸我"))
        assertTrue("{{char}} 解析为角色名", text.contains("希望小雨多夸夸我"))
        assertFalse("宏字面量不残留", text.contains("{{char}}"))
    }

    @Test
    fun 生日非空_注入月日行_不带年份() {
        // J1：只给月日不给年份。
        val text = systemText(profile = profile(birthday = utcMillisOf(1970, 3, 5)))
        assertTrue("生日行在", text.contains("阿宝的生日：3月5日"))
        assertFalse("不带年份", text.contains("1970"))
    }

    @Test
    fun 生日为null_不注入生日行() {
        // E6。
        val text = systemText(profile = profile(birthday = null))
        assertFalse("生日行不出现", text.contains("的生日："))
    }

    // ── T1-1：生日格式化纯函数（断言从 §4.1 pattern 物料独立反推） ──

    /** UTC 零点 millis 造数：与 M3 DatePicker 的 selectedDateMillis 同构（J2）。 */
    private fun utcMillisOf(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun 生日格式化_zh模式_月日() {
        assertEquals("3月5日", formatBirthdayForPrompt(utcMillisOf(1970, 3, 5), "M月d日"))
        assertEquals("个位月份不补零", "1月9日", formatBirthdayForPrompt(utcMillisOf(1995, 1, 9), "M月d日"))
        assertEquals("两位月份", "12月25日", formatBirthdayForPrompt(utcMillisOf(2000, 12, 25), "M月d日"))
    }

    @Test
    fun 生日格式化_en模式_ROOT下MMMM给缩写月名() {
        // ⚠️ 图纸 §7 T1-1 期望「March 5」，实测 Locale.ROOT 下 `MMMM` = **缩写**「Mar」——J2 的
        // 「ROOT 的英文月名满足 en 侧」经实测为伪（ROOT=Mar / ENGLISH=March）。本卷保留 §9 锁定的
        // Locale.ROOT（PITFALLS 1c 房子惯例·全库规矩优先于单份图纸的期望串），钉住真实输出；
        // 「Mar 5」对 LLM 同样清晰且同为确定性输出（不随设备语言漂移）。见 §11 D-3 待复核裁决。
        assertEquals("Mar 5", formatBirthdayForPrompt(utcMillisOf(1970, 3, 5), "MMMM d"))
        assertEquals("Dec 25", formatBirthdayForPrompt(utcMillisOf(2000, 12, 25), "MMMM d"))
    }

    @Test
    fun 生日格式化_不随设备默认语言漂移() {
        // Locale.ROOT 的真正价值（PITFALLS 1c 的立法目的）：输出与 JVM 默认 Locale 解耦。
        val default = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG")) // 阿拉伯语数字/月名最容易暴露漂移
            assertEquals("3月5日", formatBirthdayForPrompt(utcMillisOf(1970, 3, 5), "M月d日"))
            assertEquals("Mar 5", formatBirthdayForPrompt(utcMillisOf(1970, 3, 5), "MMMM d"))
        } finally {
            Locale.setDefault(default)
        }
    }

    @Test
    fun 生日格式化_UTC边界日不偏移() {
        // E8：12-31 / 01-01 按 UTC 取月日恒等于用户所选（东八区设备不把 12-31 读成 01-01）。
        assertEquals("12月31日", formatBirthdayForPrompt(utcMillisOf(1999, 12, 31), "M月d日"))
        assertEquals("1月1日", formatBirthdayForPrompt(utcMillisOf(2000, 1, 1), "M月d日"))
    }

    @Test
    fun 生日格式化_闰年2月29日() {
        // E7：格式化不做存在性校验（DatePicker 保证合法日期），闰日原样输出。
        assertEquals("2月29日", formatBirthdayForPrompt(utcMillisOf(2000, 2, 29), "M月d日"))
    }

    // ── T2-4：老备份包兜底 ──

    @Test
    fun 老备份包无相处偏好字段_反序列化兜底空串() {
        // E9：@Serializable 默认值兜底——老包 JSON 不含 companionPreference。
        val legacyJson = """{"nickname":"阿宝","bio":"爱喝手冲","birthday":5356800000}"""
        val restored = lenientJson.decodeFromString(UserProfileExport.serializer(), legacyJson)
        assertEquals("新字段兜底空串", "", restored.companionPreference)
        assertEquals("既有字段照常", "阿宝", restored.nickname)
        assertEquals("既有字段照常", 5_356_800_000L, restored.birthday)
    }

    @Test
    fun 新备份包往返_相处偏好不丢() {
        val json = Json.encodeToString(
            UserProfileExport.serializer(),
            UserProfileExport(nickname = "阿宝", companionPreference = "叫我阿宝"),
        )
        val back = Json.decodeFromString(UserProfileExport.serializer(), json)
        assertEquals("叫我阿宝", back.companionPreference)
    }
}
