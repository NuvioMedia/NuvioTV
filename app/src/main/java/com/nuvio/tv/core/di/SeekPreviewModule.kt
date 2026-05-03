package com.nuvio.tv.core.di

import android.content.Context
import io.framescout.FrameGrabberFactory
import io.framescout.MmrFrameGrabber
import io.framescout.SeekPreviewGenerator
import io.framescout.SeekPreviewThumbnailStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SeekPreviewModule {

    @Provides
    @Singleton
    fun provideSeekPreviewThumbnailStore(
        @ApplicationContext context: Context
    ): SeekPreviewThumbnailStore {
        val rootDir = File(context.cacheDir, "seek_previews")
        return SeekPreviewThumbnailStore(rootDir)
    }

    @Provides
    @Singleton
    fun provideFrameGrabberFactory(): FrameGrabberFactory = MmrFrameGrabber.Factory

    @Provides
    fun provideSeekPreviewGenerator(
        store: SeekPreviewThumbnailStore,
        frameGrabberFactory: FrameGrabberFactory
    ): SeekPreviewGenerator = SeekPreviewGenerator(
        store = store,
        grabberFactory = frameGrabberFactory
    )
}
