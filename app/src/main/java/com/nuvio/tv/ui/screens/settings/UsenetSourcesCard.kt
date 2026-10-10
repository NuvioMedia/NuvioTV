@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.usenet.*
import java.text.DateFormat
import java.util.Date
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch

@Composable
internal fun UsenetSourcesCard(
    configuration: UsenetSourceConfiguration,
    update: (UsenetSourceConfiguration) -> Unit,
    testIndexer: suspend (UsenetIndexer) -> Boolean,
    testProvider: suspend (UsenetProvider) -> ProviderTestResult?,
    initialFocusRequester: FocusRequester,
    indexerStatuses: Map<String, IndexerStatus> = emptyMap(),
    setUpFromPhone: (() -> Unit)? = null,
    phoneSetupOpen: Boolean = false
) {
    val phoneRow = remember { FocusRequester() }
    var phoneSetupShown by remember { mutableStateOf(false) }
    // The QR overlay took focus; give it back to the row that opened it, not the side menu.
    LaunchedEffect(phoneSetupOpen) {
        if (phoneSetupOpen) phoneSetupShown = true
        else if (phoneSetupShown) { phoneSetupShown = false; runCatching { phoneRow.requestFocus() } }
    }
    var provider by remember { mutableStateOf<UsenetProvider?>(null) }
    var indexer by remember { mutableStateOf<UsenetIndexer?>(null) }
    var picker by remember { mutableStateOf<String?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val sortOptions = listOf(
        SettingsPickerOption(UsenetSort.QUALITY, stringResource(R.string.usenet_sort_quality)),
        SettingsPickerOption(UsenetSort.LARGEST, stringResource(R.string.usenet_sort_largest)),
        SettingsPickerOption(UsenetSort.SMALLEST, stringResource(R.string.usenet_sort_smallest)),
        SettingsPickerOption(UsenetSort.NEWEST, stringResource(R.string.usenet_sort_newest)),
        SettingsPickerOption(UsenetSort.INDEXER, stringResource(R.string.usenet_sort_indexer))
    )
    val unlimited = stringResource(R.string.usenet_no_limit)
    SettingsGroupCard(title = stringResource(R.string.usenet_sources_title)) {
        SettingsToggleRow(title = stringResource(R.string.usenet_builtin_enabled),
            subtitle = stringResource(R.string.usenet_builtin_description), checked = configuration.enabled,
            onToggle = { update(configuration.copy(enabled = !configuration.enabled)) },
            modifier = Modifier.focusRequester(initialFocusRequester))
        if (setUpFromPhone != null) SettingsActionRow(title = stringResource(R.string.usenet_phone_setup),
            subtitle = stringResource(R.string.usenet_phone_setup_description), onClick = setUpFromPhone,
            modifier = Modifier.focusRequester(phoneRow))
        SettingsActionRow(title = stringResource(R.string.usenet_add_provider),
            subtitle = stringResource(R.string.usenet_provider_priority_hint), onClick = { provider = UsenetProvider() })
        configuration.providers.forEachIndexed { i, item ->
            SettingsActionRow(title = item.name, subtitle = "${i + 1}. ${item.host}:${item.port} • ${item.connections} • " +
                stringResource(R.string.usenet_priority_value, item.priority),
                value = stringResource(if (item.enabled) R.string.usenet_source_enabled else R.string.usenet_source_disabled),
                onClick = { selectedId = item.id; picker = "provider" })
        }
        SettingsActionRow(title = stringResource(R.string.usenet_add_indexer),
            subtitle = stringResource(R.string.usenet_indexer_hint), onClick = { indexer = UsenetIndexer() })
        configuration.indexers.forEachIndexed { i, item ->
            SettingsActionRow(title = item.name, subtitle = listOfNotNull("${i + 1}. ${item.apiUrl.substringBefore('?')}",
                stringResource(R.string.usenet_priority_value, item.priority), indexerStatusLabel(indexerStatuses[item.id]))
                .joinToString(" • "),
                value = stringResource(if (item.enabled) R.string.usenet_source_enabled else R.string.usenet_source_disabled),
                onClick = { selectedId = item.id; picker = "indexer" })
        }
        SettingsToggleRow(title = stringResource(R.string.usenet_indexer_fallback),
            subtitle = stringResource(R.string.usenet_indexer_fallback_description), checked = configuration.indexerFallback,
            onToggle = { update(configuration.copy(indexerFallback = !configuration.indexerFallback)) })
        SettingsToggleRow(title = stringResource(R.string.usenet_hide_duplicates),
            subtitle = stringResource(R.string.usenet_hide_duplicates_description), checked = configuration.hideDuplicates,
            onToggle = { update(configuration.copy(hideDuplicates = !configuration.hideDuplicates)) })
        if (configuration.enabled && !configuration.ready) {
            Text(stringResource(R.string.usenet_sources_needed), color = NuvioTheme.colors.TextSecondary)
        }
        SettingsActionRow(title = stringResource(R.string.usenet_sort_results), subtitle = null,
            value = sortOptions.first { it.value == configuration.sort }.title, onClick = { picker = "sort" })
        SettingsActionRow(title = stringResource(R.string.usenet_min_resolution), subtitle = null,
            value = if (configuration.minResolution == 0) unlimited else "${configuration.minResolution}p",
            onClick = { picker = "resolution" })
        SettingsActionRow(title = stringResource(R.string.usenet_max_size), subtitle = null,
            value = if (configuration.maxSizeGb == 0) unlimited else "${configuration.maxSizeGb} GB", onClick = { picker = "size" })
        SettingsActionRow(title = stringResource(R.string.usenet_max_age), subtitle = null,
            value = if (configuration.maxAgeDays == 0) unlimited else "${configuration.maxAgeDays}d", onClick = { picker = "age" })
        SettingsActionRow(title = stringResource(R.string.usenet_result_limit), subtitle = null,
            value = configuration.maxResults.toString(), onClick = { picker = "limit" })
        SettingsToggleRow(title = stringResource(R.string.usenet_exclude_low_quality), subtitle = null,
            checked = configuration.excludeLowQuality,
            onToggle = { update(configuration.copy(excludeLowQuality = !configuration.excludeLowQuality)) })
    }
    when (picker) {
        "sort" -> SettingsSingleChoiceDialog(title = stringResource(R.string.usenet_sort_results), options = sortOptions,
            selectedValue = configuration.sort, onOptionSelected = { update(configuration.copy(sort = it)); picker = null },
            onDismiss = { picker = null })
        "resolution", "size", "age", "limit" -> {
            val key = picker
            val values = when (key) {
                "resolution" -> listOf(0, 480, 720, 1080, 2160)
                "size" -> listOf(0, 5, 10, 20, 40, 80, 120, 200)
                "age" -> listOf(0, 30, 90, 365, 1000, 3000, 5000)
                else -> listOf(10, 25, 50, 100, 200)
            }
            val title = stringResource(when (key) {
                "resolution" -> R.string.usenet_min_resolution
                "size" -> R.string.usenet_max_size
                "age" -> R.string.usenet_max_age
                else -> R.string.usenet_result_limit
            })
            val selected = when (key) {
                "resolution" -> configuration.minResolution
                "size" -> configuration.maxSizeGb
                "age" -> configuration.maxAgeDays
                else -> configuration.maxResults
            }
            SettingsSingleChoiceDialog(title = title,
                options = values.map { SettingsPickerOption(it, if (it == 0) unlimited else it.toString()) },
                selectedValue = selected, onOptionSelected = {
                    update(when (key) {
                        "resolution" -> configuration.copy(minResolution = it)
                        "size" -> configuration.copy(maxSizeGb = it)
                        "age" -> configuration.copy(maxAgeDays = it)
                        else -> configuration.copy(maxResults = it)
                    }); picker = null
                }, onDismiss = { picker = null })
        }
        "provider", "indexer" -> {
            val isProvider = picker == "provider"
            val providerItem = configuration.providers.find { it.id == selectedId }
            val indexerItem = configuration.indexers.find { it.id == selectedId }
            val enabled = providerItem?.enabled ?: indexerItem?.enabled ?: false
            val position = if (isProvider) configuration.providers.indexOf(providerItem) else configuration.indexers.indexOf(indexerItem)
            val priorities = if (isProvider) configuration.providers.map { it.priority } else configuration.indexers.map { it.priority }
            val priority = priorities.getOrNull(position)
            NuvioDialog(title = providerItem?.name ?: indexerItem?.name.orEmpty(), onDismiss = { picker = null }) {
                SettingsActionRow(title = stringResource(R.string.usenet_source_edit), subtitle = null, onClick = {
                    if (isProvider) provider = providerItem else indexer = indexerItem
                    picker = null
                })
                SettingsActionRow(title = stringResource(R.string.usenet_priority), subtitle = null,
                    value = priority?.let { priorityLabel(it) }, onClick = {
                        picker = if (isProvider) "providerPriority" else "indexerPriority"
                    })
                SettingsToggleRow(title = stringResource(R.string.usenet_source_enabled), subtitle = null, checked = enabled, onToggle = {
                    if (isProvider) update(configuration.copy(providers = configuration.providers.map {
                        if (it.id == selectedId) it.copy(enabled = !it.enabled) else it
                    })) else update(configuration.copy(indexers = configuration.indexers.map {
                        if (it.id == selectedId) it.copy(enabled = !it.enabled) else it
                    }))
                    picker = null
                })
                fun move(delta: Int) {
                    if (isProvider) update(configuration.copy(providers = configuration.providers.moved(position, delta)))
                    else update(configuration.copy(indexers = configuration.indexers.moved(position, delta)))
                    picker = null
                }
                // Order only matters within a priority; the list stays sorted by priority.
                if (priority != null && priorities.getOrNull(position - 1) == priority) {
                    SettingsActionRow(title = stringResource(R.string.usenet_move_up), subtitle = null, onClick = { move(-1) })
                }
                if (priority != null && priorities.getOrNull(position + 1) == priority) {
                    SettingsActionRow(title = stringResource(R.string.usenet_move_down), subtitle = null, onClick = { move(1) })
                }
                SettingsActionRow(title = stringResource(R.string.usenet_source_delete), subtitle = null, onClick = {
                    picker = if (isProvider) "deleteProvider" else "deleteIndexer"
                })
            }
        }
        "providerPriority", "indexerPriority" -> {
            val isProvider = picker == "providerPriority"
            val current = (if (isProvider) configuration.providers.find { it.id == selectedId }?.priority
                else configuration.indexers.find { it.id == selectedId }?.priority) ?: USENET_PRIORITIES.first
            SettingsSingleChoiceDialog(title = stringResource(R.string.usenet_priority),
                subtitle = stringResource(if (isProvider) R.string.usenet_provider_priority_hint else R.string.usenet_indexer_priority_hint),
                options = USENET_PRIORITIES.map { SettingsPickerOption(it, priorityLabel(it)) },
                selectedValue = current, onOptionSelected = { value ->
                    if (isProvider) update(configuration.copy(providers = configuration.providers.map {
                        if (it.id == selectedId) it.copy(priority = value) else it
                    })) else update(configuration.copy(indexers = configuration.indexers.map {
                        if (it.id == selectedId) it.copy(priority = value) else it
                    }))
                    picker = null
                }, onDismiss = { picker = null })
        }
        "deleteProvider", "deleteIndexer" -> NuvioDialog(title = stringResource(R.string.usenet_source_delete),
            subtitle = stringResource(R.string.usenet_delete_confirm), onDismiss = { picker = null }) {
            SettingsDialogActionRow {
                SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = { picker = null })
                SettingsDialogActionButton(text = stringResource(R.string.usenet_source_delete), onClick = {
                    if (picker == "deleteProvider") update(configuration.copy(providers = configuration.providers.filterNot { it.id == selectedId }))
                    else update(configuration.copy(indexers = configuration.indexers.filterNot { it.id == selectedId }))
                    picker = null
                })
            }
        }
    }
    provider?.let { item -> ProviderEditor(item, isNew = configuration.providers.none { it.id == item.id },
        testProvider, onDismiss = { provider = null }, onSave = {
        update(configuration.copy(providers = configuration.providers.replaceOrAdd(it) { p -> p.id }))
        provider = null
    }) }
    indexer?.let { item -> IndexerEditor(item, isNew = configuration.indexers.none { it.id == item.id },
        testIndexer, onDismiss = { indexer = null }, onSave = {
        update(configuration.copy(indexers = configuration.indexers.replaceOrAdd(it) { p -> p.id })); indexer = null
    }) }
}

