import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Properties
import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // alias(libs.plugins.baselineprofile)  // 暂停：1.4.1 不兼容 AGP 9.2.1·1.5.0 仅 alpha（见 settings.gradle.kts）
}

// 正式 release 签名：本机存在 keystore.properties(gitignored) 时启用真签名，
// 否则回退 AGP 默认 debug 签名——保证其他 session 编译 / 别人 clone 不受影响。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}

// 端侧 ONNX Runtime 坐标——单点声明，给 implementation 依赖与 16 KB 对齐守卫（verify16kbNativeAlignment）共用，
// 版本一处改、守卫自动跟随检查那一份的 .so（见文件末尾的守卫与 §helpers）。
val onnxRuntimeCoordinate = "com.microsoft.onnxruntime:onnxruntime-android:1.24.3"

// 16 KB 守卫专用的「只解析、不传递」配置：单独把 ONNX Runtime 的 AAR 拉到 Gradle 缓存里，让守卫能在不经 AGP
// 原生库变换（会改变文件落点、难稳定定位）的前提下，直接读 AAR 内 jni/<abi>/*.so 的 ELF 头核对对齐。
// Gradle 9.6 起 configurations.creating 委托 API 弃用 → 用 configurations.create(name){}（行为等价·急切创建）。
val nativeAlignmentCheck: Configuration = configurations.create("nativeAlignmentCheck") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

// 审计 P1：稳定性声明文件——数据实体跨进 UI 的类按 equals 参与 Compose 跳过判定（文件内有契约注释）。
composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose_stability.conf"))

    // 性能专项·尺 1「界面重画量尺」（图纸 2026-07-30-性能采集与量尺 chunk 1）：Compose 编译器把每个
    // @Composable 的 skippable / restartable 判定与参数稳定性写成报告，供分析「哪些屏每次状态变化都整屏重画」。
    // 纯编译期产物、与用户手机无关、不进 APK；默认不生成（多写文件会拖慢日常构建），只在显式传参时开：
    //   ./gradlew :app:compileDebugKotlin -PcomposeMetrics=true --rerun-tasks
    // ⚠️ `--rerun-tasks` 不能省：Compose 编译器插件的 metrics/reports 目标目录**不是** compileDebugKotlin 的
    // 已跟踪输入，只加 -P 参数时该任务判 UP-TO-DATE 直接跳过、一个文件都不生成（实测 2026-07-30）。
    // 产物落 app/build/compose_metrics/（app-composables.txt / app-classes.txt / debug/app-module.json）。
    if (providers.gradleProperty("composeMetrics").orNull == "true") {
        metricsDestination.set(layout.buildDirectory.dir("compose_metrics"))
        reportsDestination.set(layout.buildDirectory.dir("compose_metrics"))
    }
}

