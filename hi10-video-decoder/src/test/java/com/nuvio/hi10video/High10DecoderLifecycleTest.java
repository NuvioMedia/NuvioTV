package com.nuvio.hi10video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mockConstruction;

import android.media.MediaCodec;
import androidx.media3.common.C;
import androidx.media3.decoder.Decoder;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.function.LongUnaryOperator;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedConstruction;

public final class High10DecoderLifecycleTest {
  private MockedConstruction<MediaCodec.CryptoInfo> crypto;
  private Decoder<DecoderInputBuffer, VideoDecoderOutputBuffer, FfmpegHigh10VideoDecoderException> decoder;

  @Before
  public void mockOnlyUnusedFrameworkCrypto() {
    crypto = mockConstruction(MediaCodec.CryptoInfo.class);
  }

  @After
  public void release() {
    if (decoder != null) decoder.release();
    crypto.close();
  }

  @Test
  public void eosDrainsAllFramesBeforeSingleEos() throws Exception {
    Backend backend = new Backend(true, value -> value);
    decoder = create(backend);
    for (long pts : new long[] {0, 3000, 1000, 2000}) queue(pts, false);
    queue(0, true);
    assertEquals(Arrays.asList(0L, 1000L, 2000L, 3000L), collectToEos());
    assertEquals(1, backend.drainCount);
  }

  @Test
  public void outputStartFiltering_usesDecodedPtsNotCurrentInputPts() throws Exception {
    Backend backend = new Backend(false, value -> value == 3000 ? 1000 : 3000);
    decoder = create(backend);
    decoder.setOutputStartTimeUs(2000);
    queue(3000, false); // Output 1000 must be skipped despite eligible input PTS.
    queue(1000, false); // Output 3000 must survive despite input PTS before seek.
    queue(0, true);
    assertEquals(Collections.singletonList(3000L), collectToEos());
  }

  @Test
  public void flushInvalidatesInFlightGeneration_repeatedAlternatingSeeks() throws Exception {
    Backend backend = new Backend(false, value -> value);
    decoder = create(backend);
    High10DecoderLifecycle lifecycle = (High10DecoderLifecycle) decoder;
    for (long start : new long[] {5000, 1000, 6000, 2000, 7000}) {
      backend.receiveEntered = new CountDownLatch(1);
      backend.allowReceive = new CountDownLatch(1);
      backend.holdNextReceive = true;
      queue(start + 100, false);
      assertTrue(backend.receiveEntered.await(2, TimeUnit.SECONDS));
      decoder.flush();
      decoder.setOutputStartTimeUs(start);
      backend.allowReceive.countDown();
      queue(start + 200, false);
      VideoDecoderOutputBuffer output = next();
      assertEquals(start + 200, output.timeUs);
      assertTrue(lifecycle.isCurrentOutput(output));
      output.release();
    }
  }

  @Test
  public void heldOutputAcrossFlush_isReleasedOnce() throws Exception {
    decoder = create(new Backend(false, value -> value));
    queue(1000, false);
    VideoDecoderOutputBuffer old = next();
    decoder.flush();
    assertFalse(((High10DecoderLifecycle) decoder).isCurrentOutput(old));
    queue(2000, false);
    VideoDecoderOutputBuffer fresh = next();
    assertNotSame(old, fresh);
    assertEquals(1000, old.timeUs); // Flush must not clear renderer-owned storage.
    assertEquals(2000, fresh.timeUs);
    old.release();
    assertThrows(IllegalStateException.class, old::release);
    fresh.release();
  }

  @Test
  public void releaseWhileOutputPoolFull_wakesWorker() throws Exception {
    Backend backend = new Backend(false, value -> value);
    backend.framesPerPacket = 17;
    backend.sixteenDecoded = new CountDownLatch(16);
    decoder = create(backend);
    queue(0, false);
    assertTrue(backend.sixteenDecoded.await(2, TimeUnit.SECONDS));
    releaseWithinDeadline();
    assertTrue(backend.released);
    decoder.release(); // Idempotent, no second native teardown.
    assertEquals(1, backend.releaseCount);
  }

