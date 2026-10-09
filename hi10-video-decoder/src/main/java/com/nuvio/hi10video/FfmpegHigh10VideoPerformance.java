package com.nuvio.hi10video;

import androidx.annotation.Nullable;

/** Read-only, low-frequency diagnostics for the active High10 software renderer. */
public final class FfmpegHigh10VideoPerformance {
  public static final String[] STAGE_NAMES = {
    "send", "receive", "frame_setup", "sws", "intermediate_copy", "surface_lock",
    "surface_copy", "surface_post"
  };

  public final int rendered;
  public final int dropped;
  public final int skipped;
  public final int maxConsecutiveDropped;
  public final int pendingInputs;
  public final int threadCount;
  public final int activeThreadType;
  public final long sendAttempts;
  public final long sendAgain;
  public final long sendAccepted;
  public final long receiveFrames;
  public final long receiveAgain;
  public final long receiveEof;
  public final long inputQueued;
  public final long decodeStarted;
  public final long decodedFrames;
  public final long renderedFrames;
  public final int inputQueueDepth;
  public final int inputQueueHighWater;
  public final int outputDepth;
  public final int outputHighWater;
  public final int pendingHighWater;
  public final long averagePacketAgeUs;
  public final long maxPacketAgeUs;
  public final long averageDecodedFrameAgeUs;
  public final long maxDecodedFrameAgeUs;
  public final Stage[] stages;

  FfmpegHigh10VideoPerformance(
      int rendered,
      int dropped,
      int skipped,
      int maxConsecutiveDropped,
      int pendingInputs,
      long[] nativeValues) {
    this(rendered, dropped, skipped, maxConsecutiveDropped, pendingInputs, nativeValues,
        new High10PipelineProbe(() -> 0).snapshot());
  }

  FfmpegHigh10VideoPerformance(
      int rendered,
      int dropped,
      int skipped,
      int maxConsecutiveDropped,
      int pendingInputs,
      long[] nativeValues,
      High10PipelineProbe.Snapshot pipeline) {
    this.rendered = rendered;
    this.dropped = dropped;
    this.skipped = skipped;
    this.maxConsecutiveDropped = maxConsecutiveDropped;
    this.pendingInputs = pendingInputs;
    threadCount = nativeValues.length >= 2 ? (int) nativeValues[0] : 0;
    activeThreadType = nativeValues.length >= 2 ? (int) nativeValues[1] : 0;
    int pipelineOffset = 2 + STAGE_NAMES.length * 6;
    sendAttempts = nativeValue(nativeValues, pipelineOffset);
    sendAgain = nativeValue(nativeValues, pipelineOffset + 1);
    sendAccepted = nativeValue(nativeValues, pipelineOffset + 2);
    receiveFrames = nativeValue(nativeValues, pipelineOffset + 3);
    receiveAgain = nativeValue(nativeValues, pipelineOffset + 4);
    receiveEof = nativeValue(nativeValues, pipelineOffset + 5);
    inputQueued = pipeline.inputQueued;
    decodeStarted = pipeline.decodeStarted;
    decodedFrames = pipeline.decodedFrames;
    renderedFrames = pipeline.renderedFrames;
    inputQueueDepth = pipeline.inputQueueDepth;
    inputQueueHighWater = pipeline.inputQueueHighWater;
    outputDepth = pipeline.outputDepth;
    outputHighWater = pipeline.outputHighWater;
    pendingHighWater = pipeline.pendingHighWater;
    averagePacketAgeUs = pipeline.averagePacketAgeUs;
    maxPacketAgeUs = pipeline.maxPacketAgeUs;
    averageDecodedFrameAgeUs = pipeline.averageDecodedFrameAgeUs;
    maxDecodedFrameAgeUs = pipeline.maxDecodedFrameAgeUs;
    stages = new Stage[STAGE_NAMES.length];
    for (int i = 0; i < stages.length; i++) {
      int offset = 2 + i * 6;
      stages[i] = nativeValues.length >= offset + 6
          ? new Stage(
              STAGE_NAMES[i],
              nativeValues[offset],
              microsToMillis(nativeValues[offset + 1]),
              microsToMillis(nativeValues[offset + 2]),
              microsToMillis(nativeValues[offset + 3]),
              microsToMillis(nativeValues[offset + 4]),
              microsToMillis(nativeValues[offset + 5]))
          : new Stage(STAGE_NAMES[i], 0, 0, 0, 0, 0, 0);
    }
  }

  private static long nativeValue(long[] values, int index) {
    return index < values.length ? values[index] : 0;
  }

  @Nullable
  public Stage stage(String name) {
    for (Stage stage : stages) {
      if (stage.name.equals(name)) {
        return stage;
      }
    }
    return null;
  }

  private static double microsToMillis(long value) {
    return value / 1000.0;
  }

  public static final class Stage {
    public final String name;
    public final long count;
    public final double averageMs;
    public final double p50Ms;
    public final double p95Ms;
    public final double p99Ms;
    public final double maxMs;

    Stage(
        String name,
        long count,
        double averageMs,
        double p50Ms,
        double p95Ms,
        double p99Ms,
        double maxMs) {
      this.name = name;
      this.count = count;
      this.averageMs = averageMs;
      this.p50Ms = p50Ms;
      this.p95Ms = p95Ms;
      this.p99Ms = p99Ms;
      this.maxMs = maxMs;
    }
  }
}