android {
    namespace = "com.situ.aichat"
    // compileSdk 37（Android 17·用户拍板）：用上 core 1.19 / lifecycle 2.11 等需 compileSdk 37 的最新稳定库。
    // targetSdk 仍 36 → 运行时行为不变（compileSdk 只决定可见 API，targetSdk 才决定运行规则）。
    compileSdk = 37

    defaultConfig {
        applicationId = "com.situ.aichat"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        ndk {
            // 只保留实际目标：arm64-v8a(国行小米14 等现代设备) + x86_64(Android Studio 模拟器)。
            // 砍掉 armeabi-v7a / x86 的 ONNX 原生库，APK 减小约 33MB。
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        // keystore.properties 存在时创建正式 release 签名（实体 .jks 在仓库外 ~/keystores/，绝不进库）。
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = File(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // 正式签名：本机有 keystore.properties → 正式 release 证书；否则回退 debug 证书。
            // （GitHub 侧载分发；从 debug 换正式签名后，旧 debug 包需先卸载再装——签名不一致。）
            signingConfig = if (keystorePropertiesFile.exists())
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        // 关于页读取 BuildConfig.VERSION_NAME 显示版本（P12.1c；避免 PackageInfo 重载在 API 33+ 的弃用警告）。
        buildConfig = true
    }
    testOptions {
        unitTests {
            // Robolectric 需要真实 Android 资源/框架（农历 android.icu.ChineseCalendar 单测用）。
            isIncludeAndroidResources = true
            // 日志覆盖治理：给逻辑层广泛补 android.util.Log 后，非 Robolectric 的纯 MockK 行为测一旦触达被测方法里的
            // Log.* 会抛「Method … not mocked」。返回默认值（Log.* → 0）让这些行为测试照常跑，无需为「顺带打了行日志」
            // 给每个测试套 Robolectric。Robolectric 测试有真实 shadow，不受此项影响（农历等仍走真框架）。
            isReturnDefaultValues = true
            // 单元测试 worker 堆上限：Gradle 默认 512m，全量（900+ 测试文件·Story* Robolectric 段）已吃紧——
            // 2026-09-05 实测：512m 在 Story* 段 OutOfMemoryError → Robolectric 主线程死、Test worker 永远 park
            // 在 Sandbox.runOnMainThread（进程 0% CPU、不报 BUILD FAILED、看似「卡住」）；提到 3g 后 921 类 / 8334 例
            // 2m40s 全绿。这是「最多能用到」的上限不是常驻占用；单轮全量 = worker 3g + daemon（gradle.properties 6g）。
            // ⚠️ 它不替代「全量单测全机同一时刻只准一个会话跑」（docs/playbook/PITFALLS.md §1g）——多会话并发只会抢得更快。
            all { it.maxHeapSize = "3g" }
        }
    }

    androidResources {
        // 端侧向量记忆模型(M05)。.onnx 已是紧凑的 int8，跳过 AAPT 二次压缩，
        // 让运行时能直接整块读入并交给 ONNX Runtime。
        noCompress += "onnx"
        // i18n 标准结构（方案②，见 FABLE5_I18N_PLAN.md）：generateLocaleConfig 自动生成 <locale-config>
        // （取代手写 res/xml/locales_config.xml），供「系统 ▸ 应用语言」列出 English + 简体中文；默认目录语言
        // 在 res/resources.properties 的 unqualifiedResLocale 声明（=en）。中文放显式 res/values-zh-rCN/、英文为
        // 默认 res/values/——zh-CN 请求精确命中 zh-rCN（全 API 都对，不依赖只有 API35+ 才认的 android:defaultLocale）。
        // 开启后须移除 manifest 手写的 android:localeConfig 以免冲突。
        generateLocaleConfig = true
    }

    // 把导出的 Room schema 快照作为 androidTest 资产，供 MigrationTest 的 MigrationTestHelper 按版本读取校验（P12.2）。
    // Gradle/AGP 9：assets.srcDir(Any) 弃用 → assets.directories。⚠️新 DSL 下显式列目录会「覆盖」约定默认，
    // 故必须同时显式补回 androidTest 默认 assets 目录（含 STT 设备测的 stt_test/ WAV）+ 导出的 Room schema 目录。
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/src/androidTest/assets")
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")
}

kotlin {
    // Kotlin 2.2 起 android.kotlinOptions{} 弃用 → 顶层 compilerOptions{}（jvmTarget 仍 17·见 JVM 决策）。
    // KT-73255：注解默认 use-site target 从 param 迁向 param+property；显式 param-property 消除迁移警告，
    // 并锁定前向兼容落点（Hilt/Room/serialization 注解位置不变·全量单测验证）。
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

ksp {
    // Export Room schemas (enables proper migrations + schema diffing later).
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.exifinterface)
    // P1-3 Baseline Profile：旁加载分发无 Play 云 profile，profileinstaller 是包内 baseline.prof → ART 的唯一安装通道。
    implementation(libs.androidx.profileinstaller)
    // 暂停（M3·依赖升级）：baselineprofile 1.4.1 不兼容 AGP 9.2.1、1.5.0 仅 alpha → 按只用稳定版拍板暂停。
    // 待稳定版 1.5 后恢复：取消本行注释 + settings.gradle.kts 的 include(":baselineprofile") + 顶部 baselineprofile 插件。
    // "baselineProfile"(project(":baselineprofile"))
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process) // P12.6 D2：ProcessLifecycleOwner 进程级前后台
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // 后台执行基建(P5.0)。WorkManager 在国行无 GMS 设备上走 JobScheduler/AlarmManager，
    // 不依赖 Google Play 服务；hilt-work 让 @HiltWorker 能注入现有仓库/LLM 客户端。
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // 桌面小组件(P11.3 · M19)。Glance = Jetpack Compose 风格的 App Widget(底层 RemoteViews)，
    // 纯 AndroidX、走系统 AppWidget 框架、不依赖 GMS，国行可用(已在 china-no-gms 预批)。
    // glance-material3 提供 GlanceTheme(动态取色/深浅)，与 App 主题一致。
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // 逐条消息 TTS 回放 + 试听播放器(P10.1c)。media3 ExoPlayer 播本地合成音频(系统 wav / 远程 mp3)，
    // 纯本地、无 GMS 依赖，国行可用(用户拍板，铁律#4)。media3-common 提供 MediaItem/Player API。
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.common)

    // 扫码导入/导出 API 配置(13.10b · C7，安卓便利加分)。ZXing core = 纯 Java 二维码编解码(生成 + 从相册图/相机帧
    // 解码)，无任何 Android/Google 依赖；CameraX = 纯 AndroidX 相机栈(预览 + 帧分析做实时扫码)，走系统 Camera2、
    // 不依赖 GMS。均离线、无 GMS、国行可用(用户 2026-06-09 拍板加这两个库，铁律#4)。ML Kit 条码需 GMS，故不用。
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.okhttp.logging)

    // 端侧 ONNX Runtime：M05 向量记忆(bge) 与 P10.1d 语音识别(sherpa) 共用同一份。完整算子集、纯本地、零 GMS。
    // 版本对齐 sherpa-onnx v1.13.3 自带的 ORT 1.24.3（依赖升级 2026-06 核对：1.13.3 仍 1.24.3·未变）—— 两者在 APK 内共用同一份 libonnxruntime.so(同 soname)，
    // 故必须用 >= 1.24 的那份：sherpa 的 jni 按 1.24 编译需精确匹配；bge 的 4j_jni 按旧版编译但走 ORT 向后兼容。
    // (用 1.20.0 会让按 1.24 编译的 sherpa GetApi() 取不到而崩溃。) 模型 bge 在 assets/models/，STT 在 assets/models/stt/。
    implementation(onnxRuntimeCoordinate)
    // 16 KB 对齐守卫只解析这一份 ONNX Runtime AAR（不参与打包/不传递依赖）——见文件末尾 verify16kbNativeAlignment。
    add("nativeAlignmentCheck", onnxRuntimeCoordinate)

    // 端侧语音识别(STT, P10.1d)。sherpa-onnx(Next-gen Kaldi + ONNX Runtime)官方 v1.13.3 AAR，
    // 已精简为 arm64-v8a+x86_64 的 libsherpa-onnx-jni.so + classes.jar(见 libs/README.md)；
    // AAR 自带的 libonnxruntime.so 已删除，复用上面 microsoft 1.24.3 那份(同 soname)避免重复打包冲突。
    // Apache-2.0、离线、无 GMS、免 key，国行可用(用户拍板，铁律#4)。模型在 assets/models/stt/。
    implementation(files("libs/sherpa-onnx-1.13.3.aar"))

    debugImplementation(libs.androidx.compose.ui.tooling)
    // 反转列表行为测试（契约 FABLE5_CHAT_REVERSE_LIST_PROPOSAL §6 T2/T3）：Robolectric 上跑 Compose UI 测试。
    // test-only（AndroidX 原生工具链·不进 release APK）；manifest 件仅给 debug 清单注册 ComponentActivity 供
    // createComposeRule 启宿主（发布构建不含）。
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // 深链导航落地 T2（Robolectric + TestNavHostController·13.10a 分享被吞修复）。test-only 不进 APK。
    testImplementation(libs.androidx.navigation.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    // Compose UI 仪器测试（T3）：Robolectric 字形宽失真测不出文字挤压类布局 bug，真字体测量归这里。
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Room 迁移测试（P12.2）：MigrationTestHelper 按导出快照逐版本校验迁移链。设备/模拟器上跑（批末期）。
    androidTestImplementation(libs.androidx.room.testing)
}