  @Test
  public void flushDuringDrain_wakesWorkerAndRemovesOldEos() throws Exception {
    Backend backend = new Backend(true, value -> value);
    backend.sixteenDecoded = new CountDownLatch(16);
    decoder = create(backend);
    for (int i = 0; i < 17; i++) queue(i, false);
    queue(0, true);
    assertTrue(backend.sixteenDecoded.await(2, TimeUnit.SECONDS));
    decoder.flush();
    queue(100_000, false);
    queue(0, true);
    assertEquals(Collections.singletonList(100_000L), collectToEos());
  }

  @Test
  public void releaseDuringDrain_wakesWorker() throws Exception {
    Backend backend = new Backend(true, value -> value);
    backend.sixteenDecoded = new CountDownLatch(16);
    decoder = create(backend);
    for (int i = 0; i < 17; i++) queue(i, false);
    queue(0, true);
    assertTrue(backend.sixteenDecoded.await(2, TimeUnit.SECONDS));
    releaseWithinDeadline();
    assertTrue(backend.released);
  }

  @Test
  public void inputCapacityNeverExceedsFour() throws Exception {
    Backend backend = new Backend(false, value -> value);
    backend.sendEntered = new CountDownLatch(1);
    backend.allowSend = new CountDownLatch(1);
    decoder = create(backend);
    try {
      queue(0, false);
      assertTrue(backend.sendEntered.await(2, TimeUnit.SECONDS));
      queue(1000, false);
      queue(2000, false);
      queue(3000, false);
      assertNull(decoder.dequeueInputBuffer());
    } finally {
      backend.allowSend.countDown();
    }
    queue(0, true);
    assertEquals(Arrays.asList(0L, 1000L, 2000L, 3000L), collectToEos());
  }

  @Test
  public void outputCapacityNeverExceedsSixteen_includingHeldBuffers() throws Exception {
    Backend backend = new Backend(false, value -> value);
    backend.framesPerPacket = 17;
    backend.sixteenDecoded = new CountDownLatch(16);
    decoder = create(backend);
    queue(0, false);
    assertTrue(backend.sixteenDecoded.await(2, TimeUnit.SECONDS));
    List<VideoDecoderOutputBuffer> held = new ArrayList<>();
    for (int i = 0; i < 16; i++) held.add(next());
    assertNull(decoder.dequeueOutputBuffer());
    assertEquals(16, backend.framesReceived);
    held.remove(0).release();
    VideoDecoderOutputBuffer seventeenth = next();
    assertEquals(16, seventeenth.timeUs);
    seventeenth.release();
    for (VideoDecoderOutputBuffer output : held) output.release();
  }

  @Test
  public void eosIsSingleAndPostEosInputIsRejected_flushRestarts() throws Exception {
    decoder = create(new Backend(false, value -> value));
    queue(1000, false);
    queue(0, true);
    assertEquals(Collections.singletonList(1000L), collectToEos());
    assertNull(decoder.dequeueOutputBuffer());
    assertNull(decoder.dequeueInputBuffer());
    decoder.flush();
    queue(2000, false);
    queue(0, true);
    assertEquals(Collections.singletonList(2000L), collectToEos());
  }

  @Test
  public void missingTimestamp_isExplicitError() throws Exception {
    assertMissingTimestamp(false);
  }

  @Test
  public void missingTimestampDuringDrain_isExplicitError() throws Exception {
    assertMissingTimestamp(true);
  }

  private void assertMissingTimestamp(boolean delayed) throws Exception {
    Backend backend = new Backend(delayed, value -> value);
    backend.missingTimestamp = true;
    decoder = create(backend);
    queue(1000, false);
    if (delayed) queue(0, true);
    FfmpegHigh10VideoDecoderException error = assertThrows(
        FfmpegHigh10VideoDecoderException.class, this::next);
    assertTrue(error.getMessage().contains("no presentation timestamp"));
  }

