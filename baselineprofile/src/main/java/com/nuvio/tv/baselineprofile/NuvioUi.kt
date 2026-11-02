package com.nuvio.tv.baselineprofile

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/**
 * Text labels rendered by the app on the TV surfaces the journeys walk through.
 * Kept in one place so a copy change only needs a single edit.
 *
 * The labels are matched **exactly** (see [awaitExactText] and [awaitExactDesc]): a substring
 * match made `Play` select the `Play trailer` button and made the `Sources`/`Subtitles`
 * headings of the settings screen look like the player overlay.
 */
internal object Labels {
    const val ONBOARDING_SKIP = "Continue without account"
    const val ONBOARDING_ADVANCED = "Advanced"
    const val ONBOARDING_ESSENTIAL = "Essential"
    const val ONBOARDING_CONTINUE = "Continue"
    const val PROFILE_FIRST = "Profile 1"
    const val PROFILE_SCREEN = "watching?"
    const val PLAY = "Play"
    const val PLAY_TRAILER = "Play trailer"
    const val EPISODES = "Episodes"
    const val LIBRARY = "Library"
    const val SETTINGS = "Settings"

    /** Exact texts that identify the detail screen. */
    val DETAIL_MARKERS = listOf(
        "Add to library",
        "Mark as watched",
        "Play trailer",
        "Playback unavailable"
    )

    /** Exact texts of the player overlay (the transport bar of the trailer player). */
    val PLAYER_TEXT_MARKERS = listOf(
        "Press back to exit trailer"
    )

    /**
     * Exact content descriptions of the player overlay.
     *
     * These are matched as content descriptions only: the settings screens carry the same words
     * as plain text, so a text match would report the player on a screen that has no player.
     */
    val PLAYER_DESC_MARKERS = listOf(
        "Pause",
        "Sources",
        "Subtitles"
    )

    /** Title of the stream picker, which the player opens when a source has to be picked. */
    const val SOURCES_TITLE = "Sources"
}

/** Application id of the release build of the app, the app under test of the profile collection. */
internal const val DEFAULT_TARGET_PACKAGE = "com.nuvio.tv"

/** Application id of the benchmark build of the app, it carries the `.debug` suffix. */
internal const val BENCHMARK_TARGET_PACKAGE = "com.nuvio.tv.debug"

/** Application id of this test module, never a valid app-under-test package. */
internal const val TEST_MODULE_PACKAGE = "com.nuvio.tv.baselineprofile"

/**
 * Runner argument the baseline profile gradle plugin fills with the application id of the
 * tested APK. It is the authoritative source of the package under test.
 */
internal const val TARGET_PACKAGE_ARGUMENT = "androidx.benchmark.targetPackageName"

private const val LOG_TAG = "NuvioBaselineProfile"

/** Default time budget for a single UI expectation before a journey gives up. */
internal const val UI_TIMEOUT_MS = 8_000L

/** Longer budget used while waiting for remote catalog content to land. */
internal const val CONTENT_TIMEOUT_MS = 20_000L

/** The device the instrumentation runs on. */
internal fun uiDevice(): UiDevice =
    UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

/** Reads an instrumentation argument, falling back to [default] when it was not supplied. */
internal fun instrumentationArg(name: String, default: String? = null): String? =
    InstrumentationRegistry.getArguments().getString(name)?.takeIf { it.isNotBlank() } ?: default

/**
 * Resolves the package id of the app under test.
 *
 * The instrumentation of this module is *self-instrumenting*: the target context of the
 * instrumentation is the benchmark APK itself (`com.nuvio.tv.baselineprofile`), never the app
 * under test. The target context is therefore only usable when it names a real app.
 *
 * The resolution is deterministic, the order is:
 * 1. the `androidx.benchmark.targetPackageName` runner argument, which the baseline profile
 *    gradle plugin fills with the application id of the tested APK,
 * 2. the target context of the instrumentation, when it is not the test module itself,
 * 3. the installed package that matches the package the caller expects for the variant under
 *    test (the release build for the profile collection, the `.debug` build for the benchmarks),
 * 4. the installed package that carries the `.debug` suffix of the benchmark build type,
 * 5. the first installed package in sorted order.
 *
 * @param expected the package id the caller knows the app under test has.
 */
