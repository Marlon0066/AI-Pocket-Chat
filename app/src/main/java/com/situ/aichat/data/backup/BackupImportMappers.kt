package com.situ.aichat.data.backup

import android.util.Base64
import com.situ.aichat.data.local.entity.CharacterDailyScheduleEntity
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.local.entity.CharacterWalletEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.CurrencyTransactionEntity
import com.situ.aichat.data.local.entity.CustomStickerEntity
import com.situ.aichat.data.local.entity.DiaryCommentEntity
import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.data.local.entity.DiaryReactionEntity
import com.situ.aichat.data.local.entity.MonthlyReviewEntity
import com.situ.aichat.data.local.entity.GiftRecordEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.local.entity.MomentCommentEntity
import com.situ.aichat.data.local.entity.MomentLikeEntity
import com.situ.aichat.data.local.entity.MomentPostEntity
import com.situ.aichat.data.local.entity.NotificationTemplateEntity
import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import com.situ.aichat.data.local.entity.RedPacketRecordEntity
import com.situ.aichat.data.local.entity.RedeemCodeUsageEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.local.entity.StoryEntity
import java.util.UUID

// ════════════════════════════════ Export → Entity 映射（导入侧；从 BackupService 抽出·刀2·只搬不改） ════════════════════════════════

// affinitySense* are cache and excluded from backup (matches iOS); they take entity defaults.
internal fun CharacterExport.toEntity(avatarPath: String?, chatWallpaperPath: String?) = CharacterEntity(
    uuid = uuid,
    name = name,
    avatarPath = avatarPath,
    chatWallpaperPath = chatWallpaperPath,
    systemPrompt = systemPrompt,
    personalityDescription = personalityDescription,
    creationDate = creationDate,
    gender = gender,
    birthday = birthday,
    ageModeRaw = ageModeRaw,
    fixedAge = fixedAge,
    appearanceDescription = appearanceDescription,
    occupation = occupation,
    backstory = backstory,
    speakingStyle = speakingStyle,
    catchphrases = catchphrases,
    exampleDialogues = exampleDialogues,
    initialInterests = initialInterests,
    memorySummary = memorySummary,
    previousMemorySummary = previousMemorySummary,
    offlineMeetingMemorySummary = offlineMeetingMemorySummary,
    voiceIdentifier = voiceIdentifier,
    remoteVoiceID = remoteVoiceID,
    ttsEmotionRaw = ttsEmotionRaw,
    ttsSpeed = ttsSpeed,
    ttsPitch = ttsPitch,
    lastMoodEmoji = lastMoodEmoji,
    lastMoodText = lastMoodText,
    lastMoodColorName = lastMoodColorName,
    firstMessageDate = firstMessageDate,
    streakCount = streakCount,
    lastChatDate = lastChatDate,
    personalitySpectrumJSON = personalitySpectrumJSON,
    relationshipQualityJSON = relationshipQualityJSON,
    relationshipArchetypeId = relationshipArchetypeId,
    moodHistoryJSON = moodHistoryJSON,
    dynamicInterestsJSON = dynamicInterestsJSON,
    growthLogJSON = growthLogJSON,
    growthMetadataJSON = growthMetadataJSON,
    structuredMemoryJSON = structuredMemoryJSON,
    structuredMemoryMetadataJSON = structuredMemoryMetadataJSON,
    previousStructuredMemoryJSON = previousStructuredMemoryJSON,
    relationshipMessageCount = relationshipMessageCount,
    lastRelationshipAnalysisDate = lastRelationshipAnalysisDate,
    cityName = cityName,
    cityLatitude = cityLatitude,
    cityLongitude = cityLongitude,
    offlineThemeColorHex = offlineThemeColorHex,
    joinedWorld = joinedWorld,
    worldHomeCityId = worldHomeCityId,
    worldJoinedAt = worldJoinedAt,
    momentsDigestedUntilMillis = momentsDigestedUntilMillis,
    personalityAnchorJSON = personalityAnchorJSON,
    personaCompileMetaJSON = personaCompileMetaJSON,
    personaGainsJSON = personaGainsJSON,
    personaOperatorsJSON = personaOperatorsJSON,
    relationshipPressureJSON = relationshipPressureJSON,
    affectFieldJSON = affectFieldJSON,
    intentQueueJSON = intentQueueJSON,
    ourDaysBackfilledAt = ourDaysBackfilledAt,
)

