package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.source.chunk.MediaChunk
import android.text.TextUtils
import android.os.SystemClock
import android.content.Context
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.RendererCapabilities
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.VideoCapabilities
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URL
import java.net.HttpURLConnection
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Tracks
import kotlinx.coroutines.flow.MutableStateFlow

class TrackSelectionInvestigationTest {

    @Test
    fun testTrackSelectionWithVixsrcManifest() {
        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers {
            val seq = firstArg<CharSequence?>()
            seq == null || seq.isEmpty()
        }

        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1000L

        // Create the 3 video formats from the vixsrc playlist:
        // 1080p (4.5 Mbps), 720p (2.15 Mbps), 480p (1.2 Mbps)
        val format1080p = Format.Builder()
            .setId("1")
            .setSampleMimeType("video/avc")
            .setCodecs("avc1.640028")
            .setPeakBitrate(4500000)
            .setWidth(1920)
            .setHeight(1080)
            .build()

        val format720p = Format.Builder()
            .setId("2")
            .setSampleMimeType("video/avc")
            .setCodecs("avc1.640028")
            .setPeakBitrate(2150000)
            .setWidth(1280)
            .setHeight(720)
            .build()

        val format480p = Format.Builder()
            .setId("3")
            .setSampleMimeType("video/avc")
            .setCodecs("avc1.640028")
            .setPeakBitrate(1200000)
            .setWidth(854)
            .setHeight(480)
            .build()

        // TrackGroup formats must be sorted by quality (highest bandwidth/quality first)
        val trackGroup = TrackGroup(format1080p, format720p, format480p)
        val tracks = intArrayOf(0, 1, 2) // Indices in the group

        // Let's test different estimated bandwidths
        val testBandwidths = listOf(
            1_000_000L to "Under 480p bandwidth",
            1_500_000L to "Sufficient for 480p",
            2_000_000L to "Between 480p and 720p",
            3_500_000L to "Sufficient for 720p (with 0.7 fraction)",
            5_000_000L to "Between 720p and 1080p",
            7_000_000L to "Sufficient for 1080p (with 0.7 fraction)",
            25_000_000L to "Initial estimate (25 Mbps)"
        )

        for ((bandwidth, description) in testBandwidths) {
            val bandwidthMeter = mockk<BandwidthMeter>(relaxed = true)
            every { bandwidthMeter.bitrateEstimate } returns bandwidth
            every { bandwidthMeter.timeToFirstByteEstimateUs } returns C.TIME_UNSET

            // Instantiate AdaptiveTrackSelection with default values
            val adaptiveSelection = AdaptiveTrackSelection(
                trackGroup,
                tracks,
                bandwidthMeter
            )

            // Update selected track (simulating playback update at start)
            adaptiveSelection.updateSelectedTrack(
                /* playbackPositionUs= */ 0,
                /* bufferedDurationUs= */ 0,
                /* playlistTimelineEngineDelayUs= */ C.TIME_UNSET,
                /* queue= */ emptyList(),
                /* mediaChunkIterators= */ emptyArray()
            )

            val selectedIndex = adaptiveSelection.selectedIndex
            val selectedFormat = adaptiveSelection.getFormat(selectedIndex)
            
            println("--- Bandwidth: ${bandwidth / 1000} kbps ($description) ---")
            println("Effective Bandwidth (0.7x): ${(bandwidth * 0.7) / 1000} kbps")
            println("Selected Quality: ${selectedFormat.height}p (Bitrate: ${selectedFormat.bitrate / 1000} kbps, Index: $selectedIndex)")
        }
    }

