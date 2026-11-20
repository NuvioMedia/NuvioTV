package com.nuvio.hi10video;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class FfmpegHigh10VideoPerformanceTest {
  @Test
  public void constructor_mapsNativeMicrosecondsToMilliseconds() {
    long[] values = new long[2 + FfmpegHigh10VideoPerformance.STAGE_NAMES.length * 6];
    values[0] = 4;
    values[1] = 1;
    values[2] = 120;
    values[3] = 2_500;
    values[4] = 2_000;
    values[5] = 4_000;
    values[6] = 5_000;
    values[7] = 8_000;

    FfmpegHigh10VideoPerformance result =
        new FfmpegHigh10VideoPerformance(100, 3, 2, 4, 1, values);

    assertEquals(4, result.threadCount);
    assertEquals(1, result.activeThreadType);
    assertEquals(100, result.rendered);
    assertEquals(3, result.dropped);
    assertEquals(2, result.skipped);
    assertEquals(4, result.maxConsecutiveDropped);
    assertEquals(1, result.pendingInputs);
    assertEquals(120, result.stage("send").count);
    assertEquals(2.5, result.stage("send").averageMs, 0.001);
    assertEquals(5.0, result.stage("send").p99Ms, 0.001);
    assertEquals(8.0, result.stage("send").maxMs, 0.001);
  }

  @Test
  public void constructor_mapsNativePipelineOutcomes() {
    long[] values = new long[2 + FfmpegHigh10VideoPerformance.STAGE_NAMES.length * 6 + 6];
    int offset = values.length - 6;
    values[offset] = 120;
    values[offset + 1] = 3;
    values[offset + 2] = 117;
    values[offset + 3] = 100;
    values[offset + 4] = 17;
    values[offset + 5] = 0;

    FfmpegHigh10VideoPerformance result =
        new FfmpegHigh10VideoPerformance(100, 3, 2, 4, 1, values);

    assertEquals(120, result.sendAttempts);
    assertEquals(3, result.sendAgain);
    assertEquals(117, result.sendAccepted);
    assertEquals(100, result.receiveFrames);
    assertEquals(17, result.receiveAgain);
    assertEquals(0, result.receiveEof);
  }
}