  @Test
  public void initializationFailureHasNoLiveWorker() {
    Backend backend = new Backend(false, value -> value);
    assertThrows(FfmpegHigh10VideoDecoderException.class, () -> new High10DecoderLifecycle(
        backend, 4, 16, 0, owner -> { throw new IllegalStateException("Cannot allocate output"); }));
    assertTrue(backend.released);
    assertFalse(Thread.getAllStackTraces().keySet().stream()
        .anyMatch(thread -> thread.isAlive() && thread.getName().equals("nuvio:hi10:decode")));
  }

  @Test
  public void eagainRetainsPacketOrder() throws Exception {
    Backend backend = new Backend(false, value -> value);
    backend.scriptedEagain = true;
    decoder = create(backend);
    queue(0, false);
    queue(1000, false);
    queue(2000, false);
    queue(0, true);
    assertEquals(Arrays.asList(0L, 1000L, 2000L), collectToEos());
    assertEquals(Arrays.asList(0L, 1000L, 1000L, 2000L), backend.sendAttempts);
  }

  @Test
  public void invalidInputDoesNotDeadlock() throws Exception {
    Backend backend = new Backend(false, value -> value);
    backend.invalidFirst = true;
    decoder = create(backend);
    queue(0, false);
    queue(1000, false);
    queue(0, true);
    VideoDecoderOutputBuffer output = next();
    assertEquals(1000, output.timeUs);
    assertEquals(1, output.skippedOutputBufferCount);
    output.release();
    VideoDecoderOutputBuffer eos = next();
    assertTrue(eos.isEndOfStream());
    eos.release();
  }

  @Test
  public void sendReceiveWithoutProgress_isErrorNotBusyLoop() throws Exception {
    Backend backend = new Backend(false, value -> value);
    backend.noProgress = true;
    decoder = create(backend);
    queue(1000, false);
    FfmpegHigh10VideoDecoderException error = assertThrows(
        FfmpegHigh10VideoDecoderException.class, this::next);
    assertTrue(error.getMessage().contains("no progress"));
    releaseWithinDeadline();
  }

  @Test
  public void firstSampleFlag_survivesDelayedDecode() throws Exception {
    Backend backend = new Backend(true, value -> value);
    decoder = create(backend);
    DecoderInputBuffer first = decoder.dequeueInputBuffer();
    assertNotNull(first);
    first.timeUs = 0;
    first.addFlag(C.BUFFER_FLAG_FIRST_SAMPLE);
    decoder.queueInputBuffer(first);
    queue(1000, false);
    queue(0, true);
    VideoDecoderOutputBuffer output = next();
    try {
      assertTrue(output.isFirstSample());
      assertEquals(0, output.timeUs);
    } finally {
      output.release();
    }
  }

  @Test
  public void flushDiscardsInFlightError_oldGenerationCannotPoisonNewDecode() throws Exception {
    Backend backend = new Backend(false, value -> value);
    backend.receiveEntered = new CountDownLatch(1);
    backend.allowReceive = new CountDownLatch(1);
    backend.holdNextReceive = true;
    backend.failNextReceive = true;
    decoder = create(backend);
    queue(1000, false);
    assertTrue(backend.receiveEntered.await(2, TimeUnit.SECONDS));
    decoder.flush();
    backend.allowReceive.countDown();
    queue(2000, false);
    VideoDecoderOutputBuffer output = next();
    assertEquals(2000, output.timeUs);
    output.release();
  }

  private void releaseWithinDeadline() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      executor.submit(decoder::release).get(2, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
  }

  private Decoder<DecoderInputBuffer, VideoDecoderOutputBuffer, FfmpegHigh10VideoDecoderException>
      create(Backend backend) throws FfmpegHigh10VideoDecoderException {
    return new High10DecoderLifecycle(backend, 4, 16, 0, VideoDecoderOutputBuffer::new);
  }

