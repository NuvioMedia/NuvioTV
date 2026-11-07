/* SPDX-License-Identifier: Apache-2.0 */
package com.nuvio.hi10video;

import static org.junit.Assert.*;
import org.junit.Test;

public final class High10SurfaceSubmissionTest {
  @Test public void nativeResultsAreNotConfusedWithSuccessfulPosts() throws Exception {
    assertTrue(FfmpegHigh10VideoDecoder.wasSubmitted(0));
    assertFalse(FfmpegHigh10VideoDecoder.wasSubmitted(1));
    for (int error : new int[] {-1, -2, -3, -4, -5, 2}) {
      assertThrows(FfmpegHigh10VideoDecoderException.class,
          () -> FfmpegHigh10VideoDecoder.wasSubmitted(error));
    }
  }

  @Test public void surfaceDropsRemainSeparateFromSuccessfulSubmissionCounts() {
    High10PipelineProbe probe = new High10PipelineProbe(() -> 0);
    Object frame = new Object();
    probe.frameDecoded(frame);
    probe.surfaceDropped();
    probe.frameReleased(frame);
    assertEquals(0, probe.snapshot().renderedFrames);
    assertEquals(1, probe.snapshot().surfaceDroppedFrames);
    assertEquals(0, probe.snapshot().outputDepth);
    FfmpegHigh10VideoPerformance performance = new FfmpegHigh10VideoPerformance(
        7, 2, 0, 2, 0, new long[0], probe.snapshot());
    assertEquals(7, performance.rendered); // Media3 attempt counter remains distinct.
    assertEquals(0, performance.renderedFrames);
    assertEquals(1, performance.surfaceDroppedFrames);
  }
}
