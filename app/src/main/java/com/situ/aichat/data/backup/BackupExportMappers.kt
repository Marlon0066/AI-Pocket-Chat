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
import com.situ.aichat.data.local.entity.UserWalletEntity
import java.io.File

// ════════════════════════════════ Entity → Export 映射（导出侧；从 BackupService 抽出·刀1·只搬不改） ════════════════════════════════

internal fun CharacterEntity.toExport(avatarArchiveKey: String?, chatWallpaperArchiveKey: String?) = CharacterExport(
    uuid = uuid,
    name = name,
    creationDate = creationDate,
    avatarData = null,
    avatarArchiveKey = avatarArchiveKey,
    chatWallpaperArchiveKey = chatWallpaperArchiveKey,
    systemPrompt = systemPrompt,
    personalityDescription = personalityDescription,
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

internal fun ConversationEntity.toExport(messages: List<MessageExport>) = ConversationExport(
    uuid = uuid,
    title = title,
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
    isInOfflineMode = isInOfflineMode,
    currentOfflineSessionId = currentOfflineSessionId,
    currentSceneProgress = currentSceneProgress,
    pendingOfflineSummarySessionId = pendingOfflineSummarySessionId,
    pendingOfflineSummaryFailCount = pendingOfflineSummaryFailCount,
    pendingOfflineSummaryLastAttemptAt = pendingOfflineSummaryLastAttemptAt,
    offlineSummaryFallbackSessionIds = offlineSummaryFallbackSessionIds,
    // 记忆改造二期·部件⑤ 场内前情提要（三列绝对快照原样往返）。
    inSceneRecapText = inSceneRecapText,
    inSceneRecapSessionKey = inSceneRecapSessionKey,
    inSceneRecapUntilMillis = inSceneRecapUntilMillis,
    messages = messages.ifEmpty { null },
)

internal fun MessageEntity.toExport(includeMedia: Boolean, media: MutableMap<String, String>): MessageExport {
    val audioKey = if (includeMedia && !audioRelativePath.isNullOrEmpty()) {
        readInto(media, "${BackupArchive.MEDIA_PREFIX}audio/$messageUUID.${fileExt(audioRelativePath, "wav")}", audioRelativePath)
    } else {
        null
    }
    val imageKey = if (includeMedia && !imageRelativePath.isNullOrEmpty()) {
        readInto(media, "${BackupArchive.MEDIA_PREFIX}images/$messageUUID.jpg", imageRelativePath)
    } else {
        null
    }
    val thumbKey = if (includeMedia && !imageThumbnailRelativePath.isNullOrEmpty()) {
        readInto(media, "${BackupArchive.MEDIA_PREFIX}images/${messageUUID}_thumb.jpg", imageThumbnailRelativePath)
    } else {
        null
    }
    return MessageExport(
        messageUUID = messageUUID,
        role = roleRaw,
        content = content,
        timestamp = timestamp,
        isVoiceMessage = isVoiceMessage,
        isPartOfVoiceCall = isPartOfVoiceCall,
        audioDuration = audioDuration,
        mediaMemorySummary = mediaMemorySummary,
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
        // 12.3: 带上 embedding（Base64），导入即可语义检索，无需重算。
        embedding = embedding?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
        audioArchiveKey = audioKey,
        imageArchiveKey = imageKey,
        imageThumbnailArchiveKey = thumbKey,
    )
}

internal fun MilestoneEntity.toExport() = MilestoneExport(
    relationshipName = relationshipName,
    establishedDate = establishedDate,
    reason = reason,
    triggerTypeRaw = triggerTypeRaw,
    phase = phase,
)

internal fun CharacterPetEntity.toExport() = CharacterPetExport(
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
)

internal fun CharacterWalletEntity.toExport() = CharacterWalletExport(
    uuid = uuid,
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

internal fun CharacterDailyScheduleEntity.toExport(events: List<ScheduleEventExport>) = ScheduleExport(
    uuid = uuid,
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
    events = events.ifEmpty { null },
)

internal fun ScheduleEventEntity.toExport() = ScheduleEventExport(
    uuid = uuid,
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

internal fun NotificationTemplateEntity.toExport() = NotificationTemplateExport(
    id = id,
    category = category,
    content = content,
    isUsed = isUsed,
    createdAt = createdAt,
)

internal fun UserWalletEntity.toExport() = UserWalletExport(
    uuid = uuid,
    createdAt = createdAt,
    coinBalance = coinBalance,
    totalEarned = totalEarned,
    totalSpent = totalSpent,
)

internal fun MomentPostEntity.toExport(imageArchiveKeys: List<String>?) = MomentPostExport(
    uuid = uuid,
    content = content,
    timestamp = timestamp,
    authorTypeRaw = authorTypeRaw,
    characterUuid = characterUuid,
    isAutoGenerated = isAutoGenerated,
    isSoftDeleted = isSoftDeleted,
    triggerTypeRaw = triggerTypeRaw,
    relatedGiftId = relatedGiftId,
    imageArchiveKeys = imageArchiveKeys,
)

internal fun MomentCommentEntity.toExport() = MomentCommentExport(
    uuid = uuid,
    content = content,
    timestamp = timestamp,
    authorTypeRaw = authorTypeRaw,
    characterUuid = characterUuid,
    replyToName = replyToName,
    postUuid = postUuid,
    parentCommentUuid = parentCommentUuid,
)

internal fun MomentLikeEntity.toExport() = MomentLikeExport(
    timestamp = timestamp,
    authorTypeRaw = authorTypeRaw,
    characterUuid = characterUuid,
    postUuid = postUuid,
)

internal fun DiaryEntryEntity.toExport(
    imageArchiveKeys: List<String>?,
    comments: List<DiaryCommentExport>?,
    reactions: List<DiaryReactionExport>?,
) = DiaryEntryExport(
    uuid = uuid,
    content = content,
    timestamp = timestamp,
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
    imageArchiveKeys = imageArchiveKeys,
    comments = comments,
    reactions = reactions,
)

internal fun DiaryCommentEntity.toExport() = DiaryCommentExport(
    id = id,
    content = content,
    timestamp = timestamp,
    characterUuid = characterUuid,
    parentCommentId = parentCommentId,
    isFromUser = isFromUser,
)

internal fun DiaryReactionEntity.toExport() = DiaryReactionExport(
    id = id,
    characterUuid = characterUuid,
    emoji = emoji,
    timestamp = timestamp,
)

internal fun MonthlyReviewEntity.toExport() = MonthlyReviewExport(
    uuid = uuid,
    monthStartMillis = monthStartMillis,
    content = content,
    moodCountsJson = moodCountsJson,
    generatedAt = generatedAt,
)

internal fun StoryEntity.toExport(chapters: List<StoryChapterExport>?, characterRoles: List<StoryCharacterRoleExport>?) = StoryExport(
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
    maxChapters = maxChapters,
    autoExtendCount = autoExtendCount,
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
    chapters = chapters,
    characterRoles = characterRoles,
)

internal fun StoryChapterEntity.toExport() = StoryChapterExport(
    id = id,
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

internal fun StoryCharacterRoleEntity.toExport() = StoryCharacterRoleExport(
    id = id,
    roleName = roleName,
    roleType = roleType,
    roleDescription = roleDescription,
    isUserRole = isUserRole,
    characterId = characterId,
    intimatePersona = intimatePersona,
)

internal fun GiftRecordEntity.toExport(diyImageArchiveKey: String?) = GiftRecordExport(
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
    diyImageArchiveKey = diyImageArchiveKey,
    context = context,
    senderMessage = senderMessage,
    reactionText = reactionText,
    reactionMoodEmoji = reactionMoodEmoji,
    affinityGain = affinityGain,
    relationshipImpactJSON = relationshipImpactJSON,
)

internal fun RedeemCodeUsageEntity.toExport() = RedeemCodeUsageExport(
    uuid = uuid,
    codeHash = codeHash,
    redeemedAt = redeemedAt,
    amount = amount,
)

// 💰流水台账往返（R2）：十字段全量保真，relatedEntityId 幂等 key 与 balanceAfter 快照原样进出。
internal fun CurrencyTransactionEntity.toExport() = CurrencyTransactionExport(
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

internal fun RedPacketRecordEntity.toExport() = RedPacketRecordExport(
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

internal fun MeetingAppointmentEntity.toExport() = MeetingAppointmentExport(
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

internal fun CustomStickerEntity.toExport(imageArchiveKey: String?) = CustomStickerExport(
    stickerUuid = stickerUuid,
    name = name,
    semanticDescription = semanticDescription,
    isAnimated = isAnimated,
    imageArchiveKey = imageArchiveKey,
    createdAt = createdAt,
    usageCount = usageCount,
)

/** 登记媒体绝对路径到 media（key=zip 相对键；字节推迟到流式写入时读盘）；空/文件不存在 → null。 */
private fun readInto(media: MutableMap<String, String>, key: String, absolutePath: String?): String? {
    if (absolutePath.isNullOrEmpty()) return null
    if (!File(absolutePath).exists()) return null
    media[key] = absolutePath
    return key
}

/** 取路径扩展名（小写，无点）；无扩展 → [default]。 */
internal fun fileExt(path: String?, default: String): String =
    path?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() } ?: default
