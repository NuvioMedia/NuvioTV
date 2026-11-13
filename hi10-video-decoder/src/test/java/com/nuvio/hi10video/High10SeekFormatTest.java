package com.nuvio.hi10video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.media.MediaCodec;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.TimedValueQueue;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.video.DecoderVideoRenderer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;

/** Exercises the real Media3 format queue; only native/framework boundaries are mocked. */
public final class High10SeekFormatTest {
  @Test
  @SuppressWarnings("unchecked")
  public void seekBeforeFirstOutput_registersFormatForNextInputWithoutRecreatingDecoder() throws Exception {
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264)
        .setCodecs("avc1.6e0028").build();
    try (var crypto = mockConstruction(MediaCodec.CryptoInfo.class);
        var trace = mockStatic(android.os.Trace.class);
        var clock = mockStatic(android.os.SystemClock.class);
        var text = mockStatic(android.text.TextUtils.class);
        var natives = mockConstruction(FfmpegHigh10VideoDecoder.class)) {
      text.when(() -> android.text.TextUtils.isEmpty(any()))
          .thenAnswer(invocation -> {
            CharSequence value = invocation.getArgument(0);
            return value == null || value.length() == 0;
          });
      FfmpegHigh10VideoRenderer renderer = new FfmpegHigh10VideoRenderer(0, null, null, 50);
      FormatHolder holder = new FormatHolder();
      holder.format = format;
      invoke(renderer, "onInputFormatChanged", new Class<?>[] {FormatHolder.class}, holder);
      Object originalDecoder = field(renderer, "decoder");
      FfmpegHigh10VideoDecoder nativeDecoder = natives.constructed().get(0);
      when(nativeDecoder.getName()).thenReturn("test-native-boundary");
      DecoderInputBuffer input = new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
      input.ensureSpaceForWrite(1);
      when(nativeDecoder.dequeueInputBuffer()).thenReturn(input);
      SampleStream stream = mock(SampleStream.class);
      long[] seekTarget = {80_000_000};
      when(stream.readData(any(FormatHolder.class), any(DecoderInputBuffer.class), anyInt()))
          .thenAnswer(invocation -> {
            DecoderInputBuffer buffer = invocation.getArgument(1);
            buffer.timeUs = seekTarget[0];
            buffer.data.put((byte) 1);
            return C.RESULT_BUFFER_READ;
          });
      set(renderer, "stream", stream);
      // First input already queued, but no output has yet established outputFormat.
      set(renderer, "waitingForFirstSampleInFormat", false);
      TimedValueQueue<Format> formats = (TimedValueQueue<Format>) field(renderer, "formatQueue");
      formats.add(0, format);
      Method feed = DecoderVideoRenderer.class.getDeclaredMethod("feedInputBuffer");
      feed.setAccessible(true);
      for (long target : new long[] {80_000_000, 35_000_000, 100_000_000, 45_000_000, 65_000_000}) {
        seekTarget[0] = target;
        input.clear();
        invoke(renderer, "onPositionReset", new Class<?>[] {long.class, boolean.class}, target, false);
        assertEquals(true, feed.invoke(renderer));
        assertEquals(format, formats.pollFloor(target));
      }
      assertSame(originalDecoder, field(renderer, "decoder"));
      assertEquals(1, natives.constructed().size());
      assertEquals(DecoderReuseEvaluation.REUSE_RESULT_NO,
          renderer.canReuseDecoder("test-native-boundary", format, format).result);
    }
  }

  private static Field member(Object object, String name) throws Exception {
    for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
      try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
      catch (NoSuchFieldException absent) { /* Continue to Media3's owning class. */ }
    }
    throw new NoSuchFieldException(name);
  }
  private static Object field(Object object, String name) throws Exception { return member(object, name).get(object); }
  private static void set(Object object, String name, Object value) throws Exception { member(object, name).set(object, value); }
  private static Object invoke(Object object, String name, Class<?>[] parameters, Object... arguments) throws Exception {
    for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
      try {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(object, arguments);
      } catch (NoSuchMethodException absent) { /* Continue to the owning class. */ }
    }
    throw new NoSuchMethodException(name);
  }
}
