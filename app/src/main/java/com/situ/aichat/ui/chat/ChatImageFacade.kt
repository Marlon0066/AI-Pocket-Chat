package com.situ.aichat.ui.chat

import android.content.Context
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.resolvedConfigHasVision
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.ApiFunctionRouter
import com.situ.aichat.util.GallerySaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 聊天屏的图片能力面（自 [ChatViewModel] 抽出——那个 VM 已在 800 行绝对红线上，本卷不该让它继续长）：
 * 发图入口的显隐依据 + 「保存到相册」。
 */
internal class ChatImageFacade(
    private val scope: CoroutineScope,
    private val appContext: Context,
    apiConfigRepo: ApiConfigRepository,
    functionRouter: ApiFunctionRouter,
) {
    /**
     * 「聊天对话」功能当前解析到的模型**看不看得懂图**——聊天「+」面板的「照片」入口据此显隐
     * （用户 2026-08-29 拍板修订原「入口常开」）。理由：发图给纯文本模型只会换回一句读不懂图的回复，
     * 与其事后降级不如根本不给这个按钮。
     *
     * 谓词走共用单源 [resolvedConfigHasVision]（回退语义与 `resolveConfigValues(CHAT)` 一致）。
     * 三个上游任一变化即刻重算——用户在设置里改完分配返回聊天，按钮立刻跟着变。
     */
    val hasVision: StateFlow<Boolean> = combine(
        functionRouter.assignments,
        apiConfigRepo.observeAll(),
        apiConfigRepo.observeActive(),
    ) { assignments, all, active ->
        resolvedConfigHasVision(assignments[ApiFunction.CHAT], all, active)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), false)

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    fun consumeToast() { _toast.value = null }

    /** 长按菜单「保存到相册」（契约 §B7）：写进 Pictures/AI Pocket Chat，成败都给一句提示。 */
    fun saveToGallery(imagePath: String?) {
        scope.launch {
            val ok = GallerySaver.saveImage(appContext, imagePath)
            _toast.value = appContext.getString(
                if (ok) R.string.chat_image_saved else R.string.chat_image_save_to_gallery_failed,
            )
        }
    }
}
