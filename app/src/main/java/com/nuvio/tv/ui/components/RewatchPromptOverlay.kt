@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.simkl.RewatchNotice
import com.nuvio.tv.data.simkl.RewatchNoticeKind
import com.nuvio.tv.data.simkl.RewatchPrompt
import com.nuvio.tv.data.simkl.SimklRewatchPromptRepository
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/** How long the question waits for an answer before it counts as Ignore. */
internal const val REWATCH_PROMPT_TIMEOUT_MS = 8_000L

/** How long the feedback of an answer stays on screen, the same time mobile keeps it. */
internal const val REWATCH_NOTICE_TIMEOUT_MS = 2_600L

/** What an answer to the question does to it. */
internal enum class RewatchPromptKeyOutcome {
    /** The question stays open, the key belongs to the buttons. */
    KEEP,

    /** The question closes without recording, which is the Ignore answer. */
    IGNORE
}

/**
 * What one key press does to the open question.
 *
 * Down is the key that closes the question. A TV has no way to tap outside a dialog, so a shared
 * answer is needed for the case where the user does not want to record the rewatch but also does not
 * want to answer the question: it is the same Ignore the button gives, applied without a second
 * click. Key up and every other key stay with the buttons, which own left, right and the click.
 */
internal fun rewatchPromptKeyOutcome(keyCode: Int, action: Int): RewatchPromptKeyOutcome {
    if (action != KeyEvent.ACTION_DOWN) return RewatchPromptKeyOutcome.KEEP
    return if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
        RewatchPromptKeyOutcome.IGNORE
    } else {
        RewatchPromptKeyOutcome.KEEP
    }
}

/**
 * The rewatch question and the feedback of its last answer, drawn above whatever is on screen.
 *
 * This is the TV counterpart of mobile `RewatchPromptHost`: the question asks about a playback that
 * Simkl accepted as a repeat viewing, and nothing is written until the user confirms. The answer
 * outlives the overlay, because the write runs on the scope of the repository, not the one that asked.
 *
 * The difference from mobile is the timeout: TV cannot click outside a dialog, so the question
 * cannot be left to vanish and the user has to see that it was asked. After eight seconds without
 * any interaction it closes as an ignore, with a visible "Nothing recorded" and not silently.
 */
@Composable
fun RewatchPromptOverlay(
    repository: SimklRewatchPromptRepository,
    modifier: Modifier = Modifier
) {
    val prompt by repository.prompt.collectAsStateWithLifecycle()
    val notice by repository.notice.collectAsStateWithLifecycle()

    prompt?.let { active ->
        RewatchQuestion(
            prompt = active,
            onConfirm = repository::confirm,
            onIgnore = repository::decline,
            modifier = modifier
        )
    }
    notice?.let { active ->
        RewatchNoticePill(
            notice = active,
            onDismissed = repository::dismissNotice,
            modifier = modifier
        )
    }
}

/**
 * The question itself, drawn as the slim band the app already uses for its own notices.
 *
 * The question is not a dialog in the middle of the screen: it is a band on the top edge, built
 * from the same pieces and the same numbers as the new version banner in `com.nuvio.tv.updater.ui`
 * (`app/src/full/java/com/nuvio/tv/updater/ui/UpdateBanner.kt`): a `BackgroundElevated` container
 * with a single line below it, at least 76 dp high, 32 dp of horizontal and 10 dp of vertical
 * padding, a 28 dp icon on the left, then the text, and pill shaped buttons on the right. The banner
 * is the only notice the app has of its own, so the question looks like it and not like its own design.
 *
 * The dialog window stays, but it fills the screen, so focus and the Back button stay with the
 * question: the band is drawn at the top of it, not in the middle. Focus starts on `Record` and the
 * right arrow moves to `No`, so the answer that writes to the account is never the one picked by mistake.
 */
