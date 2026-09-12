package com.nuvio.tv.updater

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class ApkInstallerTest {
    private val context = mockk<Context>()
    private val file = File("synthetic.apk")
    private val uri = mockk<Uri>()

    @Before fun setup() {
        mockkStatic(FileProvider::class)
        mockkConstructor(Intent::class)
        every { FileProvider.getUriForFile(context, any(), file) } returns uri
        every { anyConstructed<Intent>().setDataAndType(any(), any()) } answers { self as Intent }
        every { anyConstructed<Intent>().addFlags(any()) } answers { self as Intent }
    }
    @After fun cleanup() { unmockkStatic(FileProvider::class); unmockkConstructor(Intent::class) }

    @Test fun missingInstallerIsARecoverableFailure() {
        every { context.startActivity(any()) } throws ActivityNotFoundException("No package installer")
        assertTrue(ApkInstaller.launchInstall(context, file).exceptionOrNull() is ActivityNotFoundException)
    }

    @Test fun installerPermissionFailureIsRecoverable() {
        every { context.startActivity(any()) } throws SecurityException("Installer permission denied")
        assertTrue(ApkInstaller.launchInstall(context, file).exceptionOrNull() is SecurityException)
    }

    @Test fun rejectedFileProviderPathIsRecoverableAndDoesNotLaunchActivity() {
        every { FileProvider.getUriForFile(context, any(), file) } throws IllegalArgumentException("Outside provider roots")
        assertTrue(ApkInstaller.launchInstall(context, file).exceptionOrNull() is IllegalArgumentException)
        verify(exactly = 0) { context.startActivity(any()) }
    }
}
