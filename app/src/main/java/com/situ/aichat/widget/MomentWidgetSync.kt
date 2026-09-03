package com.situ.aichat.widget

import com.situ.aichat.data.repository.MomentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 最新动态（朋友圈）小组件的响应式同步桥（13.9b，仿 [PetWidgetSync]）：App 级一处观察「最新角色帖」单行流
 * （只观察 moment_post 一张表·图纸 2026-09-03 §3.4），当**最新角色帖**变化（新帖到达 / 被删）时 nudge 小组件重渲染。
 *
 * 只观察「最新角色帖身份」变化（uuid+内容+时间戳），**不**观察相对时间的纯流逝——那交给小组件渲染时按当前时间
 * 重算 + [com.situ.aichat.work.WidgetRefreshWorker] 每 30 分定期兜底（用户拍板的刷新策略：事件驱动 + 现算 + 定期）。
 * [drop] 跳过首帧，[distinctUntilChanged] 去抖。由 [com.situ.aichat.ui.AppViewModel] 在 init 调 [start] 一次。
 */
@Singleton
class MomentWidgetSync @Inject constructor(
    private val momentRepository: MomentRepository,
    private val updater: MomentWidgetUpdater,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false

    /** 幂等启动；由 [com.situ.aichat.ui.AppViewModel] 在 init 调用一次。 */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            momentRepository.observeLatestCharacterPost()
                .map { post -> post?.let { Identity(it.uuid, it.content, it.timestamp) } }
                .distinctUntilChanged()
                .drop(1)
                .collect { updater.refresh() }
        }
    }

    /** 最新角色帖「身份指纹」：仅这些变化才重刷（相对时间流逝不在此列）。 */
    private data class Identity(val postUuid: String, val content: String, val timestamp: Long)
}