internal fun resolveTargetPackage(device: UiDevice, expected: String): String {
    val target = runCatching {
        InstrumentationRegistry.getInstrumentation().targetContext.packageName
    }.getOrNull()
    if (!target.isNullOrBlank() && target != TEST_MODULE_PACKAGE) {
        Log.i(LOG_TAG, "Resolved target package from the instrumentation target: $target")
        return target
    }

    val pluginTarget = instrumentationArg(TARGET_PACKAGE_ARGUMENT)
    if (!pluginTarget.isNullOrBlank() && pluginTarget != TEST_MODULE_PACKAGE) {
        Log.i(LOG_TAG, "Resolved target package from $TARGET_PACKAGE_ARGUMENT: $pluginTarget")
        return pluginTarget
    }

    val installed = runCatching {
        device.executeShellCommand("pm list packages com.nuvio.tv")
            .lineSequence()
            .map { it.trim().removePrefix("package:") }
            .filter { it.startsWith("com.nuvio.tv") && it != TEST_MODULE_PACKAGE }
            .sorted()
            .toList()
    }.getOrDefault(emptyList())

    val resolved = installed.firstOrNull { it == expected }
        ?: installed.firstOrNull { it == BENCHMARK_TARGET_PACKAGE }
        ?: installed.firstOrNull { it.endsWith(".debug") }
        ?: installed.firstOrNull()
        ?: DEFAULT_TARGET_PACKAGE
    Log.i(LOG_TAG, "Resolved target package: $resolved (expected: $expected, installed: $installed)")
    return resolved
}

/** Waits for a node whose text or content description contains [text]. */
internal fun UiDevice.awaitText(text: String, timeoutMs: Long = UI_TIMEOUT_MS): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val found = runCatching {
            findObject(By.textContains(text)) != null || findObject(By.descContains(text)) != null
        }.getOrDefault(false)
        if (found) return true
        Thread.sleep(150)
    }
    return false
}

/** Waits for a node whose text or content description contains one of [texts]. */
internal fun UiDevice.awaitAnyText(vararg texts: String, timeoutMs: Long = UI_TIMEOUT_MS): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (texts.any { awaitText(it, timeoutMs = 500L) }) return true
    }
    return false
}

/**
 * Waits for a node whose text is exactly [text].
 *
 * An exact match is what keeps `Play` from selecting `Play trailer` or `Playback unavailable`.
 */
internal fun UiDevice.awaitExactText(text: String, timeoutMs: Long = UI_TIMEOUT_MS): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val found = runCatching { findObject(By.text(text)) != null }.getOrDefault(false)
        if (found) return true
        Thread.sleep(150)
    }
    return false
}

/** Waits for a node whose content description is exactly [desc]. */
internal fun UiDevice.awaitExactDesc(desc: String, timeoutMs: Long = UI_TIMEOUT_MS): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val found = runCatching { findObject(By.desc(desc)) != null }.getOrDefault(false)
        if (found) return true
        Thread.sleep(150)
    }
    return false
}

/** Waits for a node whose text is exactly one of [texts]. */
internal fun UiDevice.awaitAnyExactText(vararg texts: String, timeoutMs: Long = UI_TIMEOUT_MS): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val found = runCatching {
            texts.any { findObject(By.text(it)) != null }
        }.getOrDefault(false)
        if (found) return true
        Thread.sleep(150)
    }
    return false
}

/** Waits for a node whose content description is exactly one of [descs]. */
internal fun UiDevice.awaitAnyExactDesc(vararg descs: String, timeoutMs: Long = UI_TIMEOUT_MS): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val found = runCatching {
            descs.any { findObject(By.desc(it)) != null }
        }.getOrDefault(false)
        if (found) return true
        Thread.sleep(150)
    }
    return false
}

