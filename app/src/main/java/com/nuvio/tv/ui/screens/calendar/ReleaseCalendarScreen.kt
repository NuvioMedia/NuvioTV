@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.calendar

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

@Composable
fun ReleaseCalendarScreen(
    showBuiltInHeader: Boolean = true,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?, season: Int?, episode: Int?) -> Unit,
    viewModel: ReleaseCalendarViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onScreenResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val selectedCellFocus = remember { FocusRequester() }
    val firstAgendaFocus = remember { FocusRequester() }
    val restoreAgendaFocus = remember { FocusRequester() }
    val refreshFocus = remember { FocusRequester() }
    var gridHasFocus by remember { mutableStateOf(false) }
    var pendingGridFocus by remember { mutableStateOf(false) }
    var pendingInitialFocus by remember { mutableStateOf(true) }
    var lastFocusedEventKey by rememberSaveable { mutableStateOf<String?>(null) }

    val showCalendar = uiState.events.isNotEmpty()
    val topPadding = if (showBuiltInHeader) NuvioTheme.spacing.xl else 68.dp

    LaunchedEffect(showCalendar, uiState.isLoading, pendingInitialFocus) {
        if (!pendingInitialFocus) return@LaunchedEffect
        withFrameNanos { }
        if (showCalendar) {
            val restoreKey = lastFocusedEventKey
            val restored = restoreKey != null &&
                uiState.selectedEvents.any { it.key == restoreKey } &&
                runCatching { restoreAgendaFocus.requestFocus() }.isSuccess
            if (!restored) runCatching { selectedCellFocus.requestFocus() }
            pendingInitialFocus = false
        } else if (!uiState.isLoading) {
            runCatching { refreshFocus.requestFocus() }
        }
    }

    LaunchedEffect(uiState.selectedDate, uiState.visibleMonth) {
        if (gridHasFocus || pendingGridFocus) {
            withFrameNanos { }
            runCatching { selectedCellFocus.requestFocus() }
            pendingGridFocus = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = NuvioTheme.spacing.xxxl,
                end = NuvioTheme.spacing.xxxl,
                top = topPadding,
                bottom = NuvioTheme.spacing.xl,
            ),
    ) {
        ReleaseCalendarHeader(
            isRefreshing = uiState.isLoading,
            refreshEnabled = uiState.hasItems,
            refreshFocus = refreshFocus,
            onRefresh = viewModel::refresh,
        )
        if (uiState.failedSeriesCount > 0 && !uiState.isLoading) {
            ReleaseCalendarFailureBanner(
                failedSeriesCount = uiState.failedSeriesCount,
                onRetry = viewModel::retryFailed,
            )
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

        when {
            !showCalendar && uiState.isLoading -> ReleaseCalendarLoadingState(modifier = Modifier.weight(1f))
            !showCalendar -> EmptyScreenState(
                title = stringResource(R.string.release_calendar_empty_title),
                subtitle = stringResource(R.string.release_calendar_empty_message),
                icon = Icons.Default.CalendarMonth,
                modifier = Modifier.weight(1f),
            )
            else -> Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl),
            ) {
                ReleaseCalendarMonthCard(
                    month = uiState.visibleMonth,
                    monthEventCount = uiState.monthEventCount,
                    eventsByDate = uiState.eventsByDate,
                    selectedDate = uiState.selectedDate,
                    today = uiState.today,
                    selectedCellFocus = selectedCellFocus,
                    onGridFocusChanged = { gridHasFocus = it },
                    onPrevious = viewModel::showPreviousMonth,
                    onNext = viewModel::showNextMonth,
                    onGridPrevious = {
                        pendingGridFocus = true
                        viewModel.showPreviousMonth()
                    },
                    onGridNext = {
                        pendingGridFocus = true
                        viewModel.showNextMonth()
                    },
                    onToday = viewModel::showToday,
                    onDateFocused = viewModel::selectDate,
                    onDateClicked = {
                        if (uiState.selectedEvents.isNotEmpty()) runCatching { firstAgendaFocus.requestFocus() }
                    },
                    modifier = Modifier
                        .weight(1.1f)
                        .fillMaxHeight(),
                )
                ReleaseCalendarAgenda(
                    selectedDate = uiState.selectedDate,
                    events = uiState.selectedEvents,
                    today = uiState.today,
                    isLoading = uiState.isLoading,
                    selectedCellFocus = selectedCellFocus,
                    firstAgendaFocus = firstAgendaFocus,
                    restoreAgendaFocus = restoreAgendaFocus,
                    restoreKey = lastFocusedEventKey,
                    onEventFocused = { lastFocusedEventKey = it.key },
                    onEventClick = { event ->
                        lastFocusedEventKey = event.key
                        onNavigateToDetail(
                            event.item.id,
                            event.item.type,
                            event.item.addonBaseUrl,
                            event.seasonNumber,
                            event.episodeNumber,
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun ReleaseCalendarHeader(
    isRefreshing: Boolean,
    refreshEnabled: Boolean,
    refreshFocus: FocusRequester,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs),
        ) {
            Text(
                text = stringResource(R.string.release_calendar_title),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.release_calendar_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.lg))
        Button(
            onClick = { if (!isRefreshing) onRefresh() },
            enabled = refreshEnabled,
            modifier = Modifier.focusRequester(refreshFocus),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary,
            ),
        ) {
            if (isRefreshing) {
                LoadingIndicator(modifier = Modifier.size(18.dp))
            } else {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Text(
                text = if (isRefreshing) {
                    stringResource(R.string.release_calendar_refreshing)
                } else {
                    stringResource(R.string.release_calendar_refresh)
                },
            )
        }
    }
}

@Composable
private fun ReleaseCalendarFailureBanner(
    failedSeriesCount: Int,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = NuvioTheme.spacing.md)
            .clip(RoundedCornerShape(NuvioTheme.radii.md))
            .background(NuvioTheme.colors.Error.copy(alpha = 0.16f))
            .padding(start = NuvioTheme.spacing.lg, end = NuvioTheme.spacing.xs, top = NuvioTheme.spacing.xs, bottom = NuvioTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
    ) {
        Text(
            text = pluralStringResource(R.plurals.release_calendar_failed_series, failedSeriesCount, failedSeriesCount),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary,
            ),
            contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.xs),
        ) {
            Text(text = stringResource(R.string.release_calendar_retry))
        }
    }
}

