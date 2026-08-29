package com.situ.aichat.ui.world.web

import android.webkit.WebSettings
import android.webkit.WebView
import kotlinx.serialization.json.JsonPrimitive

/**
 * 网页世界三视图（小镇 / 大陆 / 星球）共用的 WebView 小工具：前三件（[allowFileToFileAccess] / [callJs] /
 * [jsArg]）由图纸「网页世界二期」§0.1 表从 [TownWebSceneView] **只搬不改**抽出（行为零变）；
 * 末两件是二期大陆 / 星球两视图的共用出口，令「调 worldWeb」与「推三旗」各只写一处。
 */

/**
 * 允许 `file://` 页读同目录子资源。three.js 的 `TextureLoader` 给 `<img>` 恒带 `crossOrigin="anonymous"`
 * （three.min.js `crossOrigin="anonymous"` 默认值），不开此项 `tex_*.webp` 会**静默**走程序化兜底——画面降级
 * 且不报错。官方替代 `androidx.webkit.WebViewAssetLoader` 被一期图纸 §9 明令禁止（不新增依赖），故此处沿用平台开关。
 * 风险面为零：页面是自家 asset、`blockNetworkLoads` 已封网络、DOM 文本一律走 `textContent` 无注入口。
 */
@Suppress("DEPRECATION")
internal fun WebSettings.allowFileToFileAccess() { allowFileAccessFromFileURLs = true }

/**
 * 调 `window.<ns>.<call>`（脚本未就绪时静默 no-op）。**主线程调**。
 * [ns] = 页面挂的桥命名空间：一期小镇 `townWeb`、二期大陆/星球 `worldWeb`（前端契约逐字锁死）。
 */
internal fun WebView.callJs(ns: String, call: String) {
    evaluateJavascript("window.$ns && window.$ns.$call;", null)
}

/** JSON 文本 → JS 字符串字面量（转义交给序列化器·报文里的引号/反斜杠不会撑破 evaluateJavascript）。 */
internal fun jsArg(json: String): String = JsonPrimitive(json).toString()

/** 调 `window.worldWeb.<call>`（二期大陆 / 星球两页同一命名空间·脚本未就绪时静默 no-op）。**主线程调**。 */
internal fun WebView.callWorldWeb(call: String) = callJs(WorldWebBridge.JS_NAMESPACE, call)

/**
 * 三旗调用串（reduceMotion / staticMode / interactive）。报文与一期同形，复用 [TownWebData.flagsJson] 单源。
 * 只**造串**不发送——发送统一走各宿主 host 的单一出口（R1 🔴-1 返修：令生命周期观察者与装载序共用同一条通路）。
 */
internal fun setFlagsCall(reduceMotion: Boolean, staticMode: Boolean, interactive: Boolean): String =
    "setFlags(JSON.parse(${jsArg(TownWebData.flagsJson(reduceMotion, staticMode, interactive))}))"