internal fun ConversationExport.toEntity(characterUuid: String) = ConversationEntity(
    uuid = uuid,
    title = title,
    characterUuid = characterUuid,
    creationDate = creationDate,
    lastSummarizedMessageDate = lastSummarizedMessageDate,
    lastMemorySummarySuccessDate = lastMemorySummarySuccessDate,
    lastMemorySummaryFailureDate = lastMemorySummaryFailureDate,
    lastMemorySummaryAttemptDate = lastMemorySummaryAttemptDate,
    isPinned = isPinned,
    isArchived = isArchived,
    isReservedForNotifications = isReservedForNotifications,
    lastReadDate = lastReadDate,
    lastMessageDate = lastMessageDate,
    lastMessagePreview = lastMessagePreview,
    lastMessageRole = lastMessageRole,
    moodEmoji = moodEmoji,
    moodText = moodText,
    moodColorName = moodColorName,
    cachedUnreadCount = cachedUnreadCount,
    voiceRoundsSinceLastVoice = voiceRoundsSinceLastVoice,
    voiceNextThreshold = voiceNextThreshold,
    // 13.6：线下见面状态（旧 .json 无此字段 → 取默认；新 zip 全量还原）。
    isInOfflineMode = isInOfflineMode,
    currentOfflineSessionId = currentOfflineSessionId,
    currentSceneProgress = currentSceneProgress,
    pendingOfflineSummarySessionId = pendingOfflineSummarySessionId,
    pendingOfflineSummaryFailCount = pendingOfflineSummaryFailCount,
    pendingOfflineSummaryLastAttemptAt = pendingOfflineSummaryLastAttemptAt,
    offlineSummaryFallbackSessionIds = offlineSummaryFallbackSessionIds,
    // 记忆改造二期·部件⑤ 场内前情提要（旧 .json 无此三字段 → 取默认；新包全量还原）。
    inSceneRecapText = inSceneRecapText,
    inSceneRecapSessionKey = inSceneRecapSessionKey,
    inSceneRecapUntilMillis = inSceneRecapUntilMillis,
)

internal fun MessageExport.toEntity(conversationUuid: String) = MessageEntity(
    messageUUID = messageUUID,
    conversationUuid = conversationUuid,
    roleRaw = role,
    content = content,
    timestamp = timestamp,
    isVoiceMessage = isVoiceMessage,
    isPartOfVoiceCall = isPartOfVoiceCall,
    audioRelativePath = audioRelativePath,
    audioDuration = audioDuration,
    imageRelativePath = imageRelativePath,
    imageThumbnailRelativePath = imageThumbnailRelativePath,
    mediaMemorySummary = mediaMemorySummary,
    // 12.3: 新版备份带 embedding → 直接恢复（导入后历史记忆即刻可检索）；旧版备份无此字段=null →
    // 由 EmbeddingBackfillWorker 后台回填（修「导入备份后历史记忆永久失忆」缺口）。
    embedding = embedding?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() },
    isContentRevealed = isContentRevealed,
    isHeldForDelivery = isHeldForDelivery,
    scheduledDeliveryDate = scheduledDeliveryDate,
    quotedMessageUUID = quotedMessageUUID,
    quotedContent = quotedContent,
    quotedSenderRole = quotedSenderRole,
    emotionTag = emotionTag,
    isPetMessage = isPetMessage,
    isOfflineMode = isOfflineMode,
    offlineSessionId = offlineSessionId,
    messageKindRaw = messageKindRaw,
)

// iOS export carries no milestone id (reconstructed under the character); mint a fresh uuid.
internal fun MilestoneExport.toEntity(characterUuid: String) = MilestoneEntity(
    uuid = UUID.randomUUID().toString(),
    characterUuid = characterUuid,
    relationshipName = relationshipName,
    establishedDate = establishedDate,
    reason = reason,
    triggerTypeRaw = triggerTypeRaw,
    phase = phase,
)

internal fun RedeemCodeUsageExport.toEntity() = RedeemCodeUsageEntity(
    uuid = uuid,
    codeHash = codeHash,
    redeemedAt = redeemedAt,
    amount = amount,
)

internal fun CurrencyTransactionExport.toEntity() = CurrencyTransactionEntity(
    uuid = uuid,
    timestamp = timestamp,
    ownerTypeRaw = ownerTypeRaw,
    characterUuid = characterUuid,
    kindRaw = kindRaw,
    categoryRaw = categoryRaw,
    amount = amount,
    balanceAfter = balanceAfter,
    relatedEntityId = relatedEntityId,
    note = note,
)

