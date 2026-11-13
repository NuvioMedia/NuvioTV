package com.nuvio.hi10video;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class High10PipelineProbeTest {
  @Test
  public void snapshotTracksQueueDepthAndFrameAges() {
    AtomicLong nowNs = new AtomicLong(1_000_000);
    High10PipelineProbe probe = new High10PipelineProbe(nowNs::get);
    Object inputA = new Object();
    Object inputB = new Object();
    Object frame = new Object();

    probe.inputQueued(inputA);
    probe.inputQueued(inputB);
    nowNs.set(4_000_000);
    probe.decodeStarted(inputA);
    nowNs.set(5_000_000);
    probe.frameDecoded(frame);
    nowNs.set(13_000_000);
    probe.frameRendered(frame);

    High10PipelineProbe.Snapshot snapshot = probe.snapshot();
    assertEquals(2, snapshot.inputQueued);
    assertEquals(1, snapshot.decodeStarted);
    assertEquals(1, snapshot.inputQueueDepth);
    assertEquals(2, snapshot.inputQueueHighWater);
    assertEquals(1, snapshot.decodedFrames);
    assertEquals(1, snapshot.renderedFrames);
    assertEquals(1, snapshot.outputDepth);
    assertEquals(1, snapshot.outputHighWater);
    assertEquals(3_000, snapshot.maxPacketAgeUs);
    assertEquals(8_000, snapshot.maxDecodedFrameAgeUs);
    probe.frameReleased(frame);
    assertEquals(0, probe.snapshot().outputDepth);
  }

  @Test
  public void resetClearsOutstandingAgesAndCounts() {
    AtomicLong nowNs = new AtomicLong(1_000_000);
    High10PipelineProbe probe = new High10PipelineProbe(nowNs::get);
    Object input = new Object();
    probe.inputQueued(input);
    probe.reset();
    nowNs.set(10_000_000);
    probe.decodeStarted(input);

    High10PipelineProbe.Snapshot snapshot = probe.snapshot();
    assertEquals(0, snapshot.inputQueueDepth);
    assertEquals(0, snapshot.maxPacketAgeUs);
  }
}
