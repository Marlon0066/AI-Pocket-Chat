package com.situ.aichat.ui.world.web

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.activity.ComponentActivity
import com.situ.aichat.ui.world.continent.ContinentSceneData
import com.situ.aichat.ui.world.continent.ContinentStrings
import com.situ.aichat.world.atlas.WorldAtlas
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2 行为锁（复核 R1 🔴-1 返修）：**回前台补推的必须是「回前台那一刻」的旗标与 presence，不是首次组合冻结的那一份。**
 *
 * 为什么非测不可：`DisposableEffect(lifecycleOwner, webView)` 的两个键在整屏生命周期内恒定 → 观察者闭包只建一次，
 * 里面按值捕获的普通参数（`reduceMotion` / `staticMode` / `interactive` / `presence`）会永停首次组合值。
 * 而大陆场景**永远**在转场中挂载（`interactive` 首帧恒 false），于是每次 `ON_RESUME` 都把「不可交互」推回页面，
 * 手势与站位点击全部熔断（PITFALLS 1h 的第二个入口：生命周期观察者）。
 *
 * **断言选报文内容而非「调用过没有」**——错误实现同样会调用，只是内容陈旧；「调用过」是自证式断言，没有区分力。
 * 两条用例都是回归锁：把 host 活引用退回按值捕获，二者必红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ContinentWebResumeFreshnessTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** 页面正文由原生 sheet 渲染，报文不外发——此处只为构造 [ContinentSceneData]，内容与断言无关。 */
    private val strings = ContinentStrings(
        cityBodyTemplate = "%1\$s·%2\$s·%3\$s",
        tierSmall = "小", tierTown = "镇", tierCity = "城",
        wonderBodyTemplate = "%1\$s", curatedBodies = emptyMap(),
    )
    private val sceneData = ContinentSceneData.fromAtlas(WorldAtlas.of(7L), "yunze", strings)

    /** 捕获发往页面的全部调用串（= 生产路径上 host 的唯一出口）。 */
    private val sent = mutableListOf<String>()

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /** `setFlags(JSON.parse("…"))` → 里层报文对象（两层：先剥 JS 字符串字面量，再解析 JSON）。 */
    private fun payloadOf(call: String): JsonObject {
        val literal = call.substringAfter("JSON.parse(").substringBeforeLast("))")
        val inner = Json.parseToJsonElement(literal).jsonPrimitive.content
        return Json.parseToJsonElement(inner).jsonObject
    }

    private fun lastCall(prefix: String): String? = sent.lastOrNull { it.startsWith(prefix) }

    private fun findWebView(v: View): WebView? = when {
        v is WebView -> v
        v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findWebView(v.getChildAt(it)) }
        else -> null
    }

    /**
     * 起一屏网页大陆：首次组合给「转场中」的冻结值（interactive=false·presence 空），随后翻成新值。
     * `onPageFinished` 手动触发（Robolectric 的 WebView 不会自己回调），令装载序跑完、`inited` 置位。
     */
    private fun startScene(interactiveState: () -> Boolean, presenceState: () -> WorldWebPresence): TestOwner {
        val owner = TestOwner()
        owner.registry.currentState = Lifecycle.State.RESUMED
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                Box(Modifier.fillMaxSize()) {
                    val p = presenceState()
                    ContinentWebSceneView(
                        regionId = "yunze",
                        reduceMotion = false,
                        staticMode = false,
                        interactive = interactiveState(),
                        continentOf = { sceneData },
                        onFirstFrame = {},
                        onReturnToPlanet = {},
                        onEnterTown = {},
                        onWebFailed = {},
                        onViewReady = {},
                        userPresenceCityId = p.cityId,
                        userTraveling = p.traveling,
                        userHomeCityId = p.homeCityId,
                        jsSink = { sent += it },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            val wv = findWebView(compose.activity.window.decorView)
            assertNotNull("WebView 应已挂载", wv)
            wv!!.webViewClient.onPageFinished(wv, "file:///android_asset/world_web/continent.html")
        }
        compose.waitForIdle()
        assertTrue("装载序应已发出（init/restorePose/setPresence/setFlags）", sent.any { it.startsWith("init(") })
        return owner
    }

    @Test
    fun onResume_pushesCurrentInteractive_notFirstCompositionValue() {
        // 首次组合 = 转场中（大陆场景的必然状态）：interactive 冻结值为 false。
        var interactive by mutableStateOf(false)
        val owner = startScene({ interactive }, { WorldWebPresence(null, false, "city_yunye") })

        assertEquals(
            "首帧应推 interactive=false（= 转场中·这一步本来就是对的）",
            false, payloadOf(lastCall("setFlags")!!).getValue("interactive").jsonPrimitive.boolean,
        )

        // 揭幕：phase → None，重组推 true。
        interactive = true
        compose.waitForIdle()
        assertEquals(
            "揭幕后应推 interactive=true",
            true, payloadOf(lastCall("setFlags")!!).getValue("interactive").jsonPrimitive.boolean,
        )

        // 回桌面再回来：ON_RESUME 补推。**必须是当下的 true，不是首次组合冻结的 false。**
        sent.clear()
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        compose.waitForIdle()

        val resumed = lastCall("setFlags")
        assertNotNull("ON_RESUME 必须补推一次三旗（E7）", resumed)
        assertEquals(
            "回前台补推的 interactive 必须是当下值 true —— 推回冻结的 false 会让整屏手势与站位点击熔断",
            true, payloadOf(resumed!!).getValue("interactive").jsonPrimitive.boolean,
        )
    }

    @Test
    fun onResume_pushesCurrentPresence_notFirstCompositionValue() {
        // 进大陆那一瞬 presence 流还没到 → 冻结值是「没有落点」。
        var presence by mutableStateOf(WorldWebPresence(null, false, "city_yunye"))
        val owner = startScene({ true }, { presence })

        // 流到达：徽记出现。
        presence = WorldWebPresence("city_yunye", false, "city_yunye")
        compose.waitForIdle()
        assertEquals(
            "流到达后应推真 cityId",
            "city_yunye",
            payloadOf(lastCall("setPresence")!!).getValue("cityId").jsonPrimitive.content,
        )

        // 回桌面再回来：**必须仍是 city_yunye**，推回冻结的 null 会让徽记永久消失（E6 反向失效）。
        sent.clear()
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        compose.waitForIdle()

        val resumed = lastCall("setPresence")
        assertNotNull("ON_RESUME 必须补推一次 presence（E7）", resumed)
        assertEquals(
            "回前台补推的 cityId 必须是当下值 —— 推回冻结的 null 会让「TA 在这里」徽记永久消失",
            "city_yunye",
            payloadOf(resumed!!).getValue("cityId").jsonPrimitive.content,
        )
    }
}