private fun <T> List<T>.moved(position: Int, delta: Int): List<T> = toMutableList().apply {
    val item = removeAt(position); add(position + delta, item)
}

private fun <T> List<T>.replaceOrAdd(value: T, id: (T) -> String): List<T> =
    if (any { id(it) == id(value) }) map { if (id(it) == id(value)) value else it } else this + value

/** A pause wins over an older failure: it is what currently keeps the indexer out of searches. */
@Composable
private fun indexerStatusLabel(status: IndexerStatus?): String? {
    if (status == null) return null
    if (status.pausedUntil > System.currentTimeMillis()) {
        return stringResource(R.string.usenet_indexer_paused,
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(status.pausedUntil)))
    }
    return when (status.problem) {
        IndexerProblem.AUTH -> stringResource(R.string.usenet_indexer_problem_auth)
        IndexerProblem.UNREACHABLE -> stringResource(R.string.usenet_indexer_problem_unreachable)
        IndexerProblem.RESPONSE -> stringResource(R.string.usenet_indexer_problem_response)
        null -> null
    }
}

@Composable
private fun priorityLabel(priority: Int) = when (priority) {
    USENET_PRIORITIES.first -> stringResource(R.string.usenet_priority_highest, priority)
    USENET_PRIORITIES.last -> stringResource(R.string.usenet_priority_lowest, priority)
    else -> priority.toString()
}