  private void queue(long pts, boolean eos) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    DecoderInputBuffer input;
    while ((input = decoder.dequeueInputBuffer()) == null && System.nanoTime() < deadline) {
      Thread.yield();
    }
    assertNotNull("Input did not become available within deadline", input);
    input.timeUs = pts;
    if (eos) input.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
    decoder.queueInputBuffer(input);
  }

  private VideoDecoderOutputBuffer next() throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    VideoDecoderOutputBuffer output;
    while ((output = decoder.dequeueOutputBuffer()) == null && System.nanoTime() < deadline) {
      Thread.yield();
    }
    assertNotNull("Output did not become available within deadline", output);
    return output;
  }

  private List<Long> collectToEos() throws Exception {
    List<Long> pts = new ArrayList<>();
    for (int i = 0; i < 32; i++) {
      VideoDecoderOutputBuffer output = next();
      try {
        if (output.isEndOfStream()) return pts;
        pts.add(output.timeUs);
      } finally {
        output.release();
      }
    }
    throw new AssertionError("No EOS within bounded output count");
  }

  private static final class Backend implements High10DecodeBackend {
    final boolean delayed;
    final LongUnaryOperator outputPts;
    final List<Long> pending = new ArrayList<>();
    final ArrayDeque<Long> ready = new ArrayDeque<>();
    boolean draining;
    int drainCount;
    int framesPerPacket = 1;
    volatile int framesReceived;
    volatile boolean released;
    int releaseCount;
    boolean missingTimestamp;
    volatile boolean holdNextReceive;
    volatile CountDownLatch receiveEntered;
    volatile CountDownLatch allowReceive;
    CountDownLatch sendEntered;
    CountDownLatch allowSend;
    CountDownLatch sixteenDecoded;
    boolean scriptedEagain;
    boolean returnedAgain;
    boolean noProgress;
    boolean invalidFirst;
    boolean failNextReceive;
    final List<Long> sendAttempts = new ArrayList<>();

    Backend(boolean delayed, LongUnaryOperator outputPts) {
      this.delayed = delayed;
      this.outputPts = outputPts;
    }

    @Override public int send(DecoderInputBuffer input) throws FfmpegHigh10VideoDecoderException {
      if (allowSend != null && allowSend.getCount() > 0) {
        sendEntered.countDown();
        await(allowSend);
      }
      sendAttempts.add(input.timeUs);
      if (noProgress) return -3;
      if (invalidFirst && input.timeUs == 0) return -1;
      if (scriptedEagain) {
        if (input.timeUs == 0) { pending.add(0L); return 0; }
        if (!returnedAgain) {
          ready.addAll(pending);
          pending.clear();
          returnedAgain = true;
          return -3;
        }
      }
      long pts = outputPts.applyAsLong(input.timeUs);
      for (int i = 0; i < framesPerPacket; i++) {
        if (delayed) pending.add(pts + i); else ready.add(pts + i);
      }
      return 0;
    }

    @Override public int beginDrain() {
      drainCount++;
      draining = true;
      Collections.sort(pending);
      ready.addAll(pending);
      pending.clear();
      return 0;
    }

    @Override public int receive(VideoDecoderOutputBuffer output) throws FfmpegHigh10VideoDecoderException {
      Long pts = ready.poll();
      if (pts == null) return draining ? -5 : -3;
      if (holdNextReceive) {
        holdNextReceive = false;
        receiveEntered.countDown();
        await(allowReceive);
      }
      if (failNextReceive) {
        failNextReceive = false;
        throw new FfmpegHigh10VideoDecoderException("Old-generation decode failed");
      }
      if (!missingTimestamp) output.init(pts, C.VIDEO_OUTPUT_MODE_SURFACE_YUV, null);
      framesReceived++;
      if (sixteenDecoded != null) sixteenDecoded.countDown();
      return 0;
    }

    @Override public void flush() {
      pending.clear();
      ready.clear();
      draining = false;
    }

    @Override public void release() { released = true; releaseCount++; }

    private static void await(CountDownLatch latch) throws FfmpegHigh10VideoDecoderException {
      try {
        if (!latch.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("Backend barrier timed out");
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new FfmpegHigh10VideoDecoderException("Backend barrier interrupted", error);
      }
    }
  }

}
