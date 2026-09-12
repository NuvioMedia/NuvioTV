package com.nuvio.tv.updater

import android.content.Context
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.R
import com.nuvio.tv.updater.model.AppUpdate
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val folder = TemporaryFolder()
    private val repository = mockk<UpdateRepository>()
    private val preferences = mockk<UpdatePreferences>(relaxed = true)
    private val downloader = mockk<ApkDownloader>()
    private val context = mockk<Context>()
    private val stable = AppUpdate("v999.0.0", "Stable", "", null, "app.apk", "https://example.invalid/stable.apk", 3)
    private val beta = stable.copy(tag = "v1000.0.0-beta.1", assetUrl = "https://example.invalid/beta.apk")
    private data class Pending(val file: File, val progress: (Long, Long?) -> Unit, val continuation: Continuation<Result<File>>)
    private val pending = mutableListOf<Pending>()

    @Before fun setup() {
        every { context.cacheDir } returns folder.root
        every { context.getString(any()) } answers { "localized-${firstArg<Int>()}" }
        every { preferences.updateBannerEnabled } returns flowOf(false)
        every { preferences.ignoredTag } returns flowOf(null)
        coEvery { preferences.getOrInitializeUpdateChannel() } returns UpdateChannel.STABLE
        coEvery { repository.getLatestUpdate(any()) } answers {
            Result.success(if (firstArg<UpdateChannel>() == UpdateChannel.BETA) beta else stable)
        }
        coEvery { downloader.download(any(), any(), any(), any()) } coAnswers {
            val file = secondArg<File>()
            val progress = arg<(Long, Long?) -> Unit>(3)
            suspendCoroutine { continuation -> pending += Pending(file, progress, continuation) }
        }
        mockkObject(ApkInstaller)
        every { ApkInstaller.canRequestPackageInstalls(any()) } returns Result.success(false)
        every { ApkInstaller.launchInstall(any(), any()) } returns Result.success(Unit)
        every { ApkInstaller.openUnknownSourcesSettings(any()) } returns Result.success(Unit)
    }
    @After fun cleanup() { unmockkObject(ApkInstaller) }

    private fun create() = UpdateViewModel(context, repository, preferences, downloader)
    private fun complete(attempt: Pending): File {
        attempt.file.parentFile!!.mkdirs()
        attempt.file.writeText("apk")
        attempt.continuation.resume(Result.success(attempt.file))
        return attempt.file
    }

    @Test fun duplicateClickAndCheckCannotResetActiveDownload() = runTest {
        val model = create(); runCurrent()
        model.checkForUpdates(true, false); runCurrent()
        model.downloadUpdate(); model.downloadUpdate(); runCurrent()
        pending.single().progress(1, 3)
        model.checkForUpdates(true, true); runCurrent()
        assertTrue(model.uiState.value.isDownloading)
        assertEquals(1f / 3f, model.uiState.value.downloadProgress!!, 0.001f)
        coVerify(exactly = 1) { downloader.download(any(), any(), any(), any()) }
        coVerify(exactly = 1) { repository.getLatestUpdate(any()) }
        complete(pending.single()); runCurrent()
        assertFalse(model.uiState.value.isDownloading)
        assertTrue(model.uiState.value.showUnknownSourcesDialog)
    }

    @Test fun channelChangeRejectsOldProgressCompletionAndInstallation() = runTest {
        val model = create(); runCurrent()
        model.checkForUpdates(true, false); runCurrent()
        model.downloadUpdate(); runCurrent()
        val old = pending.single()
        model.setUpdateChannel(UpdateChannel.BETA); runCurrent()
        model.checkForUpdates(true, false); runCurrent()
        model.downloadUpdate(); runCurrent()
        val current = pending.last()
        current.progress(1, 3)
        old.progress(3, 3)
        val oldFile = complete(old); runCurrent()
        assertFalse(oldFile.exists())
        assertEquals(beta, model.uiState.value.update)
        assertTrue(model.uiState.value.isDownloading)
        assertEquals(1f / 3f, model.uiState.value.downloadProgress!!, 0.001f)
        assertNull(model.uiState.value.downloadedApkPath)
        verify(exactly = 0) { ApkInstaller.canRequestPackageInstalls(any()) }
        complete(current); runCurrent()
        assertEquals(current.file.absolutePath, model.uiState.value.downloadedApkPath)
        assertNotEquals(old.file, current.file)
    }

    @Test fun cancelFromBannerKeepsRetryAndRejectsLateCompletion() = runTest {
        val model = create(); runCurrent()
        model.checkForUpdates(true, false); runCurrent()
        model.downloadUpdate(); runCurrent()
        model.dismissBanner()
        assertFalse(model.uiState.value.isDownloading)
        assertTrue(model.uiState.value.showBanner)
        val file = complete(pending.single()); runCurrent()
        assertFalse(file.exists())
        assertNull(model.uiState.value.downloadedApkPath)
        verify(exactly = 0) { ApkInstaller.canRequestPackageInstalls(any()) }
        coVerify(exactly = 0) { preferences.setIgnoredTag(any()) }
    }

    @Test fun disablingNotificationsKeepsActiveProgressAndCancelAccessible() = runTest {
        val model = create(); runCurrent()
        model.checkForUpdates(true, false); runCurrent()
        model.downloadUpdate(); runCurrent()
        model.setUpdateBannerEnabled(false); runCurrent()
        assertTrue(model.uiState.value.isDownloading)
        assertTrue(model.uiState.value.showBanner)
        assertFalse(model.uiState.value.updateBannerEnabled)
        model.dismissBanner()
        complete(pending.single()); runCurrent()
        assertFalse(model.uiState.value.isDownloading)
        assertNull(model.uiState.value.downloadedApkPath)
    }

    @Test fun lateCancelledRefreshCannotReplaceActiveDownload() = runTest {
        val model = create(); runCurrent()
        model.checkForUpdates(true, false); runCurrent()
        var lateCheck: Continuation<Result<AppUpdate>>? = null
        coEvery { repository.getLatestUpdate(any()) } coAnswers { suspendCoroutine { lateCheck = it } }
        model.checkForUpdates(true, false); runCurrent()
        assertTrue(model.uiState.value.isChecking)
        model.downloadUpdate(); runCurrent()
        lateCheck!!.resume(Result.success(beta)); runCurrent()
        assertEquals(stable, model.uiState.value.update)
        assertTrue(model.uiState.value.isDownloading)
        assertFalse(model.uiState.value.isChecking)
        complete(pending.single()); runCurrent()
    }

    @Test fun channelPreferenceWritesFinishInLastRequestedOrder() = runTest {
        val firstWrite = kotlinx.coroutines.CompletableDeferred<Unit>()
        var saved: UpdateChannel? = null
        coEvery { preferences.setUpdateChannel(any()) } coAnswers {
            val requested = firstArg<UpdateChannel>()
            if (requested == UpdateChannel.BETA) firstWrite.await()
            saved = requested
        }
        val model = create(); runCurrent()
        model.setUpdateChannel(UpdateChannel.BETA); runCurrent()
        model.setUpdateChannel(UpdateChannel.STABLE); runCurrent()
        assertNull(saved)
        firstWrite.complete(Unit); runCurrent()
        assertEquals(UpdateChannel.STABLE, saved)
        assertEquals(UpdateChannel.STABLE, model.uiState.value.updateChannel)
    }

    @Test fun sameTagWithChangedAssetInvalidatesDownloadedApk() = runTest {
        val model = create(); runCurrent()
        model.checkForUpdates(true, false); runCurrent()
        model.downloadUpdate(); runCurrent()
        val file = complete(pending.single()); runCurrent()
        coEvery { repository.getLatestUpdate(UpdateChannel.STABLE) } returns Result.success(stable.copy(assetUrl = "https://example.invalid/replaced.apk"))
        model.checkForUpdates(true, false); runCurrent()
        assertFalse(file.exists())
        assertNull(model.uiState.value.downloadedApkPath)
        model.installUpdateOrRequestPermission()
        verify(exactly = 1) { ApkInstaller.canRequestPackageInstalls(any()) }
    }

    @Test fun channelChangeAfterCompletionDeletesArtifactAndCannotInstallOldRelease() = runTest {
        val model = create(); runCurrent()
        model.checkForUpdates(true, false); runCurrent()
        model.downloadUpdate(); runCurrent()
        val file = complete(pending.single()); runCurrent()
        model.setUpdateChannel(UpdateChannel.BETA); runCurrent()
        model.installUpdateOrRequestPermission()
        assertFalse(file.exists())
        assertNull(model.uiState.value.downloadedApkPath)
        assertFalse(model.uiState.value.showUnknownSourcesDialog)
        verify(exactly = 0) { ApkInstaller.launchInstall(any(), any()) }
    }

    @Test fun installerAndSettingsErrorsRemainRecoverableWithoutLeakingExceptionUrl() = runTest {
        val model = create(); runCurrent()
        model.checkForUpdates(true, false); runCurrent()
        model.downloadUpdate(); runCurrent()
        complete(pending.single()); runCurrent()
        every { ApkInstaller.openUnknownSourcesSettings(any()) } returns Result.failure(SecurityException("https://private.invalid/?token=secret"))
        model.openUnknownSourcesSettings()
        assertEquals("localized-${R.string.update_error_settings_failed}", model.uiState.value.errorMessage)
        every { ApkInstaller.canRequestPackageInstalls(any()) } returns Result.success(true)
        every { ApkInstaller.launchInstall(any(), any()) } returns Result.failure(SecurityException("private"))
        model.installUpdateOrRequestPermission()
        assertEquals("localized-${R.string.update_error_install_failed}", model.uiState.value.errorMessage)
        assertNotNull(model.uiState.value.downloadedApkPath)
        assertTrue(model.uiState.value.showBanner)
        every { ApkInstaller.launchInstall(any(), any()) } returns Result.success(Unit)
        model.installUpdateOrRequestPermission()
        verify(exactly = 2) { ApkInstaller.launchInstall(any(), any()) }
    }
}