/** 新 zip 导入用 Message 映射：audio/image 路径取自重存的新绝对路径（archiveKey→newPathByKey）。 */
internal fun MessageExport.toEntity(conversationUuid: String, newPathByKey: Map<String, String>) = MessageEntity(
    messageUUID = messageUUID,
    conversationUuid = conversationUuid,
    roleRaw = role,
    content = content,
    timestamp = timestamp,
    isVoiceMessage = isVoiceMessage,
    isPartOfVoiceCall = isPartOfVoiceCall,
    audioRelativePath = audioArchiveKey?.let { newPathByKey[it] } ?: audioRelativePath,
    audioDuration = audioDuration,
    imageRelativePath = imageArchiveKey?.let { newPathByKey[it] } ?: imageRelativePath,
    imageThumbnailRelativePath = imageThumbnailArchiveKey?.let { newPathByKey[it] } ?: imageThumbnailRelativePath,
    mediaMemorySummary = mediaMemorySummary,
    embedding = embedding?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() },
    isContentRevealed = isContentRevealed,
    isHeldForDelivery = isHeldForDelivery,
    scheduledDeliveryDate = scheduledDeliveryDate,
    quotedMessageUUID = quotedMessageUUID,
    quotedContent = quotedContent,
    quotedSenderRole = quotedSenderRole,
    emotionTag = emotionTag,
    isPetMessage = isPetMessage,
    isOfflineMode = isOfflineMode,
    offlineSessionId = offlineSessionId,
    messageKindRaw = messageKindRaw,
)

internal fun CharacterPetExport.toEntity(characterUuid: String) = CharacterPetEntity(
    uuid = uuid,
    name = name,
    speciesRaw = speciesRaw,
    isHiddenSpecies = isHiddenSpecies,
    personalityTypeRaw = personalityTypeRaw,
    adoptedDate = adoptedDate,
    hunger = hunger,
    cleanliness = cleanliness,
    happiness = happiness,
    health = health,
    growthStageRaw = growthStageRaw,
    growthPoints = growthPoints,
    totalInteractions = totalInteractions,
    lastFedDate = lastFedDate,
    lastCleanedDate = lastCleanedDate,
    lastPlayedDate = lastPlayedDate,
    lastInteractionDate = lastInteractionDate,
    neglectPhaseRaw = neglectPhaseRaw,
    petGrowthLogJson = petGrowthLogJson,
    petMetadataJson = petMetadataJson,
    characterUuid = characterUuid,
)

// 💰 角色钱包：?? 0 不钳位（与用户钱包 max(0) 刻意不同 = iOS）；DTO 字段非空 Int 已等价 ?? 0。
internal fun CharacterWalletExport.toEntity(characterUuid: String) = CharacterWalletEntity(
    uuid = uuid,
    characterUuid = characterUuid,
    createdAt = createdAt,
    coinBalance = coinBalance,
    totalEarned = totalEarned,
    totalSpent = totalSpent,
    monthlySalary = monthlySalary,
    salaryInferred = salaryInferred,
    salaryDay = salaryDay,
    lastSalaryDate = lastSalaryDate,
    lastEconomicScanDate = lastEconomicScanDate,
    lastProactiveGiftDate = lastProactiveGiftDate,
    affinityFromUser = affinityFromUser,
    affinityToUser = affinityToUser,
)

internal fun ScheduleExport.toEntity(characterUuid: String) = CharacterDailyScheduleEntity(
    uuid = uuid,
    characterUuid = characterUuid,
    date = date,
    cityName = cityName,
    weatherCondition = weatherCondition,
    weatherEmoji = weatherEmoji,
    temperatureHigh = temperatureHigh,
    temperatureLow = temperatureLow,
    timezoneIdentifier = timezoneIdentifier,
    generatedAt = generatedAt,
    lastWeatherCheckAt = lastWeatherCheckAt,
    isBackfilled = isBackfilled,
)

internal fun ScheduleEventExport.toEntity(scheduleUuid: String) = ScheduleEventEntity(
    uuid = uuid,
    scheduleUuid = scheduleUuid,
    startTime = startTime,
    endTime = endTime,
    periodLabel = periodLabel,
    location = location,
    activity = activity,
    moodEmoji = moodEmoji,
    moodText = moodText,
    innerThought = innerThought,
    isPhoneAvailable = isPhoneAvailable,
    eventTypeRaw = eventTypeRaw,
    relatedCharacterNames = relatedCharacterNames,
    relatedMessageUUID = relatedMessageUUID,
    sourceRaw = sourceRaw,
    sortOrder = sortOrder,
)

internal fun NotificationTemplateExport.toEntity(characterUuid: String) = NotificationTemplateEntity(
    id = id,
    characterId = characterUuid,
    category = category,
    content = content,
    isUsed = isUsed,
    createdAt = createdAt,
)

