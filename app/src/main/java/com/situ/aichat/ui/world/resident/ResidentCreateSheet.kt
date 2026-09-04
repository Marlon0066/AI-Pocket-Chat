package com.situ.aichat.ui.world.resident

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.character.AvatarCropScreen
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.util.AvatarStore
import com.situ.aichat.world.cast.CreateResult
import kotlinx.coroutines.launch

// 固定暖夜面色 token（M1 过审观感·恒暗·不随浅色主题变·图纸 §4.1）。同包 ResidentCityPickerSheet 复用。
internal val ResRaised = Color(0xFF1C1916)
internal val ResSunken = Color(0xFF242019)
internal val ResText1 = Color(0xFFEDE8E2)
internal val ResText2 = Color(0xFFB5ACA1)
internal val ResText3 = Color(0xFF7E766C)
internal val ResClay = Color(0xFFBE8A76)
internal val ResGold = Color(0xFFD4B96A)
internal val ResFieldBg = Color(0xD9242019)
internal val ResFieldStroke = Color(0x0FEDE8E2)
internal val ResChipGradStart = Color(0xFFC99A86)
internal val ResChipGradEnd = Color(0xFFBE8A76)
internal val ResChipOnGrad = Color(0xFF2E2925)
internal val ResCustomChipBorder = Color(0x80D4B96A)
private val ResFieldErr = Color(0xFF9A5B3E)
private val ResCityCardStroke = Color(0x40D4B96A)
private val ResCityDot = Color(0xFF1D2738)
private val ResAvatarDashed = Color(0x80BE8A76)
private val ResCameraBadge = Color(0xFF9A5B3E)
private val ResCapBanner = Color(0x33D4B96A)
private val ResCtaStart = Color(0xFF9A5B3E)
private val ResCtaEnd = Color(0xFF8A4E33)
private val ResCtaText = Color(0xFFF5EFEA)
private val ResFieldShape = RoundedCornerShape(16.dp)
private val ResChipShape = RoundedCornerShape(percent = 50)

/** 性格底色预设 12 词（图纸 §4.1-g §9 锁死·内容词非 UI chrome·随中文提交·非硬编码城市清单类禁区）。 */
private val TRAIT_PRESETS = listOf(
    "温吞", "毒舌", "热心", "孤僻", "浪漫", "爽朗", "腼腆", "倔强", "体贴", "跳脱", "稳重", "嘴硬心软",
)

