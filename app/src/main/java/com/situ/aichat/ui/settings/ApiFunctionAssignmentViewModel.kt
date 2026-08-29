package com.situ.aichat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.entity.ApiConfigEntity
import com.situ.aichat.data.local.entity.resolvedConfigHasVision
import com.situ.aichat.data.local.entity.resolvedConfigOrNull
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.ApiFunctionRouter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ApiFunctionAssignmentViewModel @Inject constructor(
    private val repo: ApiConfigRepository,
    private val router: ApiFunctionRouter,
) : ViewModel() {

    val configs: StateFlow<List<ApiConfigEntity>> =
        repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeConfig: StateFlow<ApiConfigEntity?> =
        repo.observeActive().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val assignments: StateFlow<Map<ApiFunction, String>> =
        router.assignments.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * 「聊天对话」这一档当前解析到的模型看不看得懂图——用来在该行下方给一句大白话，
     * 告诉用户聊天「+」里到底会不会出现「照片」（这是本屏与聊天屏之间唯一不明显的联动）。
     */
    val chatVisionHint: StateFlow<FunctionVisionHint> = visionHintOf(ApiFunction.CHAT)

    /**
     * 「图片理解」这一档看不看得懂图——决定发出去的照片**有没有文字描述**。
     *
     * 为什么这行也值得一句提示（R4 §五·用户 2026-08-29 要求）：没指视觉模型时这条链会静默走兜底，
     * 照片在长期记忆里只剩「发送了一张图片」七个字，而向量库有一道**与图片无关的** 8 字下限
     * （`VectorMemoryService.MIN_CONTENT_LENGTH`）——七个字进不去，于是这张照片**永远搜不到**。
     * 屏上不说，用户没有任何途径发现这件事。
     */
    val imageUnderstandingVisionHint: StateFlow<FunctionVisionHint> =
        visionHintOf(ApiFunction.IMAGE_UNDERSTANDING)

    /**
     * 某一档功能当前解析到的配置有没有视觉能力（两行共用**同一份**判断，别再写第二遍）。
     * 谓词与聊天屏 `ChatViewModel.chatModelHasVision` 共用同一个 [resolvedConfigHasVision] 单源。
     */
    private fun visionHintOf(function: ApiFunction): StateFlow<FunctionVisionHint> = combine(
        router.assignments,
        repo.observeAll(),
        repo.observeActive(),
    ) { assignments, all, active ->
        val assigned = assignments[function]
        when {
            resolvedConfigOrNull(assigned, all, active) == null -> FunctionVisionHint.NO_CONFIG
            resolvedConfigHasVision(assigned, all, active) -> FunctionVisionHint.HAS_VISION
            else -> FunctionVisionHint.NO_VISION
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FunctionVisionHint.NO_CONFIG)

    /** Assign [uuid] to [function]; pass null to revert to the default (active) config. */
    fun setAssignment(function: ApiFunction, uuid: String?) {
        viewModelScope.launch { router.setAssignment(function, uuid) }
    }
}

/** 某一行下方那句「这个模型看不看得懂图」提示的三态（「聊天对话」与「图片理解」两行共用）。 */
enum class FunctionVisionHint { NO_CONFIG, HAS_VISION, NO_VISION }