// ──────────────────────────────────────────────────────────────────────────────────────────────
// 16 KB 内存页对齐守卫（Android 15/16 兼容·便宜保险）
//
// 为什么要它：Android 15/16 起，64 位设备可能以 16 KB 内存页运行。任何打进 APK 的原生 .so 若 ELF 的 LOAD 段
// 对齐 < 16 KB（0x4000），在 16 KB 页设备上加载器会 dlopen 失败 / SIGSEGV——硬崩，任何 Build.VERSION 判断都拦不住，
// 且「能不能启动」的冒烟测试在 Android 16 上因「兼容模式」也测不出来。本应用走 GitHub 侧载、无应用商店审核兜底，
// 故把检查左移到构建期：每次构建解析将要随包发布的 .so（ONNX Runtime AAR + app/libs 的 sherpa AAR + src/main/jniLibs），
// 任一发布 ABI 的 .so 未 16 KB 对齐即让构建失败。当前全部合规（onnxruntime 1.24.3 / sherpa 1.13.3 实测 0x4000），
// 本守卫纯防「将来某次依赖升降级悄悄换进 4 KB 的 .so」回归。
//
// 纯 JVM 实现（自解析 ELF 程序头），不依赖本机装没装 objdump/readelf —— 长期稳定、跨机一致。
// 若将来新增第三方原生依赖：把它的坐标也 add 到上面的 nativeAlignmentCheck 配置即可一并纳入守卫。
//
// 实现注意（Gradle Kotlin DSL 坑）：脚本顶层若先声明 `fun` 再写顶层语句，后者不会执行；故所有 ELF 解析辅助函数
// 一律作为 doLast 内的局部函数，注册/接线只用顶层语句（按名 dependsOn），保证守卫真正注册并运行。
tasks.register("verify16kbNativeAlignment") {
    group = "verification"
    description = "Fails the build if any shipped native .so is not 16 KB ELF-aligned (Android 15/16 page-size compat)."
    // 配置期固定数据源（Configuration 即 FileCollection），doLast 内读取——避开 AGP 原生库变换、跨版本稳定。
    val depArchives = nativeAlignmentCheck
    val localAars = layout.projectDirectory.dir("libs").asFile
    val jniLibs = layout.projectDirectory.dir("src/main/jniLibs").asFile
    // 随包发布的 ABI（与 defaultConfig.ndk.abiFilters 对齐）；只检查这两类，AAR 内其余 ABI 不打包、不判定。
    val abis = setOf("arm64-v8a", "x86_64")
    doLast {
        // 读输入流首段（ELF 程序头总在文件开头，读首 1 MB 足矣，不必整文件入内存）。
        fun readPrefix(stream: InputStream, limit: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (total < limit) {
                val n = stream.read(buf, 0, minOf(buf.size, limit - total))
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
            }
            return out.toByteArray()
        }
        // 解析 ELF64（小端·arm64-v8a / x86_64 均是）程序头，返回所有 PT_LOAD 段中最大 p_align。
        // 16 KB 兼容要求该最大值 ≥ 0x4000；返回 -1 = 非 ELF64/小端或头超出已读前缀（守卫据此跳过+告警，不误杀）。
        fun maxLoadAlignment(b: ByteArray): Long {
            if (b.size < 64) return -1
            if (b[0] != 0x7F.toByte() || b[1] != 'E'.code.toByte() || b[2] != 'L'.code.toByte() || b[3] != 'F'.code.toByte()) return -1
            if (b[4].toInt() != 2) return -1 // EI_CLASS != ELFCLASS64
            if (b[5].toInt() != 1) return -1 // EI_DATA != little-endian
            fun u16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
            fun u32(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
            fun u64(o: Int): Long {
                var v = 0L
                for (i in 0..7) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i))
                return v
            }
            val ePhoff = u64(0x20)       // e_phoff
            val ePhentsize = u16(0x36)   // e_phentsize（ELF64=56）
            val ePhnum = u16(0x38)       // e_phnum
            if (ePhoff <= 0 || ePhnum <= 0 || ePhentsize < 56) return -1
            var maxAlign = 0L
            for (i in 0 until ePhnum) {
                val off = (ePhoff + i.toLong() * ePhentsize).toInt()
                if (off + 56 > b.size) return -1 // 头超出已读前缀 → 无法判定
                if (u32(off) == 1) { // p_type == PT_LOAD
                    val pAlign = u64(off + 0x30) // p_align 在 ELF64 程序头偏移 0x30
                    if (pAlign > maxAlign) maxAlign = pAlign
                }
            }
            return maxAlign
        }

        val offenders = mutableListOf<String>()
        var checked = 0
        fun judge(source: String, abi: String, name: String, prefix: ByteArray) {
            val align = maxLoadAlignment(prefix)
            when {
                align < 0 -> logger.warn("16kb-guard: 跳过无法解析的 $source :: $abi/$name（非 ELF64/小端或头超界）")
                align < 16384L -> { checked++; offenders += "$source :: $abi/$name (max LOAD align = 0x${align.toString(16)})" }
                else -> checked++
            }
        }
        // 扫描一个 AAR/JAR（zip）里 jni/<abi>/*.so，仅对发布 ABI 判定。
        fun scanArchive(archive: File, label: String) {
            ZipFile(archive).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory) continue
                    val parts = e.name.split('/')
                    if (parts.size >= 3 && parts[0] == "jni" && parts.last().endsWith(".so")) {
                        val abi = parts[parts.size - 2]
                        if (abi in abis) zip.getInputStream(e).use { judge(label, abi, parts.last(), readPrefix(it, 1 shl 20)) }
                    }
                }
            }
        }
        // 1) ONNX Runtime AAR（经 nativeAlignmentCheck 配置解析到的 .aar/.jar）
        depArchives.files.filter { it.extension == "aar" || it.extension == "jar" }.forEach { scanArchive(it, "dep:${it.name}") }
        // 2) app/libs 下的本地 AAR（sherpa-onnx）
        localAars.listFiles { f -> f.isFile && f.extension == "aar" }?.forEach { scanArchive(it, "libs/${it.name}") }
        // 3) src/main/jniLibs/<abi>/*.so（当前为空，留作将来直放 .so 时的兜底）
        if (jniLibs.isDirectory) {
            jniLibs.listFiles { f -> f.isDirectory && f.name in abis }?.forEach { abiDir ->
                abiDir.listFiles { f -> f.isFile && f.extension == "so" }?.forEach { so ->
                    so.inputStream().use { judge("jniLibs", abiDir.name, so.name, readPrefix(it, 1 shl 20)) }
                }
            }
        }
        if (checked == 0) {
            throw GradleException("16kb-guard：未发现任何可检查的发布 .so —— 守卫接线很可能已失效（ONNX/sherpa 来源变动？），请修复后再发布。")
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("16 KB 页对齐守卫失败 —— 以下原生库未按 16 KB 对齐，将在 16 KB 页设备上加载即崩：")
                    offenders.forEach { appendLine("  • $it") }
                    appendLine("修法：升级/更换该依赖到 16 KB 对齐版本（NDK r28+/AGP 8.5.1+ 默认对齐），或回退引入它的那次改动。")
                },
            )
        }
        logger.lifecycle("16kb-guard: OK — 已核对 $checked 个发布原生 .so，均为 16 KB 对齐。")
    }
}

// 挂到 preBuild：每次构建（debug/release/单测前置）都先跑守卫，回归当场拦下，绝不漏进发布包。按名 dependsOn。
tasks.named("preBuild") { dependsOn("verify16kbNativeAlignment") }

