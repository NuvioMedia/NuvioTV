package com.nuvio.tv.core.plugin

import android.util.Log

private const val TAG = "TestDiagnostics"

/**
 * Collects diagnostic steps during a scraper test run.
 * Each step is a status line like "DEX loaded: 2 MainAPIs" or "Search: 5 results for 'The Matrix'".
 */
data class TestDiagnostics(
    val steps: MutableList<String> = mutableListOf()
) {
    fun addStep(step: String) {
        steps.add(step)
        Log.d(TAG, step)
    }
}