/**
 * All nodes carrying [label] as text or as content description.
 *
 * Only exact matches are returned: a substring match is what made the journeys select the
 * wrong control (for example `Play` on `Play trailer`).
 */
private fun UiDevice.nodesFor(label: String): List<androidx.test.uiautomator.UiObject2> =
    runCatching { findObjects(By.text(label)) + findObjects(By.desc(label)) }.getOrDefault(emptyList())

/** True when a node carrying [label] currently owns the focus. */
internal fun UiDevice.isFocusedLabel(label: String, pattern: Pattern): Boolean {
    return try {
        val focused = findObject(By.focused(true)) ?: return false
        if (focused.text?.equals(label, ignoreCase = true) == true) return true
        if (focused.contentDescription?.equals(label, ignoreCase = true) == true) return true
        // Require the focused node to be the clickable control itself so a large parent
        // container is not mistaken for the button.
        focused.isClickable && focused.findObject(By.text(pattern)) != null
    } catch (e: StaleObjectException) {
        false
    }
}

/**
 * True when the focused element is the control that carries [label].
 *
 * Compose often renders the label in a child node while the focus sits on a clickable
 * sibling, so the bounds of the focused element are compared with the bounds of the label.
 */
internal fun UiDevice.isFocusOn(label: String): Boolean {
    return try {
        val focused = findObject(By.focused(true)) ?: return false
        if (focused.text?.equals(label, ignoreCase = true) == true) return true
        if (focused.contentDescription?.equals(label, ignoreCase = true) == true) return true

        val target = nodesFor(label).firstOrNull() ?: return false
        val focusedBounds = focused.visibleBounds
        val targetBounds = target.visibleBounds
        if (focusedBounds.isEmpty || targetBounds.isEmpty) return false
        focusedBounds.contains(targetBounds.centerX(), targetBounds.centerY())
    } catch (e: StaleObjectException) {
        false
    }
}

/**
 * Moves D-pad focus until the control carrying [label] is focused and then selects it.
 * TV devices have no touch input, so every journey has to drive the focus engine.
 *
 * The label is matched exactly, so `Play` never selects the `Play trailer` button.
 *
 * When the focus cannot be parked on the label the element is clicked directly, which keeps
 * the journey working on the emulator.
 */
internal fun UiDevice.focusAndSelect(label: String, maxSteps: Int = 24, requireGone: Boolean = true): Boolean {
    if (!awaitExactText(label, timeoutMs = 10_000) &&
        !awaitExactDesc(label, timeoutMs = 1_000)
    ) {
        return false
    }
    var selected = false
    repeat(maxSteps) { step ->
        if (isFocusOn(label) || isFocusedLabel(label, Pattern.compile(Pattern.quote(label)))) {
            pressDPadCenter()
            waitForIdle()
            Thread.sleep(1_200)
            selected = true
            // Treat the step as done only once the label is gone, so a mis-selection keeps searching.
            if (!requireGone) return true
            if (wait(Until.gone(By.text(label)), 4_000) == true) return true
        }
        // Alternate directions so both vertical lists and side-by-side cards are reachable.
        if (step % 2 == 0) pressDPadDown() else pressDPadRight()
        waitForIdle()
        Thread.sleep(220)
    }
    if (!selected) {
        // Fall back to a direct activation of the element.
        val target = nodesFor(label).firstOrNull()
        if (target != null) {
            runCatching { target.click() }
            waitForIdle()
            Thread.sleep(1_200)
            selected = true
        }
    }
    return selected
}

/** Presses a D-pad direction [steps] times, pausing long enough for the UI to settle. */
internal fun UiDevice.dpad(direction: String, steps: Int, delayMs: Long = 140L) {
    repeat(steps) {
        when (direction) {
            "right" -> pressDPadRight()
            "left" -> pressDPadLeft()
            "down" -> pressDPadDown()
            "up" -> pressDPadUp()
            else -> pressDPadCenter()
        }
        waitForIdle()
        Thread.sleep(delayMs)
    }
}