/**
 * 创建居民表单（战役 B·图纸 §4.1·M1 mockup 唯一裁判）：固定暖夜面 ModalBottomSheet 里堆叠头像 / 名字 /
 * 性别年龄 / 职业 / 人设 / 性格底色 / 住址 / 更多设定折叠 / 自由设定 / CTA。所有落值逐字对 §4.1。
 * Ok → 触觉 + Toast + 关 sheet；CapReached → 顶部提示条；InvalidName → 名字框红边 + footer。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ResidentCreateSheet(
    onDismiss: () -> Unit,
    viewModel: ResidentCreateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalAppHaptics.current
    var showCityPicker by remember { mutableStateOf(false) }
    var moreExpanded by remember { mutableStateOf(false) }
    var showCustomTraitInput by remember { mutableStateOf(false) }

    var pendingAvatarCropUri by remember { mutableStateOf<Uri?>(null) }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        // 选完图先进圆形取景裁剪屏（甲 3）；「就这样」才存裁好的成品图，「取消」不改原头像。
        pendingAvatarCropUri = uri
    }
    pendingAvatarCropUri?.let { uri ->
        AvatarCropScreen(
            uri = uri,
            onCancel = { pendingAvatarCropUri = null },
            onConfirm = { cropped ->
                scope.launch { AvatarStore.save(context, cropped)?.let { viewModel.setAvatar(it) } }
                pendingAvatarCropUri = null
            },
        )
    }

    // Ok → 触觉 + Toast + 关（CapReached/InvalidName 走内联提示·不关）。
    LaunchedEffect(state.result) {
        if (state.result is CreateResult.Ok) {
            haptics.light()
            Toast.makeText(
                context,
                context.getString(R.string.world_resident_created_toast, state.name.trim()),
                Toast.LENGTH_SHORT,
            ).show()
            onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = ResRaised) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                stringResource(R.string.world_resident_sheet_title),
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = ResText1,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.world_resident_sheet_sub),
                fontSize = 12.sp, color = ResText2, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 18.dp),
            )

            if (state.result is CreateResult.CapReached) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clip(ResFieldShape)
                        .background(ResCapBanner)
                        .padding(12.dp),
                ) {
                    Text(stringResource(R.string.world_resident_cap_hint), fontSize = 13.sp, color = ResText1)
                }
            }

            // a) 头像。
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(76.dp).clickable { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Box(
                        Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(ResSunken)
                            .drawBehind {
                                drawCircle(
                                    color = ResAvatarDashed,
                                    radius = size.minDimension / 2f - 0.5.dp.toPx(),
                                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))),
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.avatarPath != null) {
                            CharacterAvatar(name = state.name.ifBlank { "?" }, avatarPath = state.avatarPath, size = 76.dp)
                        } else {
                            Text("?", fontSize = 24.sp, color = ResClay)
                        }
                    }
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(24.dp).clip(CircleShape).background(ResCameraBadge),
                        contentAlignment = Alignment.Center,
                    ) { Text("📷", fontSize = 12.sp) }
                }
            }
            Text(
                stringResource(R.string.world_resident_avatar_hint),
                fontSize = 11.sp, color = ResText3, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp),
            )

            // b/c) 名字。
            ResidentLabel(stringResource(R.string.world_resident_name_label))
            ResidentField(
                value = state.name, onValueChange = viewModel::setName,
                placeholder = stringResource(R.string.world_resident_name_ph), singleLine = true, error = state.nameError,
            )
            if (state.nameError) {
                Text(stringResource(R.string.world_resident_name_error), fontSize = 11.sp, color = ResFieldErr, modifier = Modifier.padding(top = 4.dp))
            }

            // d) 性别 + 年龄。
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    ResidentLabel(stringResource(R.string.world_resident_gender_label))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ResidentChip(stringResource(R.string.world_resident_gender_male), selected = state.genderPreset == "male") { viewModel.setGenderPreset("male") }
                        ResidentChip(stringResource(R.string.world_resident_gender_female), selected = state.genderPreset == "female") { viewModel.setGenderPreset("female") }
                        ResidentChip(
                            stringResource(R.string.world_resident_gender_custom),
                            selected = state.genderPreset == "custom",
                            dashed = state.genderPreset != "custom",
                        ) { viewModel.setGenderPreset("custom") }
                    }
                    if (state.genderPreset == "custom") {
                        Box(Modifier.padding(top = 6.dp)) {
                            ResidentField(state.genderCustom, viewModel::setGenderCustom, placeholder = "", singleLine = true)
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    ResidentLabel(stringResource(R.string.world_resident_age_label))
                    ResidentField(state.ageText, viewModel::setAge, placeholder = "", singleLine = true, keyboardType = KeyboardType.Number)
                }
            }

            // e) 职业。
            ResidentLabel(stringResource(R.string.world_resident_occupation_label))
            ResidentField(state.occupation, viewModel::setOccupation, placeholder = stringResource(R.string.world_resident_occupation_ph), singleLine = true)

            // f) 人设简介。
            ResidentLabel(stringResource(R.string.world_resident_brief_label))
            ResidentField(state.personaBrief, viewModel::setPersonaBrief, placeholder = stringResource(R.string.world_resident_brief_ph), singleLine = false, minLines = 3)

            // g) 性格底色。
            ResidentLabel(stringResource(R.string.world_resident_traits_label))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TRAIT_PRESETS.forEach { word ->
                    ResidentChip(word, selected = word in state.traits) { viewModel.toggleTrait(word) }
                }
                // 用户自造词（不在预设）：金边示例·已选态。
                state.traits.filter { it !in TRAIT_PRESETS }.forEach { word ->
                    ResidentChip(word, selected = true, custom = true) { viewModel.toggleTrait(word) }
                }
                ResidentChip(stringResource(R.string.world_resident_trait_custom), selected = false, dashed = true) {
                    showCustomTraitInput = !showCustomTraitInput
                }
            }
            if (showCustomTraitInput) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        ResidentField(state.customTraitDraft, viewModel::setCustomTraitDraft, placeholder = "", singleLine = true)
                    }
                    ResidentChip("✓", selected = true) {
                        viewModel.commitCustomTrait()
                        showCustomTraitInput = false
                    }
                }
            }
            Text(stringResource(R.string.world_resident_traits_max), fontSize = 11.sp, color = ResText3, modifier = Modifier.padding(top = 6.dp))

            // h) 住在哪座城。
            ResidentLabel(stringResource(R.string.world_resident_city_label))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(ResFieldShape)
                    .background(ResFieldBg)
                    .border(1.dp, ResCityCardStroke, ResFieldShape)
                    .clickable { showCityPicker = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(ResCityDot), contentAlignment = Alignment.Center) {
                    Text("🏔", fontSize = 16.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text(state.cityName, fontSize = 15.sp, color = ResText1, fontWeight = FontWeight.Medium)
                    if (state.regionName.isNotEmpty()) Text(state.regionName, fontSize = 11.sp, color = ResText3)
                }
                Text("›", fontSize = 18.sp, color = ResText3)
            }

            // i) 更多设定（折叠）。
            AppButton(style = AppButtonStyle.Text, onClick = { moreExpanded = !moreExpanded }) {
                Text(
                    stringResource(R.string.world_resident_more_label) + if (moreExpanded) " ▴" else " ▾",
                    fontSize = 13.sp, color = ResText2,
                )
            }
            if (moreExpanded) {
                ResidentLabel(stringResource(R.string.world_resident_relation_label))
                ResidentField(state.initialRelationText, viewModel::setInitialRelation, placeholder = stringResource(R.string.world_resident_relation_ph), singleLine = true)
                ResidentLabel(stringResource(R.string.world_resident_bias_label))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ResidentChip(stringResource(R.string.world_resident_bias_balanced), selected = state.fuelBias == "balanced") { viewModel.setFuelBias("balanced") }
                    ResidentChip(stringResource(R.string.world_resident_bias_narrative), selected = state.fuelBias == "narrative") { viewModel.setFuelBias("narrative") }
                    ResidentChip(stringResource(R.string.world_resident_bias_gift), selected = state.fuelBias == "gift") { viewModel.setFuelBias("gift") }
                }
            }

            // j) 自由补充设定。
            ResidentLabel(stringResource(R.string.world_resident_lore_label))
            ResidentField(state.freeformLore, viewModel::setFreeformLore, placeholder = stringResource(R.string.world_resident_lore_ph), singleLine = false, minLines = 2)

            // k) CTA。
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .heightIn(min = 52.dp)
                    .clip(ResChipShape)
                    .background(Brush.linearGradient(listOf(ResCtaStart, ResCtaEnd)))
                    .clickable(enabled = !state.submitting) { viewModel.submit() }
                    .semantics { role = Role.Button }
                    .padding(vertical = 15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(if (state.submitting) R.string.world_resident_cta_busy else R.string.world_resident_cta),
                    fontSize = 16.sp, fontWeight = FontWeight.Medium, color = ResCtaText,
                )
            }
            Text(
                stringResource(R.string.world_resident_cta_hint),
                fontSize = 11.sp, color = ResText3, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp),
            )
        }
    }

    if (showCityPicker) {
        ResidentCityPickerSheet(
            state = state,
            onSelectRegion = viewModel::selectRegion,
            onSelectCity = { id, name ->
                viewModel.selectCity(id, name)
                showCityPicker = false
            },
            onDismiss = { showCityPicker = false },
        )
    }
}

/** 字段标签（12sp·陶土色·Medium·上 14 下 6·§4.1-b）。 */
@Composable
internal fun ResidentLabel(text: String) {
    Text(text, fontSize = 12.sp, color = ResClay, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}

/** 暗面软填充字段（自绘·非 M3 TextField·§4.1-b）：底 sunken·1dp 描边·圆角 16·15sp 文字·placeholder 空=不显。 */
@Composable
internal fun ResidentField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    error: Boolean = false,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = TextStyle(color = ResText1, fontSize = 15.sp),
        cursorBrush = SolidColor(ResClay),
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier
            .fillMaxWidth()
            .clip(ResFieldShape)
            .background(ResFieldBg)
            .border(1.dp, if (error) ResFieldErr else ResFieldStroke, ResFieldShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = ResText3, fontSize = 15.sp)
                inner()
            }
        },
    )
}

/**
 * 性别 / 性格 / 眼缘 chip（§4.1-d/g/i）：选中 = 135° 陶土渐变 + 深字 Medium；未选 = sunken 底 + 次文字；
 * [dashed] = 虚线描边（+ 自定义 / 未选自定义位·陶土字）；[custom] = 金边示例（用户自造已选词）。
 */
@Composable
internal fun ResidentChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    custom: Boolean = false,
    dashed: Boolean = false,
    onClick: () -> Unit,
) {
    val base = modifier
        .clip(ResChipShape)
        .then(if (selected) Modifier.background(Brush.linearGradient(listOf(ResChipGradStart, ResChipGradEnd)), ResChipShape) else Modifier.background(ResFieldBg, ResChipShape))
    val bordered = when {
        dashed -> base.drawBehind {
            drawRoundRect(
                color = ResClay,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
                style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))),
            )
        }
        custom -> base.border(1.dp, ResCustomChipBorder, ResChipShape)
        else -> base
    }
    Box(
        bordered
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 32.dp)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = if (selected) ResChipOnGrad else if (dashed) ResClay else ResText2,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}
