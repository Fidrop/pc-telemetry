package com.pctelemetry.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pctelemetry.core.PairingClient
import kotlinx.coroutines.launch

@Composable
fun SettingsPanel(model: DashboardViewModel, value: UiSettings, onDismiss: () -> Unit, onPair: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val pixelShiftDescription = stringResource(R.string.pixel_shift)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
            Spacer(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onDismiss))
            Surface(color = Color(0xFF101C27), contentColor = White,
                modifier = Modifier.fillMaxHeight().widthIn(max = 420.dp).width(380.dp)) {
                Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = onDismiss, modifier = Modifier.testTag("settings-close")) {
                            Text(stringResource(R.string.close))
                        }
                    }
                    Text(stringResource(R.string.theme))
                    Choices(listOf("SPORT", "HUD"), value.theme.ordinal) {
                        val theme = DashboardTheme.entries[it]
                        model.changeSettings { previous -> previous.copy(theme = theme) }
                    }
                    Text(stringResource(R.string.language))
                    Choices(listOf(stringResource(R.string.system), "简体中文", "English"),
                        listOf("system", "zh", "en").indexOf(value.language)) {
                        val language = listOf("system", "zh", "en")[it]
                        model.changeSettings { previous -> previous.copy(language = language) }
                    }
                    Text(stringResource(R.string.brand_style))
                    Choices(listOf("ROG", "TELEMETRY"), if (value.rogBranding) 0 else 1) { index ->
                        model.changeSettings { it.copy(rogBranding = index == 0) }
                    }
                    Text(stringResource(R.string.night))
                    Choices(listOf(stringResource(R.string.system), stringResource(R.string.night_on),
                        stringResource(R.string.night_off)), listOf("system", "on", "off").indexOf(value.night)) {
                        val night = listOf("system", "on", "off")[it]
                        model.changeSettings { previous -> previous.copy(night = night) }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.pixel_shift), Modifier.weight(1f))
                        Switch(value.pixelShift, { enabled -> model.changeSettings { it.copy(pixelShift = enabled) } },
                            modifier = Modifier.semantics { contentDescription = pixelShiftDescription },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF05252A),
                                checkedTrackColor = Cyan, uncheckedThumbColor = Silver,
                                uncheckedTrackColor = Color(0xFF273D4A)))
                    }
                    Text(stringResource(R.string.gpu_selection))
                    state.gpus.forEach { gpu ->
                        Row(Modifier.fillMaxWidth().clickable { model.selectGpu(gpu.id) },
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(state.snapshot?.gpu?.id == gpu.id, { model.selectGpu(gpu.id) })
                            Text(gpu.name)
                        }
                    }
                    Text(stringResource(R.string.connection_status) + " · " + state.phase.name)
                    TextButton(onClick = { onDismiss(); onPair() }) { Text(stringResource(R.string.pairing)) }
                    TextButton(onClick = { model.rePair(); onDismiss(); onPair() }) { Text(stringResource(R.string.re_pair)) }
                    TextButton(onClick = model::disconnect) { Text(stringResource(R.string.disconnect)) }
                    TextButton(onClick = { model.startDemo(); onDismiss() }) { Text(stringResource(R.string.demo)) }
                    if (state.demo) TextButton(onClick = { model.exitDemo(); onDismiss() }) {
                        Text(stringResource(R.string.exit_demo))
                    }
                }
            }
        }
    }
}
@Composable
private fun Choices(labels: List<String>, selected: Int, change: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            FilterChip(selected == index, { change(index) }, label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color(0xFF172B39), labelColor = White,
                    selectedContainerColor = Color(0xFF17464E), selectedLabelColor = Color(0xFF86F8F1)))
        }
    }
}

@Composable
fun PairingDialog(model: DashboardViewModel, initial: String?, onDismiss: () -> Unit) {
    val discovered by model.discovered.collectAsStateWithLifecycle()
    val profileError by model.error.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var endpoint by remember { mutableStateOf(initial ?: "") }
    var fingerprint by remember { mutableStateOf<String?>(null) }
    var confirmed by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pairingOperation by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun normalize(): String {
        val value = endpoint.trim().let { if ("://" in it) it else "wss://$it" }
        val uri = java.net.URI(value)
        return java.net.URI(uri.scheme, null, uri.host, if (uri.port == -1) 47631 else uri.port,
            if (uri.path.isNullOrEmpty() || uri.path == "/") "/v1/stream" else uri.path, null, null).toASCIIString()
    }
    fun changeEndpoint(value: String) {
        endpoint = value; fingerprint = null; confirmed = false; failed = false
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(max = 700.dp).fillMaxWidth(0.85f),
        title = { Text(stringResource(R.string.pairing)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pairing_intro))
                if (profileError == R.string.unpair_failed) Text(stringResource(R.string.unpair_failed))
                TextButton(onClick = model::discover, enabled = !busy) { Text(stringResource(R.string.search)) }
                discovered.forEach { item ->
                    TextButton(onClick = { changeEndpoint(item.endpoint) }, enabled = !busy) { Text(item.name) }
                }
                Text(stringResource(R.string.no_discovery))
                OutlinedTextField(endpoint, { changeEndpoint(it) },
                    label = { Text(stringResource(R.string.endpoint_manual)) }, singleLine = true, enabled = !busy)
                TextButton(onClick = {
                    scope.launch {
                        busy = true; pairingOperation = false; failed = false
                        try {
                            val requested = normalize()
                            fingerprint = PairingClient().fingerprint(requested)
                            confirmed = false
                        } catch (_: Exception) { failed = true }
                        finally { busy = false }
                    }
                }, enabled = !busy && endpoint.isNotBlank()) { Text(stringResource(R.string.check_fingerprint)) }
                fingerprint?.let { pin ->
                    Text(stringResource(R.string.fingerprint))
                    Text(pin, style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(confirmed, { confirmed = it }, enabled = !busy)
                        Text(stringResource(R.string.fingerprint_confirm))
                    }
                    OutlinedTextField(code, { code = it.filter(Char::isDigit).take(8) },
                        label = { Text(stringResource(R.string.pairing_code)) }, singleLine = true, enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                }
                if (failed) Text(stringResource(R.string.pairing_failed), color = MaterialTheme.colorScheme.error)
                if (busy) {
                    Text(stringResource(if (pairingOperation) R.string.pairing_busy else R.string.fingerprint_loading))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    busy = true; pairingOperation = true; failed = false
                    try {
                        val profile = PairingClient().pair(normalize(), fingerprint!!, code)
                        model.connect(profile)
                        onDismiss()
                    } catch (_: Exception) { failed = true }
                    finally { busy = false }
                }
            }, enabled = !busy && confirmed && fingerprint != null && code.length == 8) {
                Text(stringResource(R.string.pair))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { model.startDemo(); onDismiss() }, enabled = !busy) { Text(stringResource(R.string.demo)) }
                TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) }
            }
        })
}