@Composable
private fun ProviderEditor(item: UsenetProvider, isNew: Boolean, test: suspend (UsenetProvider) -> ProviderTestResult?,
    onDismiss: () -> Unit, onSave: (UsenetProvider) -> Unit) {
    var name by remember { mutableStateOf(item.name) }
    var host by remember { mutableStateOf(item.host) }
    var port by remember { mutableStateOf(item.port.toString()) }
    var connections by remember { mutableStateOf(item.connections.toString()) }
    var username by remember { mutableStateOf(item.username) }
    var password by remember { mutableStateOf(item.password) }
    var tls by remember { mutableStateOf(item.tls) }
    var error by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    // Keyed by the tested values, so editing any field clears a stale result.
    var tested by remember { mutableStateOf<Pair<UsenetProvider, ProviderTestResult>?>(null) }
    val scope = rememberCoroutineScope()
    fun value() = item.copy(name = name.trim(), host = host.trim(), port = port.toIntOrNull() ?: 0,
        connections = connections.toIntOrNull() ?: 0, tls = tls, username = username, password = password)
    NuvioDialog(title = stringResource(if (isNew) R.string.usenet_add_provider else R.string.usenet_edit_provider), onDismiss = onDismiss) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceField(stringResource(R.string.usenet_source_name), name, { name = it })
            SourceField(stringResource(R.string.usenet_provider_host), host, { host = it })
            SourceField(stringResource(R.string.usenet_provider_port), port, { port = it }, numeric = true)
            SettingsToggleRow(title = stringResource(R.string.usenet_provider_tls), subtitle = null, checked = tls, onToggle = {
                tls = !tls
                if (port == "119" || port == "563") port = if (tls) "563" else "119"
            })
            SourceField(stringResource(R.string.usenet_provider_username), username, { username = it })
            SourceField(stringResource(R.string.usenet_provider_password), password, { password = it }, secret = true)
            SourceField(stringResource(R.string.usenet_provider_connections), connections, { connections = it }, numeric = true)
        }
        // Outside the scrolling fields, so messages are visible next to the buttons.
        if (error) Text(stringResource(R.string.usenet_provider_invalid), color = NuvioTheme.colors.TextSecondary)
        val result = tested?.takeIf { it.first == value() }?.second
        if (testing || result != null) Text(stringResource(
            result?.messageRes(anonymous = username.isEmpty() && password.isEmpty()) ?: R.string.usenet_provider_test_running
        ), color = NuvioTheme.colors.TextSecondary)
        SettingsDialogActionRow {
            SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onDismiss)
            SettingsDialogActionButton(text = stringResource(R.string.usenet_provider_test), enabled = !testing, onClick = {
                val value = value()
                scope.launch {
                    testing = true
                    tested = null
                    val outcome = test(value)
                    if (outcome == null) error = true else tested = value to outcome
                    testing = false
                }
            })
            SettingsDialogActionButton(text = stringResource(R.string.action_save), primary = true, onClick = {
                val value = value()
                if (runCatching { value.validate() }.isSuccess) onSave(value) else error = true
            })
        }
    }
}