/**
 * Runs [block] with the device locale pinned to `en-US`.
 *
 * The labels of the journeys are the English ones, so a device in another language degrades
 * every label lookup. The generator and the benchmark share this helper, otherwise only the
 * collection would run on an English device.
 */
internal fun UiDevice.withEnglishLocale(block: () -> Unit) {
    val originalLocale = runCatching {
        executeShellCommand("getprop persist.sys.locale").trim()
    }.getOrDefault("")
    runCatching { executeShellCommand("setprop persist.sys.locale en-US") }
    runCatching { executeShellCommand("settings put system system_locales en-US") }
    try {
        block()
    } finally {
        if (originalLocale.isNotBlank()) {
            runCatching { executeShellCommand("setprop persist.sys.locale $originalLocale") }
            runCatching { executeShellCommand("settings put system system_locales $originalLocale") }
        }
    }
}

/**
 * Seeds the addon catalog through the app deep link, so a fresh install has content.
 *
 * The manifest comes from the `addonManifestUrl` runner argument and there is no default:
 * a seeded catalog needs the addon of the operator, a hardcoded manifest would leak a
 * personal configuration into the repository. Failures are swallowed, an empty catalog must
 * not abort the whole run.
 */
internal fun UiDevice.seedAddonCatalog(packageName: String) {
    val manifest = instrumentationArg("addonManifestUrl") ?: return
    runCatching {
        executeShellCommand(
            "am start -a android.intent.action.VIEW -d '$manifest' $packageName"
        )
    }
    Thread.sleep(2_000)
}

/**
 * Brings the app to a usable home screen: skips onboarding, picks the first profile and
 * waits for the home screen to settle. Every step is best effort so a slow catalog only
 * costs time instead of failing the run.
 *
 * The catalog is seeded through the `addonManifestUrl` runner argument when one is
 * supplied. There is no default: a seeded catalog needs the addon of the operator, a
 * hardcoded one would leak a personal configuration into the repository.
 */
internal fun UiDevice.prepareHome(packageName: String) {
    seedAddonCatalog(packageName)

    // The drawer entries only exist once the setup is done, so this is the cheap probe.
    val alreadyConfigured = awaitAnyText(Labels.LIBRARY, Labels.SETTINGS, timeoutMs = 2_000)

    if (!alreadyConfigured) {
        // First run: account step.
        if (awaitText(Labels.ONBOARDING_SKIP, timeoutMs = 5_000)) {
            focusAndSelect(Labels.ONBOARDING_SKIP)
        }
        // First run: experience step (advanced or essential).
        val experience = awaitAnyText(
            Labels.ONBOARDING_ADVANCED,
            Labels.ONBOARDING_ESSENTIAL,
            timeoutMs = 8_000
        )
        if (experience) {
            // Essential keeps the setup short and lands directly on the home screen.
            focusAndSelect(Labels.ONBOARDING_ESSENTIAL, requireGone = false)
            Thread.sleep(1_500)
            // First run: layout selection step.
            if (awaitText(Labels.ONBOARDING_CONTINUE, timeoutMs = 4_000)) {
                focusAndSelect(Labels.ONBOARDING_CONTINUE)
            }
        }
        waitForIdle()

        // Profile picker shown on some installs.
        if (awaitText(Labels.PROFILE_SCREEN, timeoutMs = 5_000)) {
            focusAndSelect(Labels.PROFILE_FIRST)
        }
        waitForIdle()
    }

    // Wait for the app to own the screen and for the first row to land.
    wait(Until.hasObject(By.pkg(packageName)), 10_000)
    awaitAnyText(Labels.LIBRARY, Labels.SETTINGS, timeoutMs = CONTENT_TIMEOUT_MS)
    waitForIdle()
    Thread.sleep(2_000)
}

/** Presses back and waits for the app to settle. */
internal fun UiDevice.goBack(steps: Int = 1) {
    repeat(steps) {
        runCatching { pressBack() }
        waitForIdle()
        Thread.sleep(700)
    }
}
