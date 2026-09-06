package com.situ.aichat.ui.liuli.glass

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Shape
import com.situ.aichat.data.model.GlassTier
import com.situ.aichat.ui.liuli.designsystem.LiuliTheme

/**
 * 琉璃 L0 试验台（仅 debug 包·不接任何正式屏）：可滚长列表 + 三片顶栏 + 三片输入栏 + 底栏胶囊，
 * 用来证明实时背景模糊能跑、并量帧率。启动：
 * `adb shell am start -n com.situ.aichat/.ui.liuli.glass.LiuliGlassLabActivity --ez blur true --ez dark false --ez tinted false`
 */
class LiuliGlassLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val blur = intent.getBooleanExtra("blur", true)
        val dark = intent.getBooleanExtra("dark", false)
        val tier = if (intent.getBooleanExtra("tinted", false)) GlassTier.TINTED else GlassTier.CLEAR
        setContent { LiuliGlassLab(blur = blur, dark = dark, tier = tier) }
    }
}

private val Pill: Shape = RoundedCornerShape(50)

@Composable
internal fun LiuliGlassLab(blur: Boolean, dark: Boolean, tier: GlassTier) {
    val base = if (dark) Color(0xFF0B0D12) else Color(0xFFF2F3F7)
    val ink = if (dark) Color(0xFFF2F4F8) else Color(0xFF111318)
    val mesh = if (dark) listOf(Color(0xFF16233A), Color(0xFF221C3A), Color(0xFF132B2B), Color(0xFF2A2233))
    else listOf(Color(0xFFD3E2F6), Color(0xFFE5DCF4), Color(0xFFD6ECE4), Color(0xFFF0E7D8))
    val listState = rememberLazyListState()
    val backdrop = rememberBackdropState()

    BackdropHost(
        state = backdrop,
        modifier = Modifier.fillMaxSize().background(base),
        content = {
            // 四点柔渐变底（试验用静态版）。
            Box(Modifier.matchParentSize().background(Brush.linearGradient(mesh)))
            LaunchedEffect(listState) {
                // 兜底失效：滚动偏移变化即通知玻璃片重画（tick 之外的第二道保险·试验期观察是否必要）。
                snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                    .collect { backdrop.invalidate() }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 132.dp, bottom = 110.dp, start = 12.dp, end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(120) { i ->
                    val mine = i % 3 == 0
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                        Box(
                            Modifier
                                .width(if (i % 4 == 0) 220.dp else 160.dp)
                                .height(if (i % 5 == 0) 96.dp else 44.dp)
                                .background(
                                    if (mine) Brush.linearGradient(listOf(Color(0xFF2570E8), Color(0xFF1557CC)))
                                    else Brush.linearGradient(listOf(if (dark) Color(0xFF1C2028) else Color.White, if (dark) Color(0xFF1C2028) else Color.White)),
                                    RoundedCornerShape(18.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("消息 $i", color = if (mine) Color.White else ink, fontSize = 15.sp)
                        }
                    }
                }
            }
        },
        overlay = {
            // 顶栏三片
            Row(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(40.dp).liuliGlass(CircleShape, dark, tier, blur))
                Box(Modifier.weight(1f).height(44.dp).liuliGlass(Pill, dark, tier, blur), contentAlignment = Alignment.CenterStart) {
                    Text("  小满 · 此刻在阳台浇水", color = ink, fontSize = 15.sp)
                }
                Box(Modifier.size(40.dp).liuliGlass(CircleShape, dark, tier, blur))
            }
            // 输入栏三片
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 10.dp, end = 10.dp, bottom = 12.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(44.dp).liuliGlass(CircleShape, dark, tier, blur))
                Box(Modifier.weight(1f).height(44.dp).liuliGlass(Pill, dark, tier, blur), contentAlignment = Alignment.CenterStart) {
                    Text("  说点什么…", color = ink.copy(alpha = 0.6f), fontSize = 15.sp)
                }
                Box(Modifier.size(44.dp).liuliGlass(CircleShape, dark, tier, blur))
            }
            Spacer(Modifier.height(0.dp))
            Column(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 60.dp, end = 12.dp)) {
                Text(
                    "blur=$blur dark=$dark tier=$tier api31+=$realtimeBlurSupported tier(Local)=${LiuliTheme.glassTier}",
                    color = ink, fontSize = 10.sp,
                )
            }
        },
    )
}