@Composable
private fun RewatchQuestion(
    prompt: RewatchPrompt,
    onConfirm: () -> Unit,
    onIgnore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val confirmFocusRequester = remember { FocusRequester() }
    val ignoreFocusRequester = remember { FocusRequester() }
    var interactionCount by remember(prompt) { mutableIntStateOf(0) }

    // The timer restarts on every key press: "no interaction" is measured from the last one, so the
    // question cannot close while the user is still moving towards an answer.
    LaunchedEffect(prompt, interactionCount) {
        delay(REWATCH_PROMPT_TIMEOUT_MS)
        onIgnore()
    }

    LaunchedEffect(prompt) {
        runCatching { confirmFocusRequester.requestFocusAfterFrames() }
    }

    Dialog(
        onDismissRequest = onIgnore,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == KeyEvent.ACTION_DOWN) interactionCount += 1
                    when (rewatchPromptKeyOutcome(native.keyCode, native.action)) {
                        RewatchPromptKeyOutcome.KEEP -> false
                        RewatchPromptKeyOutcome.IGNORE -> {
                            onIgnore()
                            true
                        }
                    }
                },
            contentAlignment = Alignment.TopCenter
        ) {
            RewatchQuestionBand(
                onConfirm = onConfirm,
                onIgnore = onIgnore,
                confirmFocusRequester = confirmFocusRequester,
                ignoreFocusRequester = ignoreFocusRequester
            )
        }
    }
}

/** The band itself: the same pieces, sizes and colours the updater banner is built from. */
@Composable
private fun RewatchQuestionBand(
    onConfirm: () -> Unit,
    onIgnore: () -> Unit,
    confirmFocusRequester: FocusRequester,
    ignoreFocusRequester: FocusRequester
) {
    val containerColor = NuvioTheme.colors.BackgroundElevated
    val dividerColor = NuvioTheme.colors.Border

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(containerColor)
                drawRect(
                    color = dividerColor,
                    topLeft = Offset(0f, size.height - 1.dp.toPx()),
                    size = Size(width = size.width, height = 1.dp.toPx())
                )
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp)
                .padding(horizontal = 32.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Repeat,
                contentDescription = null,
                tint = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.size(28.dp)
            )

            Text(
                text = stringResource(R.string.rewatch_prompt_title),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            /*
             * Both buttons keep left and right between themselves. The D pad would otherwise be able
             * to walk out of the band, because the band is a strip and not a box the focus is
             * trapped in, and the question would then be left unanswered behind whatever screen the
             * user moved to.
             */
            Button(
                onClick = onConfirm,
                modifier = Modifier
                    .focusRequester(confirmFocusRequester)
                    .focusProperties {
                        left = confirmFocusRequester
                        right = ignoreFocusRequester
                        up = confirmFocusRequester
                        down = confirmFocusRequester
                    },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.Secondary,
                    focusedContainerColor = NuvioTheme.colors.SecondaryVariant,
                    contentColor = NuvioTheme.colors.OnSecondary,
                    focusedContentColor = NuvioTheme.colors.OnSecondaryVariant
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(
                    text = stringResource(R.string.rewatch_prompt_confirm),
                    fontWeight = FontWeight.SemiBold
                )
            }

            Button(
                onClick = onIgnore,
                modifier = Modifier
                    .focusRequester(ignoreFocusRequester)
                    .focusProperties {
                        left = confirmFocusRequester
                        right = ignoreFocusRequester
                        up = ignoreFocusRequester
                        down = ignoreFocusRequester
                    },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    contentColor = NuvioTheme.colors.TextPrimary,
                    focusedContentColor = NuvioTheme.colors.Primary
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50))
            ) {
                Text(stringResource(R.string.rewatch_prompt_dismiss))
            }
        }
    }
}

/** The pill that says what the last answer did, so an answer is never silent. */
@Composable
private fun RewatchNoticePill(
    notice: RewatchNotice,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(notice) {
        delay(REWATCH_NOTICE_TIMEOUT_MS)
        onDismissed()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(3f),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = NuvioTheme.spacing.xxl)
                .clip(RoundedCornerShape(24.dp))
                .background(NuvioTheme.colors.BackgroundElevated)
                .border(
                    width = NuvioTheme.spacing.hairline,
                    color = NuvioTheme.colors.Border,
                    shape = RoundedCornerShape(24.dp)
                )
                .padding(horizontal = NuvioTheme.spacing.xl, vertical = NuvioTheme.spacing.md)
        ) {
            Text(
                text = notice.kind.message(),
                style = MaterialTheme.typography.labelLarge,
                color = NuvioTheme.colors.TextPrimary
            )
        }
    }
}

@Composable
private fun RewatchNoticeKind.message(): String = stringResource(
    when (this) {
        RewatchNoticeKind.RECORDED -> R.string.rewatch_notice_recorded
        RewatchNoticeKind.NOT_RECORDED -> R.string.rewatch_notice_declined
        RewatchNoticeKind.FAILED -> R.string.rewatch_notice_failed
    }
)