internal fun MomentPostExport.toEntity(imagePathsJson: String) = MomentPostEntity(
    uuid = uuid,
    content = content,
    timestamp = timestamp,
    authorTypeRaw = authorTypeRaw,
    characterUuid = characterUuid,
    isAutoGenerated = isAutoGenerated,
    imagePathsJson = imagePathsJson,
    isSoftDeleted = isSoftDeleted,
    triggerTypeRaw = triggerTypeRaw,
    relatedGiftId = relatedGiftId,
)

internal fun MomentCommentExport.toEntity() = MomentCommentEntity(
    uuid = uuid,
    content = content,
    timestamp = timestamp,
    authorTypeRaw = authorTypeRaw,
    characterUuid = characterUuid,
    replyToName = replyToName,
    postUuid = postUuid,
    parentCommentUuid = parentCommentUuid,
)

// like 无业务 uuid → id 取自增（autoGenerate）；帖 REPLACE 已 FK CASCADE 清旧赞，不会重复。
internal fun MomentLikeExport.toEntity() = MomentLikeEntity(
    timestamp = timestamp,
    authorTypeRaw = authorTypeRaw,
    characterUuid = characterUuid,
    postUuid = postUuid,
)

internal fun DiaryEntryExport.toEntity(imagePathsJson: String) = DiaryEntryEntity(
    uuid = uuid,
    content = content,
    timestamp = timestamp,
    imagePathsJson = imagePathsJson,
    moodEmoji = moodEmoji,
    moodText = moodText,
    isAutoGenerated = isAutoGenerated,
    isDraft = isDraft,
    isPetDiary = isPetDiary,
    petSpeciesRaw = petSpeciesRaw,
    visibilityRaw = visibilityRaw,
    triggerTypeRaw = triggerTypeRaw,
    relatedGiftId = relatedGiftId,
    authorCharacterUuid = authorCharacterUuid,
    authorNameSnapshot = authorNameSnapshot,
    digestedAtMillis = digestedAtMillis,
)

internal fun DiaryCommentExport.toEntity(entryUuid: String) = DiaryCommentEntity(
    id = id,
    entryUuid = entryUuid,
    content = content,
    timestamp = timestamp,
    characterUuid = characterUuid,
    parentCommentId = parentCommentId,
    isFromUser = isFromUser,
)

internal fun DiaryReactionExport.toEntity(entryUuid: String) = DiaryReactionEntity(
    id = id,
    entryUuid = entryUuid,
    characterUuid = characterUuid,
    emoji = emoji,
    timestamp = timestamp,
)

internal fun MonthlyReviewExport.toEntity() = MonthlyReviewEntity(
    uuid = uuid,
    monthStartMillis = monthStartMillis,
    content = content,
    moodCountsJson = moodCountsJson,
    generatedAt = generatedAt,
)

/**
 * 备份包 → 故事实体。**卷二 E9 归一化**：`maxChapters`/`autoExtendCount` 一律落 null/0，无视包里的值——
 * 有限连载模式已整体退役（J2 存量转无限），老备份包里的「共 60 章 / 已自动扩展 2 次」不许还魂
 * （引擎侧满章/扩展分支已删，带回来只会是永远读不到的死数据）。两列本身保留不删（J3）。
 */
internal fun StoryExport.toEntity() = StoryEntity(
    id = id,
    title = title,
    genre = genre,
    coverColorScheme = coverColorScheme,
    createdAt = createdAt,
    updatedAt = updatedAt,
    worldSetting = worldSetting,
    plotDirection = plotDirection,
    writingStyle = writingStyle,
    chapterLengthPreference = chapterLengthPreference,
    maxChapters = null,
    autoExtendCount = 0,
    chatInfluenceWeight = chatInfluenceWeight,
    narrativePerson = narrativePerson,
    updateMode = updateMode,
    unlockHour = unlockHour,
    unlockMinute = unlockMinute,
    status = status,
    storySummary = storySummary,
    currentArc = currentArc,
    characterStates = characterStates,
    openThreads = openThreads,
    storyBible = storyBible,
    lastCompressedAtChapter = lastCompressedAtChapter,
    lastBibleCompressedAtChapter = lastBibleCompressedAtChapter,
    storyOutline = storyOutline,
    pendingChapterBeats = pendingChapterBeats,
    pendingBeatsUserEdited = pendingBeatsUserEdited,
    currentArcStartChapter = currentArcStartChapter,
    arcHistory = arcHistory,
    intimacyLedger = intimacyLedger,
    sceneState = sceneState,
    sceneLedger = sceneLedger,
    customPromptsJson = customPromptsJson,
    requestedEndingType = requestedEndingType,
    requestedEndingDetail = requestedEndingDetail,
    rewriteInstruction = rewriteInstruction,
    pendingRewriteDraftJson = pendingRewriteDraftJson,
    finaleEndingType = finaleEndingType,
    finaleEndingDetail = finaleEndingDetail,
    finalEndingType = finalEndingType,
    cachedChapterCount = cachedChapterCount,
    cachedLatestChapterNumber = cachedLatestChapterNumber,
    cachedLatestChapterTitle = cachedLatestChapterTitle,
    cachedLatestChapterCreatedAt = cachedLatestChapterCreatedAt,
    cachedHasPendingChoice = cachedHasPendingChoice,
)

