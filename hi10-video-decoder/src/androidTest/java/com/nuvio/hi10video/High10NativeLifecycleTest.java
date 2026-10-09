/* SPDX-License-Identifier: Apache-2.0 */
package com.nuvio.hi10video;

import static org.junit.Assert.*;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;

/** Actual ARMv7 JNI regression gate; no mocks, Surface or physical-presentation claims.
 * Serve existing avc-hi10.mp4 and its independent yuv420p framemd5 reference on fixtureBaseUrl.
 * Reference must have 600 frames, 1280x720, 30 fps, High10 with B-frames.
 */
public final class High10NativeLifecycleTest {
  private static File fixture;
  private static List<String> hashes;

  @BeforeClass public static void loadFixture() throws Exception {
    String base = InstrumentationRegistry.getArguments().getString(
        "fixtureBaseUrl", "http://127.0.0.1:18019/");
    fixture = new File(InstrumentationRegistry.getInstrumentation().getContext().getCacheDir(),
        "avc-hi10.mp4");
    try (InputStream input = new URL(base + "avc-hi10.mp4").openStream();
        FileOutputStream output = new FileOutputStream(fixture)) {
      byte[] bytes = new byte[65536];
      int count;
      while ((count = input.read(bytes)) >= 0) output.write(bytes, 0, count);
    }
    hashes = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
        new URL(base + "avc-hi10-yuv8.framemd5").openStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (!line.startsWith("#") && !line.trim().isEmpty()) {
          String[] columns = line.split(",");
          hashes.add(columns[columns.length - 1].trim());
        }
      }
    }
    assertEquals("Wrong reference fixture", 600, hashes.size());
    assertTrue("Missing real H.264 decoder", NuvioHi10VideoLibrary.hasH264Decoder());
  }

  @Test public void bFramesDrainToExactFinalFrameAndSingleEos() throws Exception {
    try (Session session = new Session()) {
      List<Long> pts = collect(session, 0, true);
      List<Long> expected = new ArrayList<>(session.inputPts);
      Collections.sort(expected);
      assertEquals("Every delayed B-frame must retain decoded PTS", expected, pts);
      assertEquals(600, pts.size());
      assertNull(session.decoder.dequeueOutputBuffer());
      assertNull("Post-EOS input must be rejected", session.decoder.dequeueInputBuffer());
    }
  }

  @Test public void fiveSeeksInvalidateHeldFramesAndRestartNativeDecoder() throws Exception {
    try (Session session = new Session()) {
      for (long target : new long[] {4_000_000, 1_000_000, 7_000_000, 2_000_000, 9_000_000}) {
        VideoDecoderOutputBuffer held = firstOutput(session);
        session.decoder.flush();
        assertFalse("Renderer-held frame survives only as invalid storage",
            session.decoder.isCurrentOutput(held));
        held.release();
        session.seek(target);
        List<Long> pts = collect(session, target, true);
        assertFalse(pts.isEmpty());
        assertTrue(pts.get(0) >= target);
        assertEquals("Final delayed frame lost after seek", 19_966_666L,
            (long) pts.get(pts.size() - 1));
        session.decoder.flush();
        session.seek(0);
      }
    }
  }

  @Test public void releaseWithFullOutputPoolWakesNativeWorker() throws Exception {
    Session session = new Session();
    try {
      // No output consumer: enough packets to block receive on all 16 output slots.
      long deadline = SystemClock.elapsedRealtime() + 10_000;
      int queued = 0;
      while (queued < 40 && SystemClock.elapsedRealtime() < deadline) {
        if (session.feed()) queued++; else Thread.yield();
      }
      assertTrue("Native decoder did not fill bounded pools", queued >= 20);
      releaseWithinDeadline(session);
    } finally {
      session.close();
    }
  }

  @Test public void flushDuringNativeDrainDiscardsEosAndRestarts() throws Exception {
    try (Session session = new Session()) {
      long deadline = SystemClock.elapsedRealtime() + 60_000;
      while (!session.eosQueued && SystemClock.elapsedRealtime() < deadline) {
        session.feed();
        VideoDecoderOutputBuffer output = session.decoder.dequeueOutputBuffer();
        if (output != null) output.release();
      }
      assertTrue("EOS was never queued", session.eosQueued);
      session.decoder.flush();
      session.seek(0);
      assertEquals(600, collect(session, 0, true).size());
    }
  }

  private static void releaseWithinDeadline(Session session) throws Exception {
    Thread thread = new Thread(session.decoder::release, "hi10-release-test");
    thread.start();
    thread.join(2_000);
    assertFalse("Native worker release deadlocked", thread.isAlive());
  }

  private static VideoDecoderOutputBuffer firstOutput(Session session) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + 10_000;
    while (SystemClock.elapsedRealtime() < deadline) {
      session.feed();
      VideoDecoderOutputBuffer output = session.decoder.dequeueOutputBuffer();
      if (output != null) {
        assertFalse(output.isEndOfStream());
        return output;
      }
      Thread.yield();
    }
    throw new AssertionError("No native output before deadline");
  }

  private static List<Long> collect(Session session, long startUs, boolean verifyHashes)
      throws Exception {
    session.decoder.setOutputStartTimeUs(startUs);
    List<Long> pts = new ArrayList<>();
    long deadline = SystemClock.elapsedRealtime() + 60_000;
    while (SystemClock.elapsedRealtime() < deadline) {
      session.feed();
      VideoDecoderOutputBuffer output = session.decoder.dequeueOutputBuffer();
      if (output == null) { Thread.yield(); continue; }
      try {
        assertTrue(session.decoder.isCurrentOutput(output));
        if (output.isEndOfStream()) return pts;
        assertTrue("Stale pre-seek PTS", output.timeUs >= startUs);
        if (!pts.isEmpty()) assertTrue("PTS reordered", output.timeUs > pts.get(pts.size() - 1));
        pts.add(output.timeUs);
        if (verifyHashes) {
          int index = (int) ((output.timeUs * 30 + 500_000) / 1_000_000);
          assertEquals("Frame " + index, hashes.get(index), hash(output));
        }
      } finally {
        output.release();
      }
    }
    throw new AssertionError("Native drain/decode timed out; frames=" + pts.size());
  }

  private static String hash(VideoDecoderOutputBuffer output) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("MD5");
    for (int plane = 0; plane < 3; plane++) {
      int width = plane == 0 ? output.width : (output.width + 1) / 2;
      int height = plane == 0 ? output.height : (output.height + 1) / 2;
      ByteBuffer bytes = output.yuvPlanes[plane].duplicate();
      for (int row = 0; row < height; row++) {
        bytes.limit(bytes.capacity());
        bytes.position(row * output.yuvStrides[plane]);
        bytes.limit(bytes.position() + width);
        digest.update(bytes);
      }
    }
    StringBuilder result = new StringBuilder();
    for (byte value : digest.digest()) result.append(String.format("%02x", value & 255));
    return result.toString();
  }

  private static final class Session implements AutoCloseable {
    final MediaExtractor extractor = new MediaExtractor();
    final FfmpegHigh10VideoDecoder decoder;
    final List<Long> inputPts = new ArrayList<>();
    boolean eosQueued;

    Session() throws Exception {
      extractor.setDataSource(fixture.getAbsolutePath());
      MediaFormat mediaFormat = extractor.getTrackFormat(0);
      assertEquals("video/avc", mediaFormat.getString(MediaFormat.KEY_MIME));
      extractor.selectTrack(0);
      List<byte[]> csd = new ArrayList<>();
      for (int i = 0; mediaFormat.containsKey("csd-" + i); i++) {
        ByteBuffer buffer = mediaFormat.getByteBuffer("csd-" + i).duplicate();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        csd.add(bytes);
      }
      Format format = new Format.Builder().setSampleMimeType("video/avc").setCodecs("avc1.6e001f")
          .setWidth(mediaFormat.getInteger(MediaFormat.KEY_WIDTH))
          .setHeight(mediaFormat.getInteger(MediaFormat.KEY_HEIGHT)).setInitializationData(csd).build();
      decoder = new FfmpegHigh10VideoDecoder(4, 16, 1_048_576, 4, format);
      decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_SURFACE_YUV);
    }

    boolean feed() throws Exception {
      if (eosQueued) return false;
      DecoderInputBuffer input = decoder.dequeueInputBuffer();
      if (input == null) return false;
      int size = extractor.readSampleData(input.data, 0);
      if (size < 0) {
        input.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
        eosQueued = true;
      } else {
        input.timeUs = extractor.getSampleTime();
        inputPts.add(input.timeUs);
        input.data.position(size);
        input.flip();
        extractor.advance();
      }
      decoder.queueInputBuffer(input);
      return true;
    }

    void seek(long targetUs) {
      extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
      inputPts.clear();
      eosQueued = false;
      decoder.setOutputStartTimeUs(targetUs);
    }
    @Override public void close() { decoder.release(); extractor.release(); }
  }
}
