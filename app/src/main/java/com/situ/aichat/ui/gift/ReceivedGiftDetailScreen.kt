package com.situ.aichat.ui.gift

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.gift.GiftCatalog
import com.situ.aichat.gift.GiftItem
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.util.DateFormatters
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 收礼详情 VM（9.2d d-5，1:1 iOS `ReceivedGiftDetailView` 的数据装配，**纯只读不调 LLM**）。按 recordUuid 加载
 * 角色主动送用户的礼物记录 + 角色 + 目录项。
 */
@HiltViewModel
class ReceivedGiftDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val giftDao: GiftDao,
    private val characterRepo: CharacterRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val characterName: String = "",
        val avatarPath: String? = null,
        val item: GiftItem? = null,
        val giftName: String = "",
        val senderMessage: String = "",
        val timestamp: Long = 0L,
    )

    private val recordUuid: String = savedStateHandle.get<String>(ARG_RECORD_UUID).orEmpty()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val record = giftDao.getByUuid(recordUuid)
            val character = record?.let { characterRepo.get(it.senderCharacterUUID) }
            val item = record?.let { GiftCatalog.find(it.giftItemId) }
            _state.value = UiState(
                loading = false,
                characterName = character?.name.orEmpty(),
                avatarPath = character?.avatarPath,
                item = item,
                giftName = item?.name ?: record?.diyTitle?.ifEmpty { "礼物" } ?: "礼物",
                senderMessage = record?.senderMessage.orEmpty(),
                timestamp = record?.timestamp ?: 0L,
            )
        }
    }

    companion object {
        const val ARG_RECORD_UUID = "recordUuid"
    }
}

/**
 * 收礼详情页（9.2d d-5，1:1 iOS `ReceivedGiftDetailView`）：角色主动送用户礼物的只读展示。
 * 礼物大图 + 「来自 / 名称 / 角色名」+ 角色留言气泡（senderMessage 非空才显）+ 日期。**绝不调 LLM、绝不写数据。**
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceivedGiftDetailScreen(
    onBack: () -> Unit,
    viewModel: ReceivedGiftDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = "礼物详情",
                onBack = onBack,
                lifted = scrollState.value > 0,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 礼物展示
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.item?.let { GiftImage(item = it, size = 200.dp, cornerRadius = 24.dp) }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("来自", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(state.giftName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text(state.characterName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // 送礼留言气泡
            if (state.senderMessage.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    com.situ.aichat.ui.components.CharacterAvatar(name = state.characterName, avatarPath = state.avatarPath, size = 44.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                        Text(state.characterName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Text(
                                state.senderMessage,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            // 日期
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Text(
                    // gift-1：永久保存的收礼记录显示精确日期+时间（1:1 iOS .abbreviated+.shortened），不再随天数坍缩成「3天前」。
                    DateFormatters.mediumDateShortTime(state.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