    @Test
    fun testTrackSelectionWithDecoderCapabilityLimits() {
        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers {
            val seq = firstArg<CharSequence?>()
            seq == null || seq.isEmpty()
        }

        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1000L

        val format1080p = Format.Builder().setId("1").setSampleMimeType("video/avc").setCodecs("avc1.640028").setPeakBitrate(4500000).setWidth(1920).setHeight(1080).build()
        val format720p = Format.Builder().setId("2").setSampleMimeType("video/avc").setCodecs("avc1.640028").setPeakBitrate(2150000).setWidth(1280).setHeight(720).build()
        val format480p = Format.Builder().setId("3").setSampleMimeType("video/avc").setCodecs("avc1.640028").setPeakBitrate(1200000).setWidth(854).setHeight(480).build()

        val trackGroup = TrackGroup(format1080p, format720p, format480p)
        
        // Simulating that the device only supports 480p (Index 2) as H.264 High Profile @ Level 4.0
        // is unsupported for higher resolutions on this TV device.
        val supportedTracks = intArrayOf(2) // Only 480p index

        val bandwidthMeter = mockk<BandwidthMeter>(relaxed = true)
        every { bandwidthMeter.bitrateEstimate } returns 25_000_000L // 25 Mbps (Plenty of bandwidth)
        every { bandwidthMeter.timeToFirstByteEstimateUs } returns C.TIME_UNSET

        val adaptiveSelection = AdaptiveTrackSelection(
            trackGroup,
            supportedTracks,
            bandwidthMeter
        )

        adaptiveSelection.updateSelectedTrack(0, 0, C.TIME_UNSET, emptyList(), emptyArray())

        val selectedIndex = adaptiveSelection.selectedIndex
        val selectedFormat = adaptiveSelection.getFormat(selectedIndex)

        println("--- Decoder capability constraint test ---")
        println("Raw Bandwidth: 25 Mbps")
        println("Supported tracks: Only 480p")
        println("Selected Quality: ${selectedFormat.height}p (Bitrate: ${selectedFormat.bitrate / 1000} kbps)")
        
        assertEquals(480, selectedFormat.height)
    }

    @Test
    fun testTrackSelectionWithLatencyPenalty() {
        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers {
            val seq = firstArg<CharSequence?>()
            seq == null || seq.isEmpty()
        }

        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1000L

        val format1080p = Format.Builder().setId("1").setSampleMimeType("video/avc").setCodecs("avc1.640028").setPeakBitrate(4500000).setWidth(1920).setHeight(1080).build()
        val format720p = Format.Builder().setId("2").setSampleMimeType("video/avc").setCodecs("avc1.640028").setPeakBitrate(2150000).setWidth(1280).setHeight(720).build()
        val format480p = Format.Builder().setId("3").setSampleMimeType("video/avc").setCodecs("avc1.640028").setPeakBitrate(1200000).setWidth(854).setHeight(480).build()

        val trackGroup = TrackGroup(format1080p, format720p, format480p)
        val tracks = intArrayOf(0, 1, 2)

        // Simulate ongoing playback with a last chunk duration of 4 seconds
        val mockChunk = object : MediaChunk(
            mockk(relaxed = true),
            mockk(relaxed = true),
            format1080p, // trackFormat (non-null)
            C.SELECTION_REASON_UNKNOWN,
            null,
            0L, // startTimeUs
            4_000_000L, // endTimeUs (4 seconds duration)
            0L // chunkIndex
        ) {
            override fun isLoadCompleted(): Boolean = true
            override fun cancelLoad() {}
            override fun load() {}
        }
        val queue = listOf(mockChunk)

        // High raw bandwidth of 25 Mbps
        val rawBandwidth = 25_000_000L

        // Test different TTFBs (latency)
        val testLatencies = listOf(
            0L to "No latency",
            500_000L to "0.5s latency (TTFB)",
            1_000_000L to "1.0s latency (TTFB)",
            2_000_000L to "2.0s latency (TTFB)",
            3_000_000L to "3.0s latency (TTFB)",
            3_500_000L to "3.5s latency (TTFB)"
        )

        println("--- Latency (TTFB) penalty test (Raw Bandwidth: 25 Mbps, Segment: 4s) ---")
        for ((ttfbUs, description) in testLatencies) {
            val bandwidthMeter = mockk<BandwidthMeter>(relaxed = true)
            every { bandwidthMeter.bitrateEstimate } returns rawBandwidth
            every { bandwidthMeter.timeToFirstByteEstimateUs } returns ttfbUs

            val adaptiveSelection = AdaptiveTrackSelection(
                trackGroup,
                tracks,
                bandwidthMeter
            )

            adaptiveSelection.updateSelectedTrack(0, 0, C.TIME_UNSET, queue, emptyArray())

            val selectedIndex = adaptiveSelection.selectedIndex
            val selectedFormat = adaptiveSelection.getFormat(selectedIndex)
            
            val cautiousBandwidth = rawBandwidth * 0.7
            val availableTimeFactor = (4_000_000.0 - ttfbUs) / 4_000_000.0
            val mathEffectiveBandwidth = if (availableTimeFactor > 0) cautiousBandwidth * availableTimeFactor else 0.0

            println("TTFB: ${ttfbUs / 1000} ms ($description) -> Effective Bandwidth: ${(mathEffectiveBandwidth / 1000).toInt()} kbps -> Selected: ${selectedFormat.height}p")
        }
    }


