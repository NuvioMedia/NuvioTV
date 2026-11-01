package com.nuvio.tv.baselineprofile

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/**
 * Text labels rendered by the app on the TV surfaces the journeys walk through.
 * Kept in one place so a copy change only needs a single edit.
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

    /** Markers that identify the detail screen. */
    val DETAIL_MARKERS = listOf(
        "Add to library",
        "Mark as watched",
        "Play trailer",
        "Playback unavailable"
    )

    /** Markers that identify the player screen. */
    val PLAYER_MARKERS = listOf(
        "Press back to exit trailer",
        "Pause",
        "Sources",
        "Subtitles"
    )
}

/** Application id of the shipping build, used when the device package cannot be resolved. */
internal const val DEFAULT_TARGET_PACKAGE = "com.nuvio.tv"

/** Application id of this test module, never a valid app-under-test package. */
internal const val TEST_MODULE_PACKAGE = "com.nuvio.tv.baselineprofile"

/**
 * Addon used to seed a fresh install so the home rows are not empty.
 * The app parses `stremio://<host>/<path>` deep links and installs the manifest.
 */
internal const val DEFAULT_ADDON_MANIFEST =
    "stremio://aiometadata.stremio.ru/stremio/f0d1ee5b-7a0a-4ea4-bea7-93c86c452602/manifest.json"

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
 * The `benchmark` build type of `:app` ships with an `applicationIdSuffix`, so the installed
 * package is not always `com.nuvio.tv`. The instrumentation target is used first and the
 * package list is the fallback.
 */
internal fun resolveTargetPackage(device: UiDevice): String {
    val target = runCatching {
        InstrumentationRegistry.getInstrumentation().targetContext.packageName
    }.getOrNull()
    if (!target.isNullOrBlank() && target != TEST_MODULE_PACKAGE) return target

    val installed = runCatching {
        device.executeShellCommand("pm list packages com.nuvio.tv")
            .lineSequence()
            .map { it.trim().removePrefix("package:") }
            .filter { it.startsWith("com.nuvio.tv") && it != TEST_MODULE_PACKAGE }
            .toList()
    }.getOrDefault(emptyList())
    if (installed.isNotEmpty()) return installed.first()

    return DEFAULT_TARGET_PACKAGE
}

/** Waits for any node whose text or content description contains [text]. */
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

/** All nodes carrying [text] as text or content description, exact matches first. */
private fun UiDevice.nodesFor(text: String): List<androidx.test.uiautomator.UiObject2> {
    val exact = runCatching { findObjects(By.text(text)) + findObjects(By.desc(text)) }.getOrDefault(emptyList())
    if (exact.isNotEmpty()) return exact
    return runCatching {
        findObjects(By.textContains(text)) + findObjects(By.descContains(text))
    }.getOrDefault(emptyList())
}

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
 * When the focus cannot be parked on the label the element is clicked directly, which keeps
 * the journey working on the emulator.
 */
internal fun UiDevice.focusAndSelect(label: String, maxSteps: Int = 24, requireGone: Boolean = true): Boolean {
    if (!awaitText(label, timeoutMs = 10_000)) return false
    var selected = false
    repeat(maxSteps) { step ->
        if (isFocusOn(label) || isFocusedLabel(label, Pattern.compile(Pattern.quote(label)))) {
            pressDPadCenter()
            waitForIdle()
            Thread.sleep(1_200)
            selected = true
            // Treat the step as done only once the label is gone, so a mis-selection keeps searching.
            if (!requireGone) return true
            if (wait(Until.gone(By.textContains(label)), 4_000) == true) return true
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
 * Seeds the addon catalog through the app deep link so a fresh install has content.
 * Failures are swallowed: an empty catalog must not abort the whole run.
 */
internal fun UiDevice.seedAddonCatalog(packageName: String) {
    val manifest = instrumentationArg("addonManifestUrl", DEFAULT_ADDON_MANIFEST) ?: return
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
 */
internal fun UiDevice.prepareHome(packageName: String, seedAddon: Boolean = false) {
    if (seedAddon) seedAddonCatalog(packageName)

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
