package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text as CoreText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioColors

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayMessage = buildErrorStatePresentation(
        message = message,
        strings = rememberErrorStateStrings()
    ).annotated

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CoreText(
            text = displayMessage,
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioColors.TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                contentColor = NuvioColors.TextPrimary,
                focusedContainerColor = NuvioColors.FocusBackground,
                focusedContentColor = NuvioColors.Primary
            ),
            shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
        ) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

internal fun formatErrorStateMessage(message: String): String {
    return buildErrorStatePresentation(
        message = message,
        strings = defaultErrorStateStrings()
    ).text
}

internal fun buildErrorStateAnnotatedMessage(message: String): AnnotatedString {
    return buildErrorStatePresentation(
        message = message,
        strings = defaultErrorStateStrings()
    ).annotated
}

private data class ErrorStatePresentation(
    val text: String,
    val boldRanges: List<IntRange>
) {
    val annotated: AnnotatedString
        get() = buildAnnotatedString {
            append(text)
            boldRanges.forEach { range ->
                addStyle(
                    style = SpanStyle(
                        fontWeight = FontWeight.Bold,
                        color = NuvioColors.TextPrimary
                    ),
                    start = range.first,
                    end = range.last + 1
                )
            }
        }
}

private data class ErrorStateStrings(
    val genericIssue: String,
    val possibleFixFormat: String,
    val retrySoonFix: String,
    val noMetadataAnyAddonIssue: String,
    val noMetadataAnyAddonFix: String,
    val noSupportedMetadataPrefix: String,
    val noAddonForIdPrefix: String,
    val noSupportedMetadataFix: String,
    val triedMetaAddonsPrefix: String,
    val triedMetaAddonsFix: String,
    val missingMetadataForIdIssue: String,
    val missingMetadataForIdFix: String,
    val addonUnreachableIssue: String,
    val addonUnreachableFix: String,
    val addonConnectionFailedIssue: String,
    val addonConnectionFailedFix: String,
    val addonTimeoutIssue: String,
    val addonTimeoutFix: String,
    val addonCleartextIssue: String,
    val addonCleartextFix: String,
    val addonGenericFix: String,
    val addonIssueTemplate: String,
    val metaNotFound: String
)

@Composable
private fun rememberErrorStateStrings(): ErrorStateStrings {
    return ErrorStateStrings(
        genericIssue = stringResource(R.string.error_state_generic_issue),
        possibleFixFormat = stringResource(R.string.error_state_possible_fix, "%1\$s"),
        retrySoonFix = stringResource(R.string.error_state_fix_retry_soon),
        noMetadataAnyAddonIssue = stringResource(R.string.error_state_issue_no_metadata_any_addon),
        noMetadataAnyAddonFix = stringResource(R.string.error_state_fix_no_metadata_any_addon),
        noSupportedMetadataPrefix = stringResource(R.string.error_state_prefix_no_supported_metadata),
        noAddonForIdPrefix = stringResource(R.string.error_state_prefix_no_addon_for_id),
        noSupportedMetadataFix = stringResource(R.string.error_state_fix_no_supported_metadata),
        triedMetaAddonsPrefix = stringResource(R.string.error_state_prefix_tried_meta_addons),
        triedMetaAddonsFix = stringResource(R.string.error_state_fix_tried_meta_addons),
        missingMetadataForIdIssue = stringResource(R.string.error_state_issue_missing_metadata_for_id),
        missingMetadataForIdFix = stringResource(R.string.error_state_fix_missing_metadata_for_id),
        addonUnreachableIssue = stringResource(R.string.error_state_issue_addon_unreachable),
        addonUnreachableFix = stringResource(R.string.error_state_fix_addon_unreachable),
        addonConnectionFailedIssue = stringResource(R.string.error_state_issue_addon_connection_failed),
        addonConnectionFailedFix = stringResource(R.string.error_state_fix_addon_connection_failed),
        addonTimeoutIssue = stringResource(R.string.error_state_issue_addon_timeout),
        addonTimeoutFix = stringResource(R.string.error_state_fix_addon_timeout),
        addonCleartextIssue = stringResource(R.string.error_state_issue_addon_cleartext),
        addonCleartextFix = stringResource(R.string.error_state_fix_addon_cleartext),
        addonGenericFix = stringResource(R.string.error_state_fix_addon_generic),
        addonIssueTemplate = stringResource(R.string.error_state_addon_issue_template, "%1\$s", "%2\$s"),
        metaNotFound = stringResource(R.string.error_meta_not_found)
    )
}

private fun defaultErrorStateStrings(): ErrorStateStrings {
    return ErrorStateStrings(
        genericIssue = "Something went wrong.",
        possibleFixFormat = "Possible fix: %s",
        retrySoonFix = "retry in a moment.",
        noMetadataAnyAddonIssue = "This id could not load details because none of the installed addons returned metadata.",
        noMetadataAnyAddonFix = "try another addon, disable \"Prefer external meta addon\" in Layout settings, or confirm an installed addon supports this id.",
        noSupportedMetadataPrefix = "No installed addon supports metadata for ",
        noAddonForIdPrefix = "No installed addon could provide metadata for id=",
        noSupportedMetadataFix = "install or update an addon that supports this content type, then retry.",
        triedMetaAddonsPrefix = "Tried meta addons:",
        triedMetaAddonsFix = "install an addon that supports this id, or reconfigure/update the addon and retry.",
        missingMetadataForIdIssue = "does not have metadata for this id",
        missingMetadataForIdFix = "open this id from a different addon, disable \"Prefer external meta addon\", or check that the addon supports this id.",
        addonUnreachableIssue = "could not be reached",
        addonUnreachableFix = "check your internet connection, verify the addon URL still works, then retry.",
        addonConnectionFailedIssue = "rejected the connection",
        addonConnectionFailedFix = "make sure the addon server is online and reachable, then retry.",
        addonTimeoutIssue = "took too long to respond",
        addonTimeoutFix = "retry in a moment, or try a different addon if this keeps happening.",
        addonCleartextIssue = "uses an insecure HTTP connection that Android blocked",
        addonCleartextFix = "switch the addon URL to HTTPS or update the addon configuration.",
        addonGenericFix = "retry, update or reinstall the addon, or try a different addon.",
        addonIssueTemplate = "%s: %s.",
        metaNotFound = "Meta not found"
    )
}

