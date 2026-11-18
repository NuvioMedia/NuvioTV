package com.nuvio.tv.core.plugin.cloudstream

import android.app.Activity
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudStreamCompatStubTest {

    @Test
    fun `MainActivity class is resolvable for plugin instanceof checks`() {
        val clazz = Class.forName("com.lagradost.cloudstream3.MainActivity")
        assertEquals("com.lagradost.cloudstream3.MainActivity", clazz.name)
    }

    @Test
    fun `MainActivity stub is not an Activity so instanceof stays false`() {
        assertFalse(Activity::class.java.isAssignableFrom(MainActivity::class.java))
    }

    @Test
    fun `CommonActivity exposes getActivity and showToast overloads used by plugins`() {
        assertNull(CommonActivity.getActivity())

        assertNotNull(
            CommonActivity.javaClass.getMethod(
                "showToast",
                Activity::class.java,
                String::class.java,
                Integer::class.java
            )
        )
        assertNotNull(
            CommonActivity.javaClass.getMethod(
                "showToast\$default",
                CommonActivity.javaClass,
                String::class.java,
                Integer::class.java,
                Int::class.javaPrimitiveType,
                Any::class.java
            )
        )
        assertTrue(CommonActivity.javaClass.methods.any { it.name == "getActivity" })
    }

    @Test
    fun `CommonActivity showToast calls are safe no-ops`() {
        CommonActivity.showToast("hello")
        CommonActivity.showToast(null, "hello", null)
    }
}
