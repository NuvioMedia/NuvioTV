package androidx.media3.decoder.ffmpeg;

import android.os.Handler;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.TraceUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DecoderAudioRenderer;
import com.google.common.base.Preconditions;
import java.util.function.IntSupplier;

/**
 * FFmpeg audio renderer variant that allows constraining decoder output channels.
 * This applies channel downmixing inside FFmpeg decoder initialization, not in track selection.
 */
@UnstableApi
public final class ConfigurableFfmpegAudioRenderer
        extends DecoderAudioRenderer<FfmpegAudioDecoder> {

    private static final int NUM_BUFFERS = 16;
    private static final int DEFAULT_INPUT_BUFFER_SIZE = 5760;

    private final IntSupplier maxChannelCountProvider;

    public ConfigurableFfmpegAudioRenderer(
            Handler eventHandler,
            AudioRendererEventListener eventListener,
            AudioSink audioSink,
            IntSupplier maxChannelCountProvider
    ) {
        super(eventHandler, eventListener, audioSink);
        this.maxChannelCountProvider = maxChannelCountProvider;
    }

    @Override
    public String getName() {
        return "FfmpegAudioRenderer";
    }

    @Override
    protected int supportsFormatInternal(Format format) {
        String mimeType = Preconditions.checkNotNull(format.sampleMimeType);
        if (!FfmpegLibrary.isAvailable() || !MimeTypes.isAudio(mimeType)) {
            return C.FORMAT_UNSUPPORTED_TYPE;
        }
        if (!FfmpegLibrary.supportsFormat(mimeType)
                || (!sinkSupportsFormat(format, C.ENCODING_PCM_16BIT)
                && !sinkSupportsFormat(format, C.ENCODING_PCM_FLOAT))) {
            return C.FORMAT_UNSUPPORTED_SUBTYPE;
        }
        if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
            return C.FORMAT_UNSUPPORTED_DRM;
        }
        return C.FORMAT_HANDLED;
    }

    @Override
    public int supportsMixedMimeTypeAdaptation() {
        return RendererCapabilities.ADAPTIVE_SEAMLESS;
    }

    @Override
    protected FfmpegAudioDecoder createDecoder(Format format, CryptoConfig cryptoConfig)
            throws FfmpegDecoderException {
        TraceUtil.beginSection("createFfmpegAudioDecoder");
        int maxInputSize = format.maxInputSize != Format.NO_VALUE
                ? format.maxInputSize
                : DEFAULT_INPUT_BUFFER_SIZE;

        int targetMaxChannels = Math.max(1, Math.min(8, maxChannelCountProvider.getAsInt()));
        Format decoderInputFormat = (format.channelCount > 0 && targetMaxChannels < format.channelCount)
                ? format.buildUpon().setChannelCount(targetMaxChannels).build()
                : format;

        FfmpegAudioDecoder decoder = new FfmpegAudioDecoder(
                decoderInputFormat,
                NUM_BUFFERS,
                NUM_BUFFERS,
                maxInputSize,
                shouldOutputFloat(decoderInputFormat)
        );
        TraceUtil.endSection();
        return decoder;
    }

    @Override
    protected Format getOutputFormat(FfmpegAudioDecoder decoder) {
        Preconditions.checkNotNull(decoder);
        return new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setChannelCount(decoder.getChannelCount())
                .setSampleRate(decoder.getSampleRate())
                .setPcmEncoding(decoder.getEncoding())
                .build();
    }

    private boolean sinkSupportsFormat(Format inputFormat, int pcmEncoding) {
        return sinkSupportsFormat(
                Util.getPcmFormat(pcmEncoding, inputFormat.channelCount, inputFormat.sampleRate)
        );
    }

    private boolean shouldOutputFloat(Format inputFormat) {
        if (!sinkSupportsFormat(inputFormat, C.ENCODING_PCM_16BIT)) {
            return true;
        }
        int floatSinkSupport = getSinkFormatSupport(
                Util.getPcmFormat(
                        C.ENCODING_PCM_FLOAT,
                        inputFormat.channelCount,
                        inputFormat.sampleRate
                )
        );
        return (floatSinkSupport == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                || floatSinkSupport == AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING)
                && !MimeTypes.AUDIO_AC3.equals(inputFormat.sampleMimeType);
    }
}