private fun buildErrorStatePresentation(
    message: String,
    strings: ErrorStateStrings
): ErrorStatePresentation {
    val trimmed = message.trim()
    if (trimmed.isBlank()) {
        return ErrorStatePresentation(
            text = withPossibleFix(strings.genericIssue, strings.retrySoonFix, strings),
            boldRanges = emptyList()
        )
    }

    val addonName = trimmed.substringBefore(':', missingDelimiterValue = "").trim()
        .takeIf { it.isNotBlank() && it.length < trimmed.length }
    val reason = trimmed.substringAfter(':', missingDelimiterValue = trimmed).trim()

    fun messageWithFix(issue: String, fix: String): String {
        return withPossibleFix(issue, fix, strings)
    }

    val displayMessage = when {
        trimmed.equals(strings.metaNotFound, ignoreCase = true) ||
            trimmed.startsWith(strings.noAddonForIdPrefix, ignoreCase = true) -> {
            messageWithFix(
                issue = strings.noMetadataAnyAddonIssue,
                fix = strings.noMetadataAnyAddonFix
            )
        }

        trimmed.startsWith(strings.noSupportedMetadataPrefix, ignoreCase = true) -> {
            messageWithFix(
                issue = trimmed,
                fix = strings.noSupportedMetadataFix
            )
        }

        trimmed.startsWith(strings.triedMetaAddonsPrefix, ignoreCase = true) -> {
            messageWithFix(
                issue = trimmed,
                fix = strings.triedMetaAddonsFix
            )
        }

        reason.equals("returned no metadata for this id", ignoreCase = true) ||
            reason.equals("metadata was not found", ignoreCase = true) -> {
            messageWithFix(
                issue = buildAddonIssue(addonName, strings.missingMetadataForIdIssue, strings),
                fix = strings.missingMetadataForIdFix
            )
        }

        reason.contains("could not reach the addon server", ignoreCase = true) ||
            trimmed.contains("Unable to resolve host", ignoreCase = true) -> {
            messageWithFix(
                issue = buildAddonIssue(addonName, strings.addonUnreachableIssue, strings),
                fix = strings.addonUnreachableFix
            )
        }

        reason.contains("connection to the addon failed", ignoreCase = true) ||
            trimmed.contains("Failed to connect", ignoreCase = true) -> {
            messageWithFix(
                issue = buildAddonIssue(addonName, strings.addonConnectionFailedIssue, strings),
                fix = strings.addonConnectionFailedFix
            )
        }

        reason.contains("timed out", ignoreCase = true) -> {
            messageWithFix(
                issue = buildAddonIssue(addonName, strings.addonTimeoutIssue, strings),
                fix = strings.addonTimeoutFix
            )
        }

        reason.contains("insecure HTTP connection blocked by Android", ignoreCase = true) ||
            trimmed.contains("CLEARTEXT communication", ignoreCase = true) -> {
            messageWithFix(
                issue = buildAddonIssue(addonName, strings.addonCleartextIssue, strings),
                fix = strings.addonCleartextFix
            )
        }

        addonName != null -> {
            messageWithFix(
                issue = buildAddonIssue(addonName, reason, strings),
                fix = strings.addonGenericFix
            )
        }

        else -> trimmed
    }

    val boldRanges = linkedSetOf<IntRange>()

    val aggregatePrefix = "${strings.triedMetaAddonsPrefix} "
    if (displayMessage.startsWith(aggregatePrefix)) {
        val listStart = aggregatePrefix.length
        displayMessage.indexOf('.', startIndex = listStart)
            .takeIf { it > listStart }
            ?.let { boldRanges += listStart until it }
    }

    displayMessage.indexOf(':')
        .takeIf {
            it > 0 &&
                !displayMessage.startsWith(strings.triedMetaAddonsPrefix, ignoreCase = true) &&
                !displayMessage.startsWith(strings.noSupportedMetadataPrefix, ignoreCase = true)
        }
        ?.let { boldRanges += 0 until it }

    Regex("""id=[^\s.,)]+""").findAll(displayMessage).forEach { match ->
        boldRanges += match.range.first..match.range.last
    }

    return ErrorStatePresentation(
        text = displayMessage,
        boldRanges = boldRanges.toList()
    )
}

private fun withPossibleFix(issue: String, fix: String, strings: ErrorStateStrings): String {
    return issue + "\n\n" + strings.possibleFixFormat.format(fix)
}

private fun buildAddonIssue(addonName: String?, issue: String, strings: ErrorStateStrings): String {
    val normalizedIssue = issue.replaceFirstChar { char ->
        if (char.isLowerCase()) char.titlecase() else char.toString()
    }
    return if (addonName.isNullOrBlank()) {
        normalizedIssue
    } else {
        strings.addonIssueTemplate.format(addonName, normalizedIssue)
    }
}
