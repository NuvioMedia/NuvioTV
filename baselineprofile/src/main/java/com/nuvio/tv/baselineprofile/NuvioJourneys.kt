package com.nuvio.tv.baselineprofile

import androidx.test.uiautomator.UiDevice

/** The user journeys that are compiled into the baseline profile and measured by the benchmark. */
internal enum class Journey(val key: String) {
    /** Home screen: vertical rows plus a long horizontal poster scroll. */
    HOME_SCROLL("home-scroll"),

    /** Home screen: rapid focus fling used as the fast-scroll guard rail. */
    FAST_SCROLL("fast-scroll"),

    /** Home to detail to episodes and back. */
    NAVIGATION("navigation"),

    /** Detail screen to player, including the overlay show/hide cycle. */
    PLAYER("player"),

    /** Drawer walk over the library and the settings screens. */
    SETTINGS_LIBRARY("settings-library")
}

/** Outcome of a journey, used to report how much of the journey was actually reachable. */
internal data class JourneyResult(
    val journey: Journey,
    val reachedDetail: Boolean = false,
    val reachedPlayer: Boolean = false,
    val degraded: Boolean = false,
    val note: String = ""
)

/**
 * Runs [journey] on the device, preparation included.
 *
 * Every journey is tolerant: a missing catalog, an absent player or an empty row only marks
 * the journey as degraded instead of throwing, so one slow screen never fails the whole run.
 */
internal fun UiDevice.runJourney(journey: Journey, packageName: String): JourneyResult {
    return try {
        prepareForJourney(packageName)
        performJourney(journey)
    } catch (e: Exception) {
        JourneyResult(journey, degraded = true, note = "exception: ${e.message}")
    }
}

/**
 * Brings the app into the state the journeys expect: content seeded, onboarding skipped,
 * a profile picked and the focus parked on the content grid.
 */
internal fun UiDevice.prepareForJourney(packageName: String) {
    prepareHome(packageName)
    focusContentGrid()
}

/**
 * Runs the measured part of [journey]. The device is expected to be on the home screen with
 * the focus inside the content grid.
 */
internal fun UiDevice.performJourney(journey: Journey): JourneyResult {
    return when (journey) {
        Journey.HOME_SCROLL -> homeScrollJourney()
        Journey.FAST_SCROLL -> fastScrollJourney()
        Journey.NAVIGATION -> navigationJourney()
        Journey.PLAYER -> playerJourney()
        Journey.SETTINGS_LIBRARY -> settingsLibraryJourney()
    }
}

/** Moves focus out of the drawer into the content grid. */
private fun UiDevice.focusContentGrid() {
    pressDPadRight()
    waitForIdle()
    Thread.sleep(300)
}

/**
 * Home scroll: 20+ horizontal poster steps plus a vertical pass over the rows.
 */
private fun UiDevice.homeScrollJourney(): JourneyResult {
    // Horizontal poster scroll, well past 20 items so the lazy rows keep composing.
    dpad("right", steps = 24, delayMs = 130L)
    // Vertical pass over the rows.
    dpad("down", steps = 10, delayMs = 160L)
    dpad("up", steps = 4, delayMs = 160L)
    // One more horizontal sweep on a different row.
    dpad("right", steps = 12, delayMs = 130L)
    waitForIdle()
    Thread.sleep(500)
    return JourneyResult(Journey.HOME_SCROLL)
}

/**
 * Fast scroll: the same horizontal travel without waiting for the UI to settle between the
 * D-pad events, which is the closest equivalent of a fast drag on a touch device.
 */
private fun UiDevice.fastScrollJourney(): JourneyResult {
    val screenWidth = displayWidth
    val screenHeight = displayHeight
    var touched = false
    // Try a real fling first; on a TV build without touch handling this is a no-op.
    if (screenWidth > 0 && screenHeight > 0) {
        touched = runCatching {
            swipe(
                (screenWidth * 0.8).toInt(),
                (screenHeight * 0.5).toInt(),
                (screenWidth * 0.1).toInt(),
                (screenHeight * 0.5).toInt(),
                5
            )
            waitForIdle()
            true
        }.getOrDefault(false)
    }
    Thread.sleep(400)

    // Rapid focus travel without idle waits: the deterministic part of the fast scroll.
    repeat(18) {
        runCatching { pressDPadRight() }
        Thread.sleep(35)
    }
    waitForIdle()
    Thread.sleep(600)

    return JourneyResult(
        journey = Journey.FAST_SCROLL,
        degraded = !touched,
        note = if (touched) "touch fling + rapid d-pad" else "touch fling unavailable, rapid d-pad only"
    )
}

/**
 * Navigation: home to a detail screen, into the episodes list and back to home.
 */
private fun UiDevice.navigationJourney(): JourneyResult {
    val opened = openDetail()
    if (!opened) {
        return JourneyResult(Journey.NAVIGATION, degraded = true, note = "no title could be opened")
    }

    // Walk the detail screen horizontally to reach the episodes section.
    dpad("right", steps = 4, delayMs = 200L)
    if (awaitText(Labels.EPISODES, timeoutMs = 4_000)) {
        focusAndSelect(Labels.EPISODES)
        Thread.sleep(1_500)
    }
    dpad("down", steps = 3, delayMs = 200L)
    dpad("up", steps = 2, delayMs = 200L)

    goBack(steps = 2)
    return JourneyResult(Journey.NAVIGATION, reachedDetail = true)
}

