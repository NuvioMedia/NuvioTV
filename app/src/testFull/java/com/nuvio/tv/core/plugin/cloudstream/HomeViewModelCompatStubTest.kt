package com.nuvio.tv.core.plugin.cloudstream

import com.lagradost.cloudstream3.ui.home.HomeViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeViewModelCompatStubTest {

    @Test
    fun `CloudStream HomeViewModel class is resolvable for plugins`() {
        val clazz = Class.forName("com.lagradost.cloudstream3.ui.home.HomeViewModel")
        assertEquals("com.lagradost.cloudstream3.ui.home.HomeViewModel", clazz.name)
        assertNotNull(clazz.getDeclaredClasses().firstOrNull { it.simpleName == "Companion" })
    }

    @Test
    fun `getResumeWatching returns empty instead of throwing`() = runBlocking {
        val result = HomeViewModel.getResumeWatching()
        assertTrue(result.isNullOrEmpty())
    }
}