    @Test
    fun testExoPlayerCodecCapabilitiesEvaluation() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<Throwable>()) } returns 0
        every { android.util.Log.w(any(), any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.v(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers {
            val seq = firstArg<CharSequence?>()
            seq == null || seq.isEmpty()
        }

        // Helper to construct a real android.util.Pair and set its final fields via reflection on JVM
        fun createAndroidPair(first: Int, second: Int): android.util.Pair<Int, Int> {
            val pair = android.util.Pair(first, second)
            try {
                val firstField = android.util.Pair::class.java.getField("first")
                firstField.isAccessible = true
                firstField.set(pair, first)

                val secondField = android.util.Pair::class.java.getField("second")
                secondField.isAccessible = true
                secondField.set(pair, second)
            } catch (e: Exception) {
                println("Failed to set fields on Pair: ${e.message}")
            }
            return pair
        }

        // Statically mock MediaCodecUtil to return simulated profile/level pairs, bypassing android.util.Pair JVM stub limitations
        mockkStatic(androidx.media3.exoplayer.mediacodec.MediaCodecUtil::class)
        every { androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getCodecProfileAndLevel(any()) } answers {
            val format = firstArg<Format>()
            val codecs = format.codecs ?: ""
            val profileLevel = when {
                codecs.contains("640028") -> 100 to 40  // High Profile @ Level 4.0
                codecs.contains("64002a") -> 100 to 42  // High Profile @ Level 4.2
                codecs.contains("4d401f") -> 77 to 31   // Main Profile @ Level 3.1
                else -> null
            }
            if (profileLevel != null) {
                createAndroidPair(profileLevel.first, profileLevel.second)
            } else {
                null
            }
        }

        // Create formats representing the streams we want to check
        val formats = listOf(
            Format.Builder().setId("1080p").setSampleMimeType("video/avc").setCodecs("avc1.640028").setWidth(1920).setHeight(1080).build(), // High Profile @ L4.0 (avc1.640028)
            Format.Builder().setId("720p").setSampleMimeType("video/avc").setCodecs("avc1.640028").setWidth(1280).setHeight(720).build(),   // High Profile @ L4.0 (avc1.640028)
            Format.Builder().setId("480p").setSampleMimeType("video/avc").setCodecs("avc1.640028").setWidth(854).setHeight(480).build(),   // High Profile @ L4.0 (avc1.640028)
            Format.Builder().setId("1080p_L4.2").setSampleMimeType("video/avc").setCodecs("avc1.64002a").setWidth(1920).setHeight(1080).build(), // High Profile @ L4.2 (avc1.64002a)
            Format.Builder().setId("1080p_Main_L3.1").setSampleMimeType("video/avc").setCodecs("avc1.4d401f").setWidth(1920).setHeight(1080).build() // Main Profile @ L3.1 (avc1.4d401f)
        )

        // Define simulated decoder capability profiles:
        // H.264 profiles: AVCProfileMain = 77, AVCProfileHigh = 100
        // H.264 levels: AVCLevel31 = 31, AVCLevel4 = 40, AVCLevel42 = 42
        val testDecoderCapabilities = listOf(
            "High Profile Level 4.0 Decoder" to listOf(100 to 40),
            "High Profile Level 3.1 Decoder" to listOf(100 to 31),
            "Main Profile Level 4.0 Decoder" to listOf(77 to 40)
        )

        for ((decoderName, profileLevels) in testDecoderCapabilities) {
            println("\n--- Testing with simulated decoder: $decoderName ---")
            
            // Map the profile-level pairs to mock CodecProfileLevel objects using MediaCodecUtil helper
            val mockProfileLevels = profileLevels.map { (p, l) ->
                androidx.media3.exoplayer.mediacodec.MediaCodecUtil.createCodecProfileLevel(p, l)
            }.toTypedArray()

            // Mock android.media.MediaCodecInfo capabilities
            val videoCaps = mockk<android.media.MediaCodecInfo.VideoCapabilities>()
            every { videoCaps.isSizeSupported(any(), any()) } returns true
            every { videoCaps.areSizeAndRateSupported(any(), any(), any()) } returns true
            every { videoCaps.getWidthAlignment() } returns 2
            every { videoCaps.getHeightAlignment() } returns 2

            val capabilities = mockk<CodecCapabilities>(relaxed = true)
            every { capabilities.getVideoCapabilities() } returns videoCaps
            
            // Set the profileLevels field of CodecCapabilities using reflection
            val field = CodecCapabilities::class.java.getField("profileLevels")
            field.set(capabilities, mockProfileLevels)

            // Instantiate a real Media3 MediaCodecInfo (not spyk)
            val codecInfo = androidx.media3.exoplayer.mediacodec.MediaCodecInfo.newInstance(
                /* name= */ "test-h264-decoder",
                /* mimeType= */ "video/avc",
                /* codecMimeType= */ "video/avc",
                /* capabilities= */ capabilities,
                /* hardwareAccelerated= */ true,
                /* softwareOnly= */ false,
                /* vendor= */ true,
                /* forceDisableAdaptive= */ false,
                /* forceSecure= */ false
            )

            for (format in formats) {
                println("Evaluating format: ${format.id}, codecs: ${format.codecs}")
                
                try {
                    // Check format support using ExoPlayer's capability check logic
                    val isSupported = codecInfo.isFormatSupported(format)
                    val isFunctionallySupported = codecInfo.isFormatFunctionallySupported(format)
                    
                    println("Format ${format.id} (Size: ${format.width}x${format.height}):")
                    println("  - isFormatSupported (checkPerformanceCapabilities = true): $isSupported")
                    println("  - isFormatFunctionallySupported (checkPerformanceCapabilities = false): $isFunctionallySupported")
                } catch (e: Exception) {
                    println("  - capability check failed: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }

    @Test
    fun testTrackSelectorHlsResolutionBypass() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0

        val format1080pAvc8Bit = Format.Builder()
            .setId("1080p-avc")
            .setSampleMimeType("video/avc")
            .setCodecs("avc1.640028")
            .setWidth(1920)
            .setHeight(1080)
            .build()

        val format1080pHevc10Bit = Format.Builder()
            .setId("1080p-hevc-10bit")
            .setSampleMimeType("video/hevc")
            .setCodecs("hev1.2.4.L150.B0.main10")
            .setWidth(1920)
            .setHeight(1080)
            .build()

        val mappedTrackInfo = mockk<MappedTrackInfo>()
        every { mappedTrackInfo.rendererCount } returns 1
        every { mappedTrackInfo.getRendererType(0) } returns C.TRACK_TYPE_VIDEO
        
        val trackGroupAvc = TrackGroup(format1080pAvc8Bit)
        val trackGroupHevc10 = TrackGroup(format1080pHevc10Bit)
        val trackGroups = TrackGroupArray(trackGroupAvc, trackGroupHevc10)
        every { mappedTrackInfo.getTrackGroups(0) } returns trackGroups

        val capabilitiesAvc = RendererCapabilities.create(C.FORMAT_EXCEEDS_CAPABILITIES)
        val capabilitiesHevc10 = RendererCapabilities.create(C.FORMAT_EXCEEDS_CAPABILITIES)
        val rendererFormatSupports = arrayOf(
            arrayOf(
                intArrayOf(capabilitiesAvc),
                intArrayOf(capabilitiesHevc10)
            )
        )

        val context = mockk<Context>(relaxed = true)
        val adaptiveTrackSelectionFactory = mockk<AdaptiveTrackSelection.Factory>(relaxed = true)
        
        val isHls = true
        val selector = object : DefaultTrackSelector(context, adaptiveTrackSelectionFactory) {
            public override fun selectAllTracks(
                mappedTrackInfo: MappedTrackInfo,
                rendererFormatSupports: Array<out Array<out IntArray>>,
                rendererMixedMimeTypeAdaptationSupports: IntArray,
                params: Parameters
            ): Array<ExoTrackSelection.Definition?> {
                if (isHls) {
                    for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
                        if (mappedTrackInfo.getRendererType(rendererIndex) == C.TRACK_TYPE_VIDEO) {
                            val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
                            for (groupIndex in 0 until trackGroups.length) {
                                val group = trackGroups[groupIndex]
                                  for (trackIndex in 0 until group.length) {
                                      val format = group.getFormat(trackIndex)
                                      val support = rendererFormatSupports[rendererIndex][groupIndex][trackIndex]
                                      val formatSupport = RendererCapabilities.getFormatSupport(support)
                                      if (formatSupport == C.FORMAT_EXCEEDS_CAPABILITIES) {
                                          val mime = format.sampleMimeType
                                          val isAvcOrHevc = mime == MimeTypes.VIDEO_H264 || mime == MimeTypes.VIDEO_H265
                                          val isAtMost1080p = format.width <= 1920 && format.height <= 1080
                                          val codecs = format.codecs?.lowercase() ?: ""
                                          val is10Bit = codecs.contains("main10") || codecs.contains("hevc.2") || codecs.contains("hev2")
                                          val isHdr = format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084
                                          val isStandard8Bit = !is10Bit && !isHdr

                                          if (isAvcOrHevc && isAtMost1080p && isStandard8Bit) {
                                              rendererFormatSupports[rendererIndex][groupIndex][trackIndex] =
                                                  RendererCapabilities.create(
                                                      C.FORMAT_HANDLED,
                                                      RendererCapabilities.getAdaptiveSupport(support),
                                                      RendererCapabilities.getTunnelingSupport(support),
                                                      RendererCapabilities.getHardwareAccelerationSupport(support),
                                                      RendererCapabilities.getDecoderSupport(support)
                                                  )
                                          }
                                      }
                                  }
                              }
                          }
                      }
                  }
                  return arrayOfNulls(mappedTrackInfo.rendererCount)
              }
          }

        selector.selectAllTracks(
            mappedTrackInfo,
            rendererFormatSupports,
            intArrayOf(),
            DefaultTrackSelector.Parameters.DEFAULT_WITHOUT_CONTEXT
        )

        val finalSupportAvc = RendererCapabilities.getFormatSupport(rendererFormatSupports[0][0][0])
        assertEquals(C.FORMAT_HANDLED, finalSupportAvc)

        val finalSupportHevc10 = RendererCapabilities.getFormatSupport(rendererFormatSupports[0][1][0])
        assertEquals(C.FORMAT_EXCEEDS_CAPABILITIES, finalSupportHevc10)
    }

    @Test
    fun testBuildStreamInfoDataWithActiveVideoFormat() {
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        val mockExoPlayer = mockk<ExoPlayer>(relaxed = true)
        
        // Active format is the cropped format returned by the decoder/player (1918x802)
        val activeFormat = Format.Builder()
            .setId("1")
            .setWidth(1918)
            .setHeight(802)
            .setPeakBitrate(1800000)
            .setSampleMimeType("video/avc")
            .setCodecs("avc1.640028")
            .build()

        // Manifest format contains the original uncropped resolution (1920x1080)
        val manifestFormat = Format.Builder()
            .setId("1")
            .setWidth(1920)
            .setHeight(1080)
            .setPeakBitrate(1800000)
            .setSampleMimeType("video/avc")
            .setCodecs("avc1.640028")
            .build()

        // Construct real currentTracks structure using media3 class constructors
        val mediaTrackGroup = TrackGroup(manifestFormat)
        val realGroup = Tracks.Group(mediaTrackGroup, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))
        val mockTracks = Tracks(com.google.common.collect.ImmutableList.of(realGroup))
        
        every { mockExoPlayer.videoFormat } returns activeFormat
        every { mockExoPlayer.currentTracks } returns mockTracks
        every { controller._exoPlayer } returns mockExoPlayer

        // Mock other controller properties
        every { controller._uiState } returns MutableStateFlow(PlayerUiState(
            currentStreamName = "Test Stream",
            detectedFrameRate = 23.976f
        ))
        every { controller.currentAddonName } returns "Test Addon"
        every { controller.currentAddonLogo } returns "logo.png"
        every { controller.currentStreamDescription } returns "Description"
        every { controller.currentFilename } returns "file.mkv"
        every { controller.currentVideoSize } returns 1024L
        every { controller.currentVideoWidth } returns 1920
        every { controller.currentVideoHeight } returns 1080
        every { controller.currentVideoBitrate } returns 4500000
        every { controller.currentVideoCodec } returns "HEVC"
        every { controller.currentInternalPlayerEngine } returns com.nuvio.tv.data.local.InternalPlayerEngine.EXOPLAYER

        val mockContext = mockk<Context>(relaxed = true)
        every { mockContext.getString(any()) } returns "mocked_string"
        every { mockContext.resources } returns mockk(relaxed = true)
        every { controller.context } returns mockContext

        val streamInfo = controller.buildStreamInfoData()

        // Verify that the uncropped manifest/header resolution is matched and resolved
        assertEquals(1920, streamInfo.videoWidth)
        assertEquals(1080, streamInfo.videoHeight)
        assertEquals(1800000, streamInfo.videoBitrate)
        assertEquals("AVC", streamInfo.videoCodec)
    }

    @Test
    fun testFormatResolution() {
        // Cropped widescreen / letterboxed resolutions
        assertEquals("1918 × 802 (1080p)", formatResolution(1918, 802))
        assertEquals("854 × 357 (480p)", formatResolution(854, 357))
        assertEquals("1278 × 530 (720p)", formatResolution(1278, 530))
        assertEquals("3836 × 1600 (4K)", formatResolution(3836, 1600))

        // Standard resolutions
        assertEquals("1920 × 1080 (1080p)", formatResolution(1920, 1080))
        assertEquals("1280 × 720 (720p)", formatResolution(1280, 720))
        assertEquals("854 × 480 (480p)", formatResolution(854, 480))
        assertEquals("3840 × 2160 (4K)", formatResolution(3840, 2160))

        // Portrait / vertical resolutions
        assertEquals("1080 × 1920 (1080p)", formatResolution(1080, 1920))
        assertEquals("720 × 1280 (720p)", formatResolution(720, 1280))

        // Custom / fallback resolutions
        assertEquals("640 × 360 (360p)", formatResolution(640, 360))
        assertEquals("720 × 576 (576p)", formatResolution(720, 576))
    }

    @Test
    fun testDolbyVisionExtractorsFactoryFormatRewriting() {
        val mockExtractor = mockk<androidx.media3.extractor.mp4.Mp4Extractor>(relaxed = true)
        val delegateFactory = androidx.media3.extractor.ExtractorsFactory { arrayOf(mockExtractor) }

        val factory = com.nuvio.tv.core.player.DolbyVisionExtractorsFactory(
            delegate = delegateFactory,
            config = com.nuvio.tv.core.player.DolbyVisionConversionConfig(active = false),
            stripDvRpu = true
        )

        val extractors = factory.createExtractors()
        assertEquals(1, extractors.size)
        val wrappedExtractor = extractors[0]

        val mockExtractorOutput = mockk<androidx.media3.extractor.ExtractorOutput>(relaxed = true)
        val mockTrackOutput = mockk<androidx.media3.extractor.TrackOutput>(relaxed = true)
        every { mockExtractorOutput.track(any(), C.TRACK_TYPE_VIDEO) } returns mockTrackOutput

        val extractorOutputSlot = io.mockk.slot<androidx.media3.extractor.ExtractorOutput>()
        every { mockExtractor.init(capture(extractorOutputSlot)) } returns Unit

        wrappedExtractor.init(mockExtractorOutput)

        val wrappedTrackOutput = extractorOutputSlot.captured.track(1, C.TRACK_TYPE_VIDEO)

        val formatSlot = io.mockk.slot<Format>()
        every { mockTrackOutput.format(capture(formatSlot)) } returns Unit

        // 1. Dolby Vision format -> should be rewritten to HEVC (H.265)
        val dvFormat = Format.Builder()
            .setId("1")
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.07.06")
            .build()

        wrappedTrackOutput.format(dvFormat)
        assertEquals(MimeTypes.VIDEO_H265, formatSlot.captured.sampleMimeType)

        // 2. Standard H.264 format -> should NOT be rewritten to HEVC (H.265)
        val avcFormat = Format.Builder()
            .setId("2")
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setCodecs("avc1.64001F")
            .build()

        wrappedTrackOutput.format(avcFormat)
        assertEquals(MimeTypes.VIDEO_H264, formatSlot.captured.sampleMimeType)
    }
}