internal fun StoryChapterExport.toEntity(storyId: String) = StoryChapterEntity(
    id = id,
    storyId = storyId,
    chapterNumber = chapterNumber,
    title = title,
    teaser = teaser,
    createdAt = createdAt,
    content = content,
    mood = mood,
    scenes = scenes,
    hasChoice = hasChoice,
    choicePrompt = choicePrompt,
    choiceOptions = choiceOptions,
    userChoice = userChoice,
    choiceMadeAt = choiceMadeAt,
    chapterSummary = chapterSummary,
    unlockAt = unlockAt,
    aiSuggestedEnding = aiSuggestedEnding,
    previousDraftJson = previousDraftJson,
    userRating = userRating,
)

internal fun StoryCharacterRoleExport.toEntity(storyId: String) = StoryCharacterRoleEntity(
    id = id,
    storyId = storyId,
    roleName = roleName,
    roleType = roleType,
    roleDescription = roleDescription,
    isUserRole = isUserRole,
    characterId = characterId,
    intimatePersona = intimatePersona,
)

// 💰 礼物记录：纯历史，导入不动钱包余额。
internal fun GiftRecordExport.toEntity(newPathByKey: Map<String, String>) = GiftRecordEntity(
    uuid = uuid,
    timestamp = timestamp,
    senderType = senderType,
    senderCharacterUUID = senderCharacterUUID,
    receiverType = receiverType,
    receiverCharacterUUID = receiverCharacterUUID,
    giftItemId = giftItemId,
    pricePaid = pricePaid,
    isDIY = isDIY,
    diyTitle = diyTitle,
    diyContent = diyContent,
    diyImagePath = diyImageArchiveKey?.let { newPathByKey[it] },
    context = context,
    senderMessage = senderMessage,
    reactionText = reactionText,
    reactionMoodEmoji = reactionMoodEmoji,
    affinityGain = affinityGain,
    relationshipImpactJSON = relationshipImpactJSON,
)

// 💰 红包记录：托管金额快照，导入不重复扣/加币（余额已由钱包快照反映）。
internal fun RedPacketRecordExport.toEntity() = RedPacketRecordEntity(
    uuid = uuid,
    messageUuid = messageUuid,
    conversationUuid = conversationUuid,
    senderType = senderType,
    senderCharacterUUID = senderCharacterUUID,
    receiverType = receiverType,
    receiverCharacterUUID = receiverCharacterUUID,
    amount = amount,
    blessingText = blessingText,
    festivalId = festivalId,
    status = status,
    createdAt = createdAt,
    expiresAt = expiresAt,
    resolvedAt = resolvedAt,
    rejectionReason = rejectionReason,
    notifiedExpiringSoon = notifiedExpiringSoon,
)

// 未来约定见面：无钱路，纯状态往返。confirmed 恢复后到点通知由打开会话/冷启扫描重排（设备本地通知换机即失，靠数据重建）。
internal fun MeetingAppointmentExport.toEntity() = MeetingAppointmentEntity(
    uuid = uuid,
    characterUuid = characterUuid,
    conversationUuid = conversationUuid,
    status = status,
    proposedBy = proposedBy,
    source = source,
    scheduledAt = scheduledAt,
    timeGranularity = timeGranularity,
    rawWhenText = rawWhenText,
    location = location,
    activity = activity,
    invitationText = invitationText,
    tensionHint = tensionHint,
    hiddenTensionSeed = hiddenTensionSeed,
    createdAt = createdAt,
    confirmedAt = confirmedAt,
    outcomeAt = outcomeAt,
    honoredSessionId = honoredSessionId,
    lastReminderScheduledAt = lastReminderScheduledAt,
)

internal fun CustomStickerExport.toEntity(newPathByKey: Map<String, String>) = CustomStickerEntity(
    stickerUuid = stickerUuid,
    name = name,
    semanticDescription = semanticDescription,
    isAnimated = isAnimated,
    imagePath = imageArchiveKey?.let { newPathByKey[it] } ?: "",
    createdAt = createdAt,
    usageCount = usageCount,
)
