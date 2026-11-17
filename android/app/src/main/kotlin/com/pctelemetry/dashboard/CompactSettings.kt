package com.pctelemetry.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pctelemetry.core.ConnectionPhase

@Composable
fun SettingsPanel(model: DashboardViewModel, value: UiSettings, onDismiss: () -> Unit, onPair: () -> Unit,
    onMicrophoneChange: (Boolean) -> Unit, onMicrophoneSensitivityPreview: (Int?) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val profiles by model.profiles.collectAsStateWithLifecycle()
    val selected by model.profile.collectAsStateWithLifecycle()
    val wanted by model.connectionWanted.collectAsStateWithLifecycle()
    val accent = if (value.theme == DashboardTheme.SPORT) Red else Cyan
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sensitivity by remember(value.microphoneSensitivity) { mutableFloatStateOf(value.microphoneSensitivity.toFloat()) }
    val preview by rememberUpdatedState(onMicrophoneSensitivityPreview)
    DisposableEffect(Unit) { onDispose { preview(null) } }
    LaunchedEffect(tab) { if (tab != 2) preview(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)), contentAlignment = Alignment.Center) {
            Box(Modifier.matchParentSize().clickable(onClick = onDismiss))
            Surface(color = Color(0xFF0B171F), contentColor = White, shape = CutCornerShape(14.dp),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 1080.dp).fillMaxHeight(0.93f)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .testTag("settings-panel")) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.settings), fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (state.demo) "DEMO" else selected?.let { "PC · " + (it.displayName ?: java.net.URI(it.endpoint).host) }
                            ?: stringResource(R.string.no_paired_devices), Modifier.weight(1f).padding(horizontal = 20.dp),
                            color = Silver, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = onDismiss, modifier = Modifier.testTag("settings-close")) {
                            Text(stringResource(R.string.close), color = accent)
                        }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        listOf(R.string.settings_devices_tab, R.string.settings_display_tab, R.string.settings_audio_tab)
                            .forEachIndexed { index, label ->
                                val active = tab == index
                                Box(Modifier.weight(1f).height(38.dp)
                                    .testTag("settings-tab-${listOf("devices", "display", "audio")[index]}")
                                    .background(if (active) accent.copy(alpha = 0.12f) else Color.Transparent)
                                    .selectable(active, role = Role.Tab, onClick = { tab = index })
                                    .drawBehind {
                                        drawLine(if (active) accent else Silver.copy(alpha = 0.2f),
                                            Offset(0f, size.height), Offset(size.width, size.height), if (active) 2.dp.toPx() else 1.dp.toPx())
                                    }, contentAlignment = Alignment.Center) {
                                    Text(stringResource(label), color = if (active) White else Silver, fontSize = 14.sp)
                                }
                            }
                    }
                    Row(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        when (tab) {
                            0 -> {
                                Column(Modifier.weight(1.2f).fillMaxHeight().verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    SettingTitle(stringResource(R.string.paired_devices))
                                    if (profiles.isEmpty()) Text(stringResource(R.string.no_paired_devices), color = Silver, fontSize = 13.sp)
                                    profiles.forEach { device ->
                                        val active = wanted && !state.demo && selected?.deviceId == device.deviceId
                                        val connected = active && state.phase == ConnectionPhase.CONNECTED
                                        val toggle = if (!active) ToggleableState.Off else if (connected) ToggleableState.On else ToggleableState.Indeterminate
                                        Row(Modifier.fillMaxWidth().testTag("device-${device.deviceId}")
                                            .triStateToggleable(toggle, role = Role.Checkbox,
                                                onClick = { if (active) model.disconnect() else model.reconnect(device.deviceId) })
                                            .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            TriStateCheckbox(toggle, onClick = null, colors = CheckboxDefaults.colors(
                                                checkedColor = accent, uncheckedColor = Silver, checkmarkColor = Color.Black))
                                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                                Text(device.displayName ?: java.net.URI(device.endpoint).host, fontSize = 14.sp,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(java.net.URI(device.endpoint).let { "${it.host}:${if (it.port == -1) 443 else it.port}" },
                                                    color = Silver, fontSize = 11.sp)
                                                Text(stringResource(if (connected) R.string.device_connected else if (active) {
                                                    when (state.message) {
                                                        "authentication" -> R.string.device_auth_failed
                                                        "certificate" -> R.string.device_certificate_failed
                                                        else -> R.string.device_connecting
                                                    }
                                                } else R.string.device_disconnected), color = if (connected) accent else Silver, fontSize = 11.sp)
                                            }
                                        }
                                        HorizontalDivider(color = Silver.copy(alpha = 0.13f))
                                    }
                                    TextButton(onClick = { onDismiss(); onPair() }, modifier = Modifier.testTag("connect-new-device")) {
                                        Text("＋ " + stringResource(R.string.connect_new_device), color = accent)
                                    }
                                }
                                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    SettingTitle(stringResource(R.string.gpu_selection))
                                    state.gpus.forEach { gpu ->
                                        Row(Modifier.fillMaxWidth().clickable { model.selectGpu(gpu.id) },
                                            verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(state.snapshot?.gpu?.id == gpu.id, { model.selectGpu(gpu.id) },
                                                colors = RadioButtonDefaults.colors(selectedColor = accent, unselectedColor = Silver))
                                            Text(gpu.name, fontSize = 12.sp)
                                        }
                                    }
                                    TextButton(onClick = { model.startDemo(); onDismiss() }) { Text(stringResource(R.string.demo), color = accent) }
                                    if (state.demo) TextButton(onClick = { model.exitDemo(); onDismiss() }) {
                                        Text(stringResource(R.string.exit_demo), color = accent)
                                    }
                                }
                            }
                            1 -> {
                                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    SettingGroup(stringResource(R.string.theme)) {
                                        Choices(listOf("SPORT", "HUD"), value.theme.ordinal, accent) { index ->
                                            model.changeSettings { it.copy(theme = DashboardTheme.entries[index]) }
                                        }
                                    }
                                    SettingGroup(stringResource(R.string.language)) {
                                        Choices(listOf(stringResource(R.string.system), "简体中文", "English"),
                                            listOf("system", "zh", "en").indexOf(value.language), accent) { index ->
                                            model.changeSettings { it.copy(language = listOf("system", "zh", "en")[index]) }
                                        }
                                    }
                                    SettingGroup(stringResource(R.string.brand_style)) {
                                        Choices(listOf("ROG", "TELEMETRY"), if (value.rogBranding) 0 else 1, accent) { index ->
                                            model.changeSettings { it.copy(rogBranding = index == 0) }
                                        }
                                    }
                                }
                                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    SettingGroup(stringResource(R.string.night)) {
                                        Choices(listOf(stringResource(R.string.system), stringResource(R.string.night_on), stringResource(R.string.night_off)),
                                            listOf("system", "on", "off").indexOf(value.night), accent) { index ->
                                            model.changeSettings { it.copy(night = listOf("system", "on", "off")[index]) }
                                        }
                                    }
                                    val pixelDescription = stringResource(R.string.pixel_shift)
                                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(pixelDescription, Modifier.weight(1f), fontSize = 13.sp)
                                        Switch(value.pixelShift, { enabled -> model.changeSettings { it.copy(pixelShift = enabled) } },
                                            modifier = Modifier.semantics { contentDescription = pixelDescription }, colors = settingsSwitchColors(accent))
                                    }
                                    HorizontalDivider(color = Silver.copy(alpha = 0.16f))
                                    Text(stringResource(R.string.settings_device_hint), color = Silver, fontSize = 12.sp)
                                }
                            }
                            2 -> {
                                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(stringResource(R.string.microphone_meter), Modifier.weight(1f), fontSize = 14.sp)
                                        Switch(value.microphoneMeter, onMicrophoneChange, modifier = Modifier.testTag("microphone-switch"),
                                            colors = settingsSwitchColors(accent))
                                    }
                                    Text(stringResource(R.string.microphone_note), color = Silver, fontSize = 12.sp)
                                }
                                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SettingTitle(stringResource(R.string.microphone_sensitivity, sensitivity.toInt()))
                                    Slider(sensitivity, { sensitivity = it; onMicrophoneSensitivityPreview(it.toInt()) },
                                        onValueChangeFinished = { val level = sensitivity.toInt(); model.changeSettings { it.copy(microphoneSensitivity = level) } },
                                        valueRange = 0f..100f, modifier = Modifier.testTag("microphone-sensitivity"),
                                        colors = SliderDefaults.colors(thumbColor = White, activeTrackColor = accent,
                                            inactiveTrackColor = Silver.copy(alpha = 0.25f)))
                                    Text(stringResource(R.string.microphone_sensitivity_hint), color = Silver, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun settingsSwitchColors(accent: Color) = SwitchDefaults.colors(checkedThumbColor = Color(0xFF07151B),
    checkedTrackColor = accent, uncheckedThumbColor = Silver, uncheckedTrackColor = Color(0xFF273D4A))

@Composable
private fun SettingTitle(text: String) { Text(text, color = White, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium) }

@Composable
private fun SettingGroup(title: String, content: @Composable () -> Unit) {
    Column { SettingTitle(title); content() }
}

@Composable
private fun Choices(labels: List<String>, selected: Int, accent: Color, change: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { index, label ->
            FilterChip(selected == index, { change(index) }, label = { Text(label, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1) },
                border = BorderStroke(0.65.dp, if (selected == index) accent.copy(alpha = 0.65f) else Silver.copy(alpha = 0.38f)),
                shape = CutCornerShape(5.dp), colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color(0xFF12232D), labelColor = White,
                    selectedContainerColor = accent.copy(alpha = 0.17f), selectedLabelColor = accent))
        }
    }
}
