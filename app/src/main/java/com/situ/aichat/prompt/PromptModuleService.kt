package com.situ.aichat.prompt

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 1:1 port of iOS `PromptModuleService` (pure-function form).
 *
 * iOS 版本带一个 OSAllocatedUnfairLock 缓存 + 会把默认模块写回 AppSettings；Android 侧把"读"做成
 * 无副作用的纯函数（[effectiveModules] 等），写回/持久化交给上层（设置页 / 仓库）。PromptBuilder 只读，
 * JSON 为空时直接返回 [defaultModules]，因此无需持久化即可工作。
 *
 * JSON 形状与 iOS Codable 兼容（[PromptModels] 的 @SerialName + 省略 null），便于未来导入 iOS 备份。
 */
object PromptModuleService {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false          // Swift 合成 Codable 对 nil 可选值省略 key
        encodeDefaults = true
    }

    // MARK: - 系统模块固定 UUID（照抄 iOS，大写；迁移老配置需 ID 稳定）

    private val systemModuleUUIDs: Map<SystemModuleType, String> = mapOf(
        SystemModuleType.CORE_RULES to "10000001-0000-0000-0000-000000000001",
        SystemModuleType.CHARACTER_IDENTITY to "10000001-0000-0000-0000-000000000002",
        SystemModuleType.SCENARIO to "10000001-0000-0000-0000-00000000000C",
        SystemModuleType.USER_PERSONA to "10000001-0000-0000-0000-000000000003",
        SystemModuleType.CHARACTER_GROWTH to "10000001-0000-0000-0000-00000000000A",
        SystemModuleType.CHARACTER_MEMORY to "10000001-0000-0000-0000-000000000004",
        SystemModuleType.TIME_AWARENESS to "10000001-0000-0000-0000-000000000005",
        SystemModuleType.CALENDAR_AWARENESS to "10000001-0000-0000-0000-000000000006",
        SystemModuleType.SCHEDULE_AWARENESS to "10000001-0000-0000-0000-00000000000F",
        SystemModuleType.CURRENT_MOMENT to "10000001-0000-0000-0000-000000000016",
        SystemModuleType.MOMENTS_CONTEXT to "10000001-0000-0000-0000-000000000007",
        SystemModuleType.RESPONSE_STYLE to "10000001-0000-0000-0000-00000000000D",
        SystemModuleType.CHAT_FORMAT to "10000001-0000-0000-0000-00000000000B",
        SystemModuleType.QUALITY_CONTROL to "10000001-0000-0000-0000-00000000000E",
        SystemModuleType.MOOD_EXPRESSION to "10000001-0000-0000-0000-000000000008",
        SystemModuleType.GENERAL_INSTRUCTIONS to "10000001-0000-0000-0000-000000000009",
        SystemModuleType.STICKER_LIBRARY to "10000001-0000-0000-0000-000000000010",
        SystemModuleType.PET_STATUS to "10000001-0000-0000-0000-000000000011",
        SystemModuleType.OFFLINE_MEETING_MEMORY to "10000001-0000-0000-0000-000000000012",
        SystemModuleType.GIFT_HISTORY to "10000001-0000-0000-0000-000000000013",
        SystemModuleType.CHARACTER_ECONOMIC_STATE to "10000001-0000-0000-0000-000000000014",
        SystemModuleType.BUSY_REPLY_INSTRUCTION to "10000001-0000-0000-0000-000000000015",
        SystemModuleType.OUR_DAYS to "10000001-0000-0000-0000-000000000017", // 「我们的日子」卷二（…016 已被 CURRENT_MOMENT 占）
    )

    private fun systemModuleUUID(type: SystemModuleType): String =
        systemModuleUUIDs[type] ?: "10000001-0000-0000-0000-0000000000FF"

    // MARK: - 默认模块列表（与 enum 顺序一致，sortOrder = index）

    fun defaultModules(): List<PromptModule> =
        SystemModuleType.entries.mapIndexed { index, type ->
            PromptModule(
                id = systemModuleUUID(type),
                name = type.displayName,
                content = "",
                sortOrder = index,
                isEnabled = true,
                isSystemGenerated = true,
                systemModuleType = type,
                position = type.defaultPosition,
                enabledScenes = type.defaultEnabledScenes,
            )
        }

    fun builtInPresets(): List<PromptModulePreset> = listOf(
        PromptModulePreset(
            id = "20000001-0000-0000-0000-000000000001",
            name = "默认",
            modules = defaultModules(),
            isBuiltIn = true,
        ),
    )

    // MARK: - 解码

    private fun decodeModules(jsonStr: String): List<PromptModule>? =
        if (jsonStr.isEmpty()) null
        else runCatching {
            json.decodeFromString(ListSerializer(PromptModule.serializer()), jsonStr)
        }.getOrNull()

    private fun decodeCharacterDict(jsonStr: String): Map<String, List<PromptModule>> =
        if (jsonStr.isEmpty()) emptyMap()
        else runCatching {
            json.decodeFromString(
                MapSerializer(String.serializer(), ListSerializer(PromptModule.serializer())),
                jsonStr,
            )
        }.getOrNull() ?: emptyMap()

    fun encodeModules(modules: List<PromptModule>): String =
        json.encodeToString(ListSerializer(PromptModule.serializer()), modules)

    private fun encodeCharacterDict(dict: Map<String, List<PromptModule>>): String =
        json.encodeToString(
            MapSerializer(String.serializer(), ListSerializer(PromptModule.serializer())),
            dict,
        )

    // MARK: - 角色专属覆盖（写）—— 对齐 iOS hasCharacterOverride / saveCharacterModules / removeCharacterOverride

    fun hasCharacterOverride(characterUuid: String, characterJson: String): Boolean =
        decodeCharacterDict(characterJson).containsKey(characterUuid)

    /** 写入/更新某角色的专属模块，返回新的 character dict JSON。 */
    fun setCharacterModules(characterUuid: String, modules: List<PromptModule>, characterJson: String): String {
        val dict = decodeCharacterDict(characterJson).toMutableMap()
        dict[characterUuid] = modules
        return encodeCharacterDict(dict)
    }

    /** 移除某角色的专属覆盖（回到全局），返回新的 character dict JSON。 */
    fun removeCharacterOverride(characterUuid: String, characterJson: String): String {
        val dict = decodeCharacterDict(characterJson).toMutableMap()
        dict.remove(characterUuid)
        return encodeCharacterDict(dict)
    }

    // MARK: - 预设（内置 + 自定义）

    /** 内置预设 + 用户自定义预设（自定义存于 presetsJson）。 */
    fun loadPresets(presetsJson: String): List<PromptModulePreset> {
        val custom = if (presetsJson.isEmpty()) {
            emptyList()
        } else {
            runCatching {
                json.decodeFromString(ListSerializer(PromptModulePreset.serializer()), presetsJson)
            }.getOrDefault(emptyList())
        }
        return builtInPresets() + custom
    }

    /** 仅持久化自定义预设（内置不入库），返回新的 presets JSON。 */
    fun savePresets(presets: List<PromptModulePreset>): String =
        json.encodeToString(
            ListSerializer(PromptModulePreset.serializer()),
            presets.filter { !it.isBuiltIn },
        )

    // MARK: - 全局 / 角色专属 / 有效模块

    /** 读取全局模块（JSON 为空或解码失败 → 默认；否则 reconcile）。 */
    fun loadGlobalModules(globalJson: String): List<PromptModule> {
        if (globalJson.isEmpty()) return defaultModules()
        val decoded = decodeModules(globalJson) ?: return defaultModules()
        return reconcileSystemModules(decoded)
    }

    /** 读取角色专属模块（无覆盖返回 null）。 */
    fun loadCharacterModules(characterUuid: String, characterJson: String): List<PromptModule>? =
        decodeCharacterDict(characterJson)[characterUuid]

    /** 获取指定角色的有效模块（有专属用专属，否则用全局）。 */
    fun effectiveModules(
        characterUuid: String?,
        globalJson: String,
        characterJson: String,
    ): List<PromptModule> {
        if (characterUuid != null) {
            val override = loadCharacterModules(characterUuid, characterJson)
            if (override != null) return reconcileSystemModules(override)
        }
        return loadGlobalModules(globalJson)
    }

    // MARK: - reconcile（删除无效系统模块、append 新增系统模块）

    fun reconcileSystemModules(modules: List<PromptModule>): List<PromptModule> {
        val result = modules.toMutableList()

        val validSystemIDs = systemModuleUUIDs.values.toSet()
        result.removeAll { it.isSystemGenerated && it.systemModuleType != null && it.id !in validSystemIDs }

        val existingIDs = result.map { it.id }.toMutableSet()
        var nextOrder = (result.maxOfOrNull { it.sortOrder } ?: -1) + 1
        for (type in SystemModuleType.entries) {
            val expectedID = systemModuleUUID(type)
            if (expectedID !in existingIDs) {
                result.add(
                    PromptModule(
                        id = expectedID,
                        name = type.displayName,
                        content = "",
                        sortOrder = nextOrder,
                        isEnabled = true,
                        isSystemGenerated = true,
                        systemModuleType = type,
                        position = type.defaultPosition,
                        enabledScenes = type.defaultEnabledScenes,
                    ),
                )
                existingIDs.add(expectedID)
                nextOrder += 1
            }
        }
        return result
    }

    // MARK: - G2 迁移：timeAwareness prefix → suffix（只在列表里改，返回是否有改动）

    fun migrateTimeAwarenessPosition(modules: MutableList<PromptModule>): Boolean {
        var changed = false
        for (i in modules.indices) {
            val m = modules[i]
            if (m.systemModuleType == SystemModuleType.TIME_AWARENESS &&
                m.position == PromptModulePosition.PREFIX
            ) {
                modules[i] = m.copy(position = PromptModulePosition.SUFFIX)
                changed = true
            }
        }
        return changed
    }

    // MARK: - G3 迁移：currentMoment sortOrder 调到紧贴 timeAwareness 之后

    fun migrateCurrentMomentSortOrder(modules: MutableList<PromptModule>): Boolean {
        val timeAware = modules.firstOrNull { it.systemModuleType == SystemModuleType.TIME_AWARENESS }
            ?: return false
        if (timeAware.position != PromptModulePosition.SUFFIX) return false  // 用户改了位置，跳过

        val targetSortOrder = timeAware.sortOrder + 1

        val cmIdx = modules.indexOfFirst { it.systemModuleType == SystemModuleType.CURRENT_MOMENT }
        if (cmIdx >= 0) {
            if (modules[cmIdx].position != PromptModulePosition.SUFFIX) return false  // 用户改成 prefix，跳过
            // 已紧贴则不改
            val suffixSorted = modules
                .filter { it.position == PromptModulePosition.SUFFIX }
                .sortedBy { it.sortOrder }
            val taIdx = suffixSorted.indexOfFirst { it.systemModuleType == SystemModuleType.TIME_AWARENESS }
            val cmIdxSuffix = suffixSorted.indexOfFirst { it.systemModuleType == SystemModuleType.CURRENT_MOMENT }
            if (taIdx >= 0 && cmIdxSuffix == taIdx + 1) return false
            modules[cmIdx] = modules[cmIdx].copy(sortOrder = targetSortOrder)
            return true
        }

        // currentMoment 不存在（pre-G3 老 JSON）→ append 到正确位置
        val type = SystemModuleType.CURRENT_MOMENT
        modules.add(
            PromptModule(
                id = systemModuleUUID(type),
                name = type.displayName,
                content = "",
                sortOrder = targetSortOrder,
                isEnabled = true,
                isSystemGenerated = true,
                systemModuleType = type,
                position = type.defaultPosition,
                enabledScenes = type.defaultEnabledScenes,
            ),
        )
        return true
    }

    // MARK: - G4 迁移：timeAwareness + currentMoment 调到 suffix 末尾（紧贴生成处，时间感知优化·修 A）

    /**
     * 把 timeAwareness + currentMoment 的 sortOrder 抬到「比其余所有模块都大」，使二者排到 suffix 末尾
     * （最贴近生成、对下条回复影响最大）。**只改 sortOrder**，不动 position/enabled/content。
     * 守卫：timeAwareness（或存在的 currentMoment）已被用户改成非 suffix → 跳过（尊重自定义）。
     * 已在末尾（ta 高于其余、cm 紧随其后）→ 幂等不动，返回 false。返回是否有改动。
     *
     * ⚠️ 未来维护：[reconcileSystemModules] 给「新增」系统模块分配 maxSortOrder+1，会落在已迁到末尾的
     * timeAwareness/currentMoment **之后**。若日后新增一个本应排在二者之上的 suffix 系统模块，须在引入时一并处理
     * （本一次性迁移只跑一次，不会再把二者重新压到末尾）。不在 reconcile 里强制压底，是为尊重用户拖动顺序。
     */
    fun migrateTimeAwarenessToBottom(modules: MutableList<PromptModule>): Boolean {
        val taIdx = modules.indexOfFirst { it.systemModuleType == SystemModuleType.TIME_AWARENESS }
        if (taIdx < 0 || modules[taIdx].position != PromptModulePosition.SUFFIX) return false
        val cmIdx = modules.indexOfFirst { it.systemModuleType == SystemModuleType.CURRENT_MOMENT }
        if (cmIdx >= 0 && modules[cmIdx].position != PromptModulePosition.SUFFIX) return false

        val maxOther = modules
            .filterIndexed { i, _ -> i != taIdx && i != cmIdx }
            .maxOfOrNull { it.sortOrder } ?: return false // 只有 ta(/cm)，无所谓顺序
        val taOrder = modules[taIdx].sortOrder
        val cmOrder = if (cmIdx >= 0) modules[cmIdx].sortOrder else null
        val alreadyBottom = taOrder > maxOther && (cmOrder == null || cmOrder > taOrder)
        if (alreadyBottom) return false

        modules[taIdx] = modules[taIdx].copy(sortOrder = maxOther + 1)
        if (cmIdx >= 0) modules[cmIdx] = modules[cmIdx].copy(sortOrder = maxOther + 2)
        return true
    }

    /**
     * 对全局 + 每个角色覆盖各自应用 [migrateTimeAwarenessToBottom]。无任何改动返回 null；否则返回
     * （新 globalJson 或 null，新 characterJson 或 null）——null 表示该块无需写回。供 [com.situ.aichat.data.repository
     * .SettingsRepository] 的一次性迁移调用。全局 JSON 空 = 用 [defaultModules]（已在末尾），不迁。
     */
    fun migratePromptModuleTimeOrder(globalJson: String, characterJson: String): Pair<String?, String?>? {
        val newGlobal: String? = globalJson
            .takeIf { it.isNotEmpty() }
            ?.let { decodeModules(it) }
            ?.toMutableList()
            ?.takeIf { migrateTimeAwarenessToBottom(it) }
            ?.let { encodeModules(it) }

        var characterChanged = false
        val dict = decodeCharacterDict(characterJson)
        val newDict = dict.mapValues { (_, mods) ->
            val mutable = mods.toMutableList()
            if (migrateTimeAwarenessToBottom(mutable)) {
                characterChanged = true
                mutable.toList()
            } else {
                mods
            }
        }
        val newCharacter = if (characterChanged) encodeCharacterDict(newDict) else null

        return if (newGlobal == null && newCharacter == null) null else newGlobal to newCharacter
    }

    // MARK: - 见面记忆迁移（2026-07-11 拍板：默认位置 SUFFIX→PREFIX·插到「角色记忆」正后）

    /**
     * 把 offlineMeetingMemory 从 SUFFIX 迁到 PREFIX、插缝到 characterMemory 正后（其余 sortOrder>=target 全 +1，
     * 不产生并列值）。守卫：用户已改过 position（非 SUFFIX）→ 跳过（尊重自定义·G4 同款只看 position，
     * 后置区内部拖动无法与默认可靠区分）。characterMemory 缺席或不在 PREFIX → 只翻 position、保留 sortOrder。
     * 幂等：迁移后 position=PREFIX，再跑必 false；一次性 flag 另在仓库层守（用户日后手动挪回 SUFFIX 不会被再迁）。
     */
    fun migrateMeetingMemoryToPrefix(modules: MutableList<PromptModule>): Boolean {
        val mmIdx = modules.indexOfFirst { it.systemModuleType == SystemModuleType.OFFLINE_MEETING_MEMORY }
        if (mmIdx < 0) return false // 极老 JSON 无此模块：reconcile 会按新默认（PREFIX）补，此处不管
        if (modules[mmIdx].position != PromptModulePosition.SUFFIX) return false
        val mem = modules.firstOrNull {
            it.systemModuleType == SystemModuleType.CHARACTER_MEMORY && it.position == PromptModulePosition.PREFIX
        }
        if (mem == null) {
            modules[mmIdx] = modules[mmIdx].copy(position = PromptModulePosition.PREFIX)
            return true
        }
        val target = mem.sortOrder + 1
        for (i in modules.indices) {
            if (i == mmIdx) continue
            val m = modules[i]
            if (m.sortOrder >= target) modules[i] = m.copy(sortOrder = m.sortOrder + 1)
        }
        modules[mmIdx] = modules[mmIdx].copy(position = PromptModulePosition.PREFIX, sortOrder = target)
        return true
    }

    /** 对全局 + 每个角色覆盖各自应用 [migrateMeetingMemoryToPrefix]；返回语义同 [migratePromptModuleTimeOrder]。 */
    fun migratePromptModuleMeetingMemory(globalJson: String, characterJson: String): Pair<String?, String?>? {
        val newGlobal: String? = globalJson
            .takeIf { it.isNotEmpty() }
            ?.let { decodeModules(it) }
            ?.toMutableList()
            ?.takeIf { migrateMeetingMemoryToPrefix(it) }
            ?.let { encodeModules(it) }

        var characterChanged = false
        val dict = decodeCharacterDict(characterJson)
        val newDict = dict.mapValues { (_, mods) ->
            val mutable = mods.toMutableList()
            if (migrateMeetingMemoryToPrefix(mutable)) {
                characterChanged = true
                mutable.toList()
            } else {
                mods
            }
        }
        val newCharacter = if (characterChanged) encodeCharacterDict(newDict) else null

        return if (newGlobal == null && newCharacter == null) null else newGlobal to newCharacter
    }

    // MARK: - 「我们的日子」归位迁移（卷二 2026-09-02·图纸 §3.2：紧随「见面记忆」·只动 sortOrder·缺席按 reconcile 字段插入）

    /**
     * 缺席：见面记忆（PREFIX）缺席 → 追加末尾，否则插缝到其正后（>= target 全 +1 不产并列）。在场：仅当仍在追加位（sortOrder ==
     * 全表最大 = reconcile 内存追加位）且见面记忆在 PREFIX 且尚未在正后才归位；挪过 / 已归位 / 无锚点 → false。只动 sortOrder；幂等。
     */
    fun migrateOurDaysAfterMeetingMemory(modules: MutableList<PromptModule>): Boolean {
        val type = SystemModuleType.OUR_DAYS
        val mm = modules.firstOrNull { it.systemModuleType == SystemModuleType.OFFLINE_MEETING_MEMORY && it.position == PromptModulePosition.PREFIX }
        val od = modules.indexOfFirst { it.systemModuleType == type }
        if (od >= 0) {
            if (mm == null || modules[od].sortOrder != modules.maxOf { it.sortOrder }) return false // 无锚点 / 不在追加位
            if (modules[od].sortOrder == mm.sortOrder + 1) return false // 已在正后
        }
        val target = if (mm == null) (modules.maxOfOrNull { it.sortOrder } ?: -1) + 1 else mm.sortOrder + 1
        for (i in modules.indices) {
            val m = modules[i]
            if (i != od && m.sortOrder >= target) modules[i] = m.copy(sortOrder = m.sortOrder + 1)
        }
        if (od >= 0) {
            modules[od] = modules[od].copy(sortOrder = target)
        } else {
            modules.add(
                PromptModule(
                    id = systemModuleUUID(type), name = type.displayName, content = "", sortOrder = target, isEnabled = true,
                    isSystemGenerated = true, systemModuleType = type, position = type.defaultPosition, enabledScenes = type.defaultEnabledScenes,
                ),
            )
        }
        return true
    }

    /** 对全局 + 每个角色覆盖各自应用 [migrateOurDaysAfterMeetingMemory]；返回语义同 [migratePromptModuleTimeOrder]。 */
    fun migratePromptModuleOurDays(globalJson: String, characterJson: String): Pair<String?, String?>? {
        val newGlobal: String? = globalJson
            .takeIf { it.isNotEmpty() }
            ?.let { decodeModules(it) }
            ?.toMutableList()
            ?.takeIf { migrateOurDaysAfterMeetingMemory(it) }
            ?.let { encodeModules(it) }

        var characterChanged = false
        val dict = decodeCharacterDict(characterJson)
        val newDict = dict.mapValues { (_, mods) ->
            val mutable = mods.toMutableList()
            if (migrateOurDaysAfterMeetingMemory(mutable)) {
                characterChanged = true
                mutable.toList()
            } else {
                mods
            }
        }
        val newCharacter = if (characterChanged) encodeCharacterDict(newDict) else null

        return if (newGlobal == null && newCharacter == null) null else newGlobal to newCharacter
    }

    // MARK: - 短信腔四件线下退场迁移（两语境模型 2026-07-12）

    /**
     * 短信腔四模块线下退场（两语境模型 2026-07-12）：enabledScenes==null（从未手改）→ 写
     * type.defaultEnabledScenes（单源，勿字面重复集合）；非 null（手改过）→ 零碰。幂等：迁后非 null 再跑必 false。
     */
    fun migrateSceneDefaultsForOfflineExit(modules: MutableList<PromptModule>): Boolean {
        val targets = setOf(
            SystemModuleType.CHAT_FORMAT, SystemModuleType.RESPONSE_STYLE,
            SystemModuleType.MOOD_EXPRESSION, SystemModuleType.STICKER_LIBRARY,
        )
        var changed = false
        for (i in modules.indices) {
            val m = modules[i]
            val type = m.systemModuleType ?: continue
            if (type in targets && m.enabledScenes == null) {
                modules[i] = m.copy(enabledScenes = type.defaultEnabledScenes)
                changed = true
            }
        }
        return changed
    }

    /** 对全局 + 每个角色覆盖各自应用 [migrateSceneDefaultsForOfflineExit]；返回语义同 [migratePromptModuleTimeOrder]。 */
    fun migratePromptModuleSceneDefaults(globalJson: String, characterJson: String): Pair<String?, String?>? {
        val newGlobal: String? = globalJson
            .takeIf { it.isNotEmpty() }
            ?.let { decodeModules(it) }
            ?.toMutableList()
            ?.takeIf { migrateSceneDefaultsForOfflineExit(it) }
            ?.let { encodeModules(it) }

        var characterChanged = false
        val dict = decodeCharacterDict(characterJson)
        val newDict = dict.mapValues { (_, mods) ->
            val mutable = mods.toMutableList()
            if (migrateSceneDefaultsForOfflineExit(mutable)) {
                characterChanged = true
                mutable.toList()
            } else {
                mods
            }
        }
        val newCharacter = if (characterChanged) encodeCharacterDict(newDict) else null

        return if (newGlobal == null && newCharacter == null) null else newGlobal to newCharacter
    }
}
