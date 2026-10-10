package com.nuvio.hi10video;

import java.util.IdentityHashMap;
import java.util.function.LongSupplier;

/** Low-frequency diagnostic counters; no decoder scheduling decisions depend on these values. */
final class High10PipelineProbe {
  private final LongSupplier clockNs;
  private final IdentityHashMap<Object, Long> inputQueuedAtNs = new IdentityHashMap<>();
  private final IdentityHashMap<Object, Long> frameDecodedAtNs = new IdentityHashMap<>();
  private long inputQueued;
  private long decodeStarted;
  private long decodedFrames;
  private long renderedFrames;
  private long surfaceDroppedFrames;
  private long packetAgeTotalUs;
  private long packetAgeCount;
  private long maxPacketAgeUs;
  private long decodedFrameAgeTotalUs;
  private long decodedFrameAgeCount;
  private long maxDecodedFrameAgeUs;
  private int inputQueueHighWater;
  private int outputHighWater;
  private int pendingHighWater;

  High10PipelineProbe(LongSupplier clockNs) {
    this.clockNs = clockNs;
  }

  synchronized void inputQueued(Object input) {
    inputQueuedAtNs.put(input, clockNs.getAsLong());
    inputQueued++;
    inputQueueHighWater = Math.max(inputQueueHighWater, inputQueuedAtNs.size());
  }

  synchronized void decodeStarted(Object input) {
    Long queuedAtNs = inputQueuedAtNs.remove(input);
    if (queuedAtNs == null) {
      return;
    }
    decodeStarted++;
    long ageUs = Math.max(0, clockNs.getAsLong() - queuedAtNs) / 1_000;
    packetAgeTotalUs += ageUs;
    packetAgeCount++;
    maxPacketAgeUs = Math.max(maxPacketAgeUs, ageUs);
  }

  synchronized void frameDecoded(Object output) {
    frameDecodedAtNs.put(output, clockNs.getAsLong());
    decodedFrames++;
    outputHighWater = Math.max(outputHighWater, frameDecodedAtNs.size());
  }

  synchronized void frameRendered(Object output) {
    Long decodedAtNs = frameDecodedAtNs.get(output);
    if (decodedAtNs == null) {
      return;
    }
    renderedFrames++;
    long ageUs = Math.max(0, clockNs.getAsLong() - decodedAtNs) / 1_000;
    decodedFrameAgeTotalUs += ageUs;
    decodedFrameAgeCount++;
    maxDecodedFrameAgeUs = Math.max(maxDecodedFrameAgeUs, ageUs);
  }

  synchronized void frameReleased(Object output) {
    frameDecodedAtNs.remove(output);
  }

  synchronized void surfaceDropped() { surfaceDroppedFrames++; }

  synchronized void pendingDepth(int depth) {
    pendingHighWater = Math.max(pendingHighWater, depth);
  }

  synchronized void reset() {
    inputQueuedAtNs.clear();
    frameDecodedAtNs.clear();
    inputQueueHighWater = 0;
    outputHighWater = 0;
    pendingHighWater = 0;
    packetAgeTotalUs = 0;
    packetAgeCount = 0;
    maxPacketAgeUs = 0;
    decodedFrameAgeTotalUs = 0;
    decodedFrameAgeCount = 0;
    maxDecodedFrameAgeUs = 0;
  }

  synchronized Snapshot snapshot() {
    return new Snapshot(
        inputQueued,
        decodeStarted,
        decodedFrames,
        renderedFrames,
        surfaceDroppedFrames,
        inputQueuedAtNs.size(),
        inputQueueHighWater,
        frameDecodedAtNs.size(),
        outputHighWater,
        pendingHighWater,
        packetAgeCount == 0 ? 0 : packetAgeTotalUs / packetAgeCount,
        maxPacketAgeUs,
        decodedFrameAgeCount == 0 ? 0 : decodedFrameAgeTotalUs / decodedFrameAgeCount,
        maxDecodedFrameAgeUs);
  }

  static final class Snapshot {
    final long inputQueued;
    final long decodeStarted;
    final long decodedFrames;
    final long renderedFrames;
    final long surfaceDroppedFrames;
    final int inputQueueDepth;
    final int inputQueueHighWater;
    final int outputDepth;
    final int outputHighWater;
    final int pendingHighWater;
    final long averagePacketAgeUs;
    final long maxPacketAgeUs;
    final long averageDecodedFrameAgeUs;
    final long maxDecodedFrameAgeUs;

    Snapshot(
        long inputQueued,
        long decodeStarted,
        long decodedFrames,
        long renderedFrames,
        long surfaceDroppedFrames,
        int inputQueueDepth,
        int inputQueueHighWater,
        int outputDepth,
        int outputHighWater,
        int pendingHighWater,
        long averagePacketAgeUs,
        long maxPacketAgeUs,
        long averageDecodedFrameAgeUs,
        long maxDecodedFrameAgeUs) {
      this.inputQueued = inputQueued;
      this.decodeStarted = decodeStarted;
      this.decodedFrames = decodedFrames;
      this.renderedFrames = renderedFrames;
      this.surfaceDroppedFrames = surfaceDroppedFrames;
      this.inputQueueDepth = inputQueueDepth;
      this.inputQueueHighWater = inputQueueHighWater;
      this.outputDepth = outputDepth;
      this.outputHighWater = outputHighWater;
      this.pendingHighWater = pendingHighWater;
      this.averagePacketAgeUs = averagePacketAgeUs;
      this.maxPacketAgeUs = maxPacketAgeUs;
      this.averageDecodedFrameAgeUs = averageDecodedFrameAgeUs;
      this.maxDecodedFrameAgeUs = maxDecodedFrameAgeUs;
    }
  }
}
