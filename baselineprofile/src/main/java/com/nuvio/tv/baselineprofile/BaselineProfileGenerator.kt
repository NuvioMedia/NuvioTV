package com.nuvio.tv.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        rule.collect(
            packageName = "com.nuvio.tv.debug", // benchmark build has .debug suffix
            includeInStartupProfile = true
        ) {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.pkg("com.nuvio.tv.debug")), 5_000)
            Thread.sleep(3_000)
        }
    }
}