@Composable
private fun ReleaseCalendarLoadingState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LoadingIndicator(modifier = Modifier.size(40.dp))
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
        Text(
            text = stringResource(R.string.release_calendar_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
        )
    }
}

@Composable
private fun ReleaseCalendarMonthCard(
    month: YearMonth,
    monthEventCount: Int,
    eventsByDate: Map<LocalDate, List<ReleaseCalendarEvent>>,
    selectedDate: LocalDate,
    today: LocalDate,
    selectedCellFocus: FocusRequester,
    onGridFocusChanged: (Boolean) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onGridPrevious: () -> Unit,
    onGridNext: () -> Unit,
    onToday: () -> Unit,
    onDateFocused: (LocalDate) -> Unit,
    onDateClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val locale = currentCalendarLocale()
    val firstDayOfWeek = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val cells = remember(month, firstDayOfWeek) { releaseCalendarCells(month, firstDayOfWeek) }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(NuvioTheme.radii.xl))
            .background(NuvioTheme.colors.BackgroundCard)
            .padding(NuvioTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        ) {
            ReleaseCalendarMonthButton(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.release_calendar_previous_month),
                onClick = onPrevious,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = remember(month, locale) { formatCalendarMonthTitle(context, month) },
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioTheme.colors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = pluralStringResource(R.plurals.release_calendar_release_count, monthEventCount, monthEventCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.TextSecondary,
                )
            }
            Button(
                onClick = onToday,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary,
                ),
                contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.xs),
            ) {
                Text(
                    text = stringResource(R.string.release_calendar_today),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            ReleaseCalendarMonthButton(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.release_calendar_next_month),
                onClick = onNext,
            )
        }

        ReleaseCalendarWeekdayHeader(firstDayOfWeek = firstDayOfWeek, locale = locale)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onFocusChanged { onGridFocusChanged(it.hasFocus) }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.ChannelUp, Key.PageUp, Key.MediaRewind, Key.MediaPrevious -> {
                            onGridPrevious()
                            true
                        }
                        Key.ChannelDown, Key.PageDown, Key.MediaFastForward, Key.MediaNext -> {
                            onGridNext()
                            true
                        }
                        else -> false
                    }
                },
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
        ) {
            cells.chunked(7).forEach { week ->
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
                ) {
                    week.forEach { date ->
                        if (date == null) {
                            Spacer(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        } else {
                            ReleaseCalendarDayCell(
                                date = date,
                                eventCount = eventsByDate[date]?.size ?: 0,
                                isSelected = date == selectedDate,
                                isToday = date == today,
                                focusRequester = if (date == selectedDate) selectedCellFocus else null,
                                onFocused = { onDateFocused(date) },
                                onClick = onDateClicked,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseCalendarMonthButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        colors = IconButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated,
            focusedContainerColor = NuvioTheme.colors.Secondary,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContentColor = NuvioTheme.colors.OnSecondary,
        ),
        border = IconButtonDefaults.border(
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = CircleShape,
            ),
        ),
        shape = IconButtonDefaults.shape(shape = CircleShape),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun ReleaseCalendarWeekdayHeader(firstDayOfWeek: DayOfWeek, locale: Locale) {
    val labels = remember(firstDayOfWeek, locale) {
        (0 until 7).map { offset ->
            firstDayOfWeek.plus(offset.toLong()).getDisplayName(TextStyle.SHORT, locale)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
    ) {
        labels.forEach { label ->
            Text(
                text = label.uppercase(locale),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = NuvioTheme.colors.TextTertiary,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ReleaseCalendarDayCell(
    date: LocalDate,
    eventCount: Int,
    isSelected: Boolean,
    isToday: Boolean,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    val hasEvents = eventCount > 0
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    val restingBorder = when {
        isSelected -> Border(border = BorderStroke(2.dp, NuvioTheme.colors.Secondary), shape = shape)
        isToday -> Border(border = BorderStroke(1.dp, NuvioTheme.colors.Secondary.copy(alpha = 0.7f)), shape = shape)
        else -> Border.None
    }
    Surface(
        onClick = onClick,
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { state ->
                if (state.isFocused != isFocused) {
                    isFocused = state.isFocused
                    if (state.isFocused) onFocused()
                }
            },
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) NuvioTheme.colors.FocusBackground else Color.Transparent,
            contentColor = when {
                isSelected || isToday || hasEvents -> NuvioTheme.colors.TextPrimary
                else -> NuvioTheme.colors.TextSecondary
            },
            focusedContainerColor = NuvioTheme.colors.Secondary,
            focusedContentColor = NuvioTheme.colors.OnSecondary,
        ),
        border = ClickableSurfaceDefaults.border(
            border = restingBorder,
            focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (hasEvents || isToday) FontWeight.Bold else FontWeight.Normal,
                modifier = if (hasEvents) Modifier.padding(bottom = NuvioTheme.spacing.sm) else Modifier,
            )
            if (hasEvents) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = NuvioTheme.spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    repeat(eventCount.coerceAtMost(3)) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isFocused) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.Secondary,
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseCalendarAgenda(
    selectedDate: LocalDate,
    events: List<ReleaseCalendarEvent>,
    today: LocalDate,
    isLoading: Boolean,
    selectedCellFocus: FocusRequester,
    firstAgendaFocus: FocusRequester,
    restoreAgendaFocus: FocusRequester,
    restoreKey: String?,
    onEventFocused: (ReleaseCalendarEvent) -> Unit,
    onEventClick: (ReleaseCalendarEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val locale = currentCalendarLocale()
    val listState = rememberLazyListState()
    LaunchedEffect(selectedDate) {
        if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
            listState.scrollToItem(0)
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs),
            ) {
                Text(
                    text = stringResource(R.string.release_calendar_agenda),
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioTheme.colors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = remember(selectedDate, locale) { formatCalendarAgendaDate(context, selectedDate) },
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(NuvioTheme.radii.full))
                    .background(NuvioTheme.colors.BackgroundCard)
                    .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs),
            ) {
                Text(
                    text = pluralStringResource(R.plurals.release_calendar_release_count, events.size, events.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

        if (events.isEmpty()) {
            ReleaseCalendarNoDayEvents()
            if (isLoading) {
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    LoadingIndicator(modifier = Modifier.size(24.dp))
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = NuvioTheme.spacing.xs, horizontal = NuvioTheme.spacing.xxs),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            ) {
                itemsIndexed(items = events, key = { _, event -> event.key }) { index, event ->
                    ReleaseCalendarEventRow(
                        event = event,
                        today = today,
                        modifier = Modifier
                            .then(if (index == 0) Modifier.focusRequester(firstAgendaFocus) else Modifier)
                            .then(if (event.key == restoreKey) Modifier.focusRequester(restoreAgendaFocus) else Modifier)
                            .focusProperties { left = selectedCellFocus },
                        onFocused = { onEventFocused(event) },
                        onClick = { onEventClick(event) },
                    )
                }
                if (isLoading) {
                    item(key = "release_calendar_loading") {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            LoadingIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseCalendarNoDayEvents() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NuvioTheme.radii.lg))
            .background(NuvioTheme.colors.BackgroundCard)
            .padding(NuvioTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(NuvioTheme.radii.md))
                .background(NuvioTheme.colors.BackgroundElevated),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.CalendarMonth,
                contentDescription = null,
                tint = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
            Text(
                text = stringResource(R.string.release_calendar_no_day_events_title),
                style = MaterialTheme.typography.titleSmall,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.release_calendar_no_day_events_message),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
            )
        }
    }
}

@Composable
private fun ReleaseCalendarEventRow(
    event: ReleaseCalendarEvent,
    today: LocalDate,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.lg)
    val isUpcoming = remember(event, today) { event.isUpcoming(today) }
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = CardDefaults.shape(shape = shape),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
        ),
        border = CardDefaults.border(
            border = Border(border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border), shape = shape),
            focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape),
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(NuvioTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReleaseCalendarEventArtwork(event = event)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs),
            ) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                event.subtitle?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stringResource(
                        if (isUpcoming) R.string.release_calendar_upcoming else R.string.release_calendar_available,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.Secondary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun ReleaseCalendarEventArtwork(event: ReleaseCalendarEvent) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Box(
        modifier = Modifier
            .size(width = 144.dp, height = 81.dp)
            .clip(shape)
            .background(NuvioTheme.colors.BackgroundElevated),
        contentAlignment = Alignment.Center,
    ) {
        if (!event.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = event.imageUrl,
                contentDescription = event.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = remember(event.date) { formatCalendarShortDate(context, event.date) },
                style = MaterialTheme.typography.labelLarge,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.sm),
            )
        }
    }
}

@Composable
private fun currentCalendarLocale(): Locale {
    val configuration = LocalConfiguration.current
    return remember(configuration) {
        configuration.locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()
    }
}

private fun releaseCalendarCells(month: YearMonth, firstDayOfWeek: DayOfWeek): List<LocalDate?> {
    val offset = (month.atDay(1).dayOfWeek.value - firstDayOfWeek.value + 7) % 7
    val cells = MutableList<LocalDate?>(offset) { null }
    for (day in 1..month.lengthOfMonth()) {
        cells += month.atDay(day)
    }
    while (cells.size < 42) {
        cells += null
    }
    return cells
}

private fun LocalDate.toCalendarEpochMillis(): Long =
    atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun formatCalendarMonthTitle(context: Context, month: YearMonth): String =
    DateUtils.formatDateTime(
        context,
        month.atDay(1).toCalendarEpochMillis(),
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_MONTH_DAY or DateUtils.FORMAT_SHOW_YEAR,
    )

private fun formatCalendarAgendaDate(context: Context, date: LocalDate): String =
    DateUtils.formatDateTime(
        context,
        date.toCalendarEpochMillis(),
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_YEAR,
    )

private fun formatCalendarShortDate(context: Context, date: LocalDate): String =
    DateUtils.formatDateTime(
        context,
        date.toCalendarEpochMillis(),
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR,
    )
