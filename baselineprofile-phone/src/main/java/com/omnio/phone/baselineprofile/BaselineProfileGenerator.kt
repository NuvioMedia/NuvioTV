package com.omnio.phone.baselineprofile

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
            packageName = "com.omnio.phone.debug", // benchmark build has .debug suffix
            includeInStartupProfile = true
        ) {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.pkg("com.omnio.phone.debug")), 5_000)
            Thread.sleep(3_000)
        }
    }
}