/**
 * Player: opens the detail screen and starts playback.
 *
 * The full stream sources are not available in every environment, so the trailer of the
 * detail screen is used as the playback surface. When neither is reachable the journey
 * degrades to the detail screen and still exercises the overlay show/hide cycle.
 */
private fun UiDevice.playerJourney(): JourneyResult {
    if (!openDetail()) {
        return JourneyResult(Journey.PLAYER, degraded = true, note = "no title could be opened")
    }

    var inPlayer = false
    // Prefer the real playback button, fall back to the trailer player.
    if (awaitText(Labels.PLAY, timeoutMs = 2_000)) {
        focusAndSelect(Labels.PLAY, maxSteps = 8, requireGone = false)
        inPlayer = awaitAnyText(*Labels.PLAYER_MARKERS.toTypedArray(), timeoutMs = 15_000)
    }
    if (!inPlayer && awaitText(Labels.PLAY_TRAILER, timeoutMs = 3_000)) {
        focusAndSelect(Labels.PLAY_TRAILER, maxSteps = 12, requireGone = false)
        inPlayer = awaitAnyText(*Labels.PLAYER_MARKERS.toTypedArray(), timeoutMs = 15_000)
    }

    if (inPlayer) {
        // Let the surface play for a few seconds.
        Thread.sleep(5_000)
        toggleOverlay()
        // Leave the player again.
        goBack(steps = 1)
        return JourneyResult(Journey.PLAYER, reachedDetail = true, reachedPlayer = true)
    }

    // Degraded path: no playable surface. Still capture the overlay show/hide cycle.
    toggleOverlay()
    return JourneyResult(
        journey = Journey.PLAYER,
        reachedDetail = true,
        degraded = true,
        note = "playback unavailable, measured detail overlay instead"
    )
}

/** Shows the player overlay and hides it again. */
private fun UiDevice.toggleOverlay() {
    // Center shows the transport controls.
    runCatching { pressDPadCenter() }
    waitForIdle()
    Thread.sleep(1_500)
    // Center again hides them again.
    runCatching { pressDPadCenter() }
    waitForIdle()
    Thread.sleep(1_000)
}

/**
 * Settings and library walk over the drawer entries.
 */
private fun UiDevice.settingsLibraryJourney(): JourneyResult {
    val libraryOpened = openDrawerItem(Labels.LIBRARY)
    if (libraryOpened) {
        Thread.sleep(2_000)
        dpad("down", steps = 3, delayMs = 200L)
        goBack(steps = 1)
    }

    val settingsOpened = openDrawerItem(Labels.SETTINGS)
    if (settingsOpened) {
        Thread.sleep(2_000)
        dpad("down", steps = 3, delayMs = 200L)
        goBack(steps = 1)
    }

    return JourneyResult(
        journey = Journey.SETTINGS_LIBRARY,
        degraded = !(libraryOpened && settingsOpened),
        note = "library=$libraryOpened settings=$settingsOpened"
    )
}

/**
 * Opens a detail screen by activating the focused poster.
 * Returns false when the home screen has no title to open.
 */
private fun UiDevice.openDetail(): Boolean {
    // The detail screen exposes its own actions, the home screen does not.
    if (awaitAnyText(*Labels.DETAIL_MARKERS.toTypedArray(), timeoutMs = 1_000)) return true

    // Walk into the grid and pick the first focused title.
    repeat(4) {
        pressDPadRight()
        waitForIdle()
        Thread.sleep(250)
    }
    pressDPadCenter()
    waitForIdle()
    return awaitAnyText(*Labels.DETAIL_MARKERS.toTypedArray(), timeoutMs = 12_000)
}

/**
 * Opens a drawer entry by label.
 *
 * The drawer sits on the left edge, so the focus is first pushed left and then walked down
 * until the requested entry owns the focus.
 */
private fun UiDevice.openDrawerItem(label: String): Boolean {
    for (step in 0 until 6) {
        pressDPadLeft()
        waitForIdle()
        Thread.sleep(150)
        if (isFocusOnSidebar()) break
    }
    repeat(10) {
        if (focusedTextEquals(label)) {
            pressDPadCenter()
            waitForIdle()
            Thread.sleep(2_000)
            return true
        }
        pressDPadDown()
        waitForIdle()
        Thread.sleep(200)
    }
    // Fall back to the generic search when the drawer could not be focused directly.
    val selected = focusAndSelect(label, maxSteps = 12)
    if (selected) Thread.sleep(2_000)
    return selected
}

/** True when the focus currently sits on one of the drawer entries. */
private fun UiDevice.isFocusOnSidebar(): Boolean =
    isFocusOn("Home") || isFocusOn("Search") || isFocusOn("Library") || isFocusOn("Settings")

/** True when the currently focused node carries [label] as its text. */
private fun UiDevice.focusedTextEquals(label: String): Boolean = isFocusOn(label)