@Composable
private fun IndexerEditor(item: UsenetIndexer, isNew: Boolean, test: suspend (UsenetIndexer) -> Boolean,
    onDismiss: () -> Unit, onSave: (UsenetIndexer) -> Unit) {
    var name by remember { mutableStateOf(item.name) }
    var url by remember { mutableStateOf(item.apiUrl) }
    var key by remember { mutableStateOf(item.apiKey) }
    var error by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    fun value() = item.copy(name = name.trim(), apiUrl = url.trim(), apiKey = key.trim())
    NuvioDialog(title = stringResource(if (isNew) R.string.usenet_add_indexer else R.string.usenet_edit_indexer), subtitle = stringResource(R.string.usenet_indexer_hint), onDismiss = onDismiss) {
        SourceField(stringResource(R.string.usenet_source_name), name, { name = it; testResult = null })
        SourceField(stringResource(R.string.usenet_indexer_url), url, { url = it; testResult = null })
        SourceField(stringResource(R.string.usenet_indexer_key), key, { key = it; testResult = null }, secret = true)
        if (error) Text(stringResource(R.string.usenet_indexer_invalid), color = NuvioTheme.colors.TextSecondary)
        if (testing || testResult != null) Text(stringResource(when {
            testing -> R.string.usenet_test_running
            testResult == true -> R.string.usenet_test_success
            else -> R.string.usenet_test_failed
        }), color = NuvioTheme.colors.TextSecondary)
        SettingsDialogActionRow {
            SettingsDialogActionButton(text = stringResource(R.string.usenet_test_indexer), enabled = !testing, onClick = {
                scope.launch { testing = true; testResult = test(value()); testing = false }
            })
            SettingsDialogActionButton(text = stringResource(R.string.action_save), primary = true, onClick = {
                if (runCatching { value().validate() }.isSuccess) onSave(value()) else error = true
            })
        }
    }
}

@Composable
private fun SourceField(label: String, value: String, changed: (String) -> Unit,
    secret: Boolean = false, numeric: Boolean = false) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary)
        Card(onClick = { focus.requestFocus() }, modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.colors(containerColor = NuvioTheme.colors.Background,
                focusedContainerColor = NuvioTheme.colors.BackgroundElevated), scale = CardDefaults.scale(focusedScale = 1f)) {
            BasicTextField(value = value, onValueChange = changed, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(12.dp).focusRequester(focus).testTag(label)
                    // A focused text field keeps the D-pad; without this a remote cannot reach the next field.
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                            else -> false
                        }
                    },
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = when {
                    secret -> KeyboardType.Password
                    numeric -> KeyboardType.Number
                    else -> KeyboardType.Text
                }, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
                cursorBrush = SolidColor(NuvioTheme.colors.Primary))
        }
    }
}
