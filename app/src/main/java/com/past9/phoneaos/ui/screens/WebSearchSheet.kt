package com.past9.phoneaos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.R
import com.past9.phoneaos.ui.AppCard
import com.past9.phoneaos.ui.AppTextField
import com.past9.phoneaos.web.SearchProvider
import com.past9.phoneaos.web.WebSearchMode
import kotlinx.coroutines.launch

/** Settings > Web search: what the sheet reads and writes. */
data class WebSearchActions(
    val mode: () -> String = { "questions" },
    val setMode: (String) -> Unit = {},
    val provider: () -> String = { "auto" },
    val setProvider: (String) -> Unit = {},
    val parallel: () -> Boolean = { true },
    val setParallel: (Boolean) -> Unit = {},
    val hasKey: (String) -> Boolean = { false },
    val setKey: (String, String?) -> Unit = { _, _ -> },
    /** Runs one real search and returns a short result. Never throws. */
    val test: suspend () -> String = { "No search key." },
)

/**
 * Settings > Web search. A slide-up sheet in the app's own pieces: research mode, one encrypted key per provider,
 * preferred provider, the parallel switch, and a real test search.
 */
@Composable
internal fun WebSearchSheet(actions: WebSearchActions, onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf(WebSearchMode.of(actions.mode())) }
    var provider by remember { mutableStateOf(actions.provider()) }
    var parallel by remember { mutableStateOf(actions.parallel()) }
    var editing by remember { mutableStateOf<SearchProvider?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val modes = listOf(WebSearchMode.QUESTIONS, WebSearchMode.ALWAYS, WebSearchMode.OFF)
    com.past9.phoneaos.ui.AgentSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding().imePadding()) {
            com.past9.phoneaos.ui.SheetHead(
                Icons.Rounded.Public,
                stringResource(R.string.settings_web_search),
                stringResource(R.string.settings_web_search_sub),
                stringResource(R.string.web_search_privacy),
            )
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.web_search_before), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { mode = m; actions.setMode(m.wire) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("web-mode-${m.wire}"),
                    ) { Text(modeLabel(m), maxLines = 1) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(modeHint(mode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.web_search_providers), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                SearchProvider.order.forEach { p ->
                    val saved = actions.hasKey(p.id)
                    Surface(
                        onClick = { editing = if (editing == p) null else p },
                        color = androidx.compose.ui.graphics.Color.Transparent,
                        modifier = Modifier.fillMaxWidth().testTag("web-provider-${p.id}"),
                    ) {
                        Row(Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Language, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.label, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (saved) stringResource(R.string.settings_key_saved) else stringResource(R.string.web_search_add_key),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(if (saved) Icons.Rounded.CheckCircle else Icons.Rounded.Key, null, tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (editing == p) KeyEditor(
                        provider = p,
                        hasKey = saved,
                        onSave = { k -> actions.setKey(p.id, k); editing = null },
                        onRemove = { actions.setKey(p.id, null); editing = null },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.web_search_preferred), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            com.past9.phoneaos.ui.ChoiceChips(
                options = listOf(Triple("auto", stringResource(R.string.web_search_auto), null)) +
                    SearchProvider.order.map { Triple(it.id, it.label, null) },
                selected = provider,
                onSelect = { provider = it; actions.setProvider(it) },
            )
            Spacer(Modifier.height(20.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Bolt, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.web_search_parallel), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.web_search_parallel_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(parallel, { on -> parallel = on; actions.setParallel(on) }, modifier = Modifier.testTag("web-parallel"))
                }
            }
            Spacer(Modifier.height(16.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.web_search_test), style = MaterialTheme.typography.titleSmall)
                        Text(testResult ?: stringResource(R.string.web_search_test_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = {
                            if (!testing) {
                                testing = true; testResult = null
                                scope.launch {
                                    testResult = runCatching { actions.test() }.getOrElse { context.getString(R.string.web_search_test_failed, it.message ?: "failed") }
                                    testing = false
                                }
                            }
                        },
                        enabled = !testing,
                        modifier = Modifier.testTag("web-test"),
                    ) { Text(stringResource(if (testing) R.string.web_search_testing else R.string.web_search_test), maxLines = 1) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.web_search_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun modeLabel(mode: WebSearchMode): String = when (mode) {
    WebSearchMode.QUESTIONS -> stringResource(R.string.web_search_questions)
    WebSearchMode.ALWAYS -> stringResource(R.string.web_search_always)
    WebSearchMode.OFF -> stringResource(R.string.rel_off)
}

@Composable
private fun modeHint(mode: WebSearchMode): String = when (mode) {
    WebSearchMode.QUESTIONS -> stringResource(R.string.web_search_questions_sub)
    WebSearchMode.ALWAYS -> stringResource(R.string.web_search_always_sub)
    WebSearchMode.OFF -> stringResource(R.string.web_search_off_sub)
}

@Composable
private fun KeyEditor(provider: SearchProvider, hasKey: Boolean, onSave: (String) -> Unit, onRemove: () -> Unit) {
    var key by remember(provider) { mutableStateOf("") }
    var show by remember(provider) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        AppTextField(
            key,
            { key = it.trim() },
            label = { Text(stringResource(R.string.web_search_key_label, provider.label)) },
            placeholder = { Text("tvly-…  |  exa-…  |  fc-…  |  …") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, stringResource(if (show) R.string.onb_hide_key else R.string.onb_show_key)) }
                    TextButton(onClick = { if (key.isNotBlank()) onSave(key) }, enabled = key.isNotBlank(), modifier = Modifier.testTag("web-key-save")) {
                        Text(stringResource(R.string.settings_save))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("web-key-${provider.id}"),
        )
        if (hasKey) TextButton(onClick = onRemove, modifier = Modifier.testTag("web-key-remove")) {
            Text(stringResource(R.string.web_search_remove_key), color = MaterialTheme.colorScheme.error)
        }
    }
}
