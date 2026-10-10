/* SPDX-License-Identifier: Apache-2.0 */
package com.nuvio.hi10video;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.decoder.Decoder;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.DecoderOutputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;

/** One synchronous decode/conversion worker with fixed ownership pools and generation fencing. */
final class High10DecoderLifecycle implements Decoder<
    DecoderInputBuffer, VideoDecoderOutputBuffer, FfmpegHigh10VideoDecoderException> {
  interface OutputFactory {
    VideoDecoderOutputBuffer create(DecoderOutputBuffer.Owner<VideoDecoderOutputBuffer> owner);
  }

  interface Observer {
    default void inputStarted(DecoderInputBuffer input) {}
    default void frameDecoded(VideoDecoderOutputBuffer output) {}
    default void outputReleased(VideoDecoderOutputBuffer output) {}
    default void flushed() {}
  }

  private static final int AVAILABLE = 0;
  private static final int CALLER = 1;
  private static final int QUEUED = 2;
  private static final int WORKER = 3;
  private final Object lock = new Object();
  private final Object releaseLock = new Object();
  private final High10DecodeBackend backend;
  private final Observer observer;
  private final DecoderInputBuffer[] inputs;
  private final VideoDecoderOutputBuffer[] outputs;
  private final int[] inputOwners;
  private final int[] outputOwners;
  private final long[] outputGenerations;
  private final int[] inputQueue;
  private final int[] outputQueue;
  private final Thread worker;
  private int inputHead;
  private int inputCount;
  private int outputHead;
  private int outputCount;
  private int dequeuedInput = -1;
  private int skippedOutputs;
  private long generation;
  private long outputStartTimeUs = C.TIME_UNSET;
  private boolean inputEnded;
  private boolean firstSamplePending;
  private boolean released;
  private boolean backendReleased;
  @Nullable private FfmpegHigh10VideoDecoderException failure;

  High10DecoderLifecycle(High10DecodeBackend backend, int inputSlots, int outputSlots,
      int inputSize, OutputFactory outputFactory) throws FfmpegHigh10VideoDecoderException {
    // FFmpeg 7.0.2 padding; production constructor supplies the native library's value.
    this(backend, inputSlots, outputSlots, inputSize, 64, outputFactory, new Observer() {});
  }

  High10DecoderLifecycle(High10DecodeBackend backend, int inputSlots, int outputSlots,
      int inputSize, int inputPadding, OutputFactory outputFactory, Observer observer)
      throws FfmpegHigh10VideoDecoderException {
    this.backend = backend;
    this.observer = observer;
    try {
      if (inputSlots <= 0 || outputSlots <= 0 || inputSize < 0 || inputPadding < 0) {
        throw new IllegalArgumentException("Invalid decoder pool size");
      }
      inputs = new DecoderInputBuffer[inputSlots];
      outputs = new VideoDecoderOutputBuffer[outputSlots];
      inputOwners = new int[inputSlots];
      outputOwners = new int[outputSlots];
      outputGenerations = new long[outputSlots];
      inputQueue = new int[inputSlots];
      outputQueue = new int[outputSlots];
      for (int i = 0; i < inputSlots; i++) {
        inputs[i] = new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT, inputPadding);
        inputs[i].ensureSpaceForWrite(inputSize);
      }
      for (int i = 0; i < outputSlots; i++) {
        outputs[i] = outputFactory.create(this::releaseOutput);
        if (outputs[i] == null) throw new IllegalStateException("Missing output buffer");
      }
      worker = new Thread(this::run, "nuvio:hi10:decode");
      worker.start(); // No worker exists until all native/pool initialization succeeded.
    } catch (RuntimeException | Error error) {
      try {
        backend.release();
      } catch (RuntimeException | Error cleanupError) {
        error.addSuppressed(cleanupError);
      }
      throw new FfmpegHigh10VideoDecoderException("Decoder initialization failed", error);
    }
  }

  @Override public String getName() { return "nuvio-hi10-lifecycle"; }

  @Override public void setOutputStartTimeUs(long timeUs) {
    synchronized (lock) { outputStartTimeUs = timeUs; }
  }

  @Override @Nullable public DecoderInputBuffer dequeueInputBuffer() throws FfmpegHigh10VideoDecoderException {
    synchronized (lock) {
      throwIfFailed();
      if (released || inputEnded) return null;
      if (dequeuedInput != -1) throw new IllegalStateException("An input is already dequeued");
      for (int i = 0; i < inputs.length; i++) {
        if (inputOwners[i] == AVAILABLE) {
          inputOwners[i] = CALLER;
          dequeuedInput = i;
          return inputs[i];
        }
      }
      return null;
    }
  }

  @Override public void queueInputBuffer(DecoderInputBuffer input) throws FfmpegHigh10VideoDecoderException {
    synchronized (lock) {
      throwIfFailed();
      if (released || inputEnded || dequeuedInput < 0 || inputs[dequeuedInput] != input) {
        throw new IllegalStateException("Input does not belong to the active decoder generation");
      }
      int index = dequeuedInput;
      dequeuedInput = -1;
      inputOwners[index] = QUEUED;
      inputQueue[(inputHead + inputCount) % inputQueue.length] = index;
      inputCount++;
      inputEnded = input.isEndOfStream();
      lock.notifyAll();
    }
  }

  @Override @Nullable public VideoDecoderOutputBuffer dequeueOutputBuffer()
      throws FfmpegHigh10VideoDecoderException {
    synchronized (lock) {
      throwIfFailed();
      if (released || outputCount == 0) return null;
      int index = outputQueue[outputHead];
      outputHead = (outputHead + 1) % outputQueue.length;
      outputCount--;
      outputOwners[index] = CALLER;
      return outputs[index];
    }
  }

  @Override public void flush() {
    synchronized (lock) {
      if (released) return;
      generation++;
      inputEnded = false;
      firstSamplePending = false;
      skippedOutputs = 0;
      if (dequeuedInput >= 0) returnInput(dequeuedInput);
      dequeuedInput = -1;
      while (inputCount > 0) {
        returnInput(inputQueue[inputHead]);
        inputHead = (inputHead + 1) % inputQueue.length;
        inputCount--;
      }
      while (outputCount > 0) {
        returnOutput(outputQueue[outputHead]);
        outputHead = (outputHead + 1) % outputQueue.length;
        outputCount--;
      }
      observer.flushed();
      // Worker-owned storage and renderer-held outputs are not recycled here.
      lock.notifyAll();
    }
  }

  @Override public void release() {
    synchronized (releaseLock) {
      synchronized (lock) {
        released = true;
        generation++;
        lock.notifyAll();
      }
      boolean interrupted = false;
      for (;;) {
        try {
          worker.join(); // Neither queue nor native/render lock is held.
          break;
        } catch (InterruptedException error) {
          interrupted = true; // Never free native context while worker still uses it.
        }
      }
      try {
        if (!backendReleased) {
          backendReleased = true;
          backend.release();
        }
      } finally {
        if (interrupted) Thread.currentThread().interrupt();
      }
    }
  }

  boolean isCurrentOutput(VideoDecoderOutputBuffer output) {
    synchronized (lock) {
      int index = outputIndex(output);
      return index >= 0 && !released && outputOwners[index] == CALLER
          && outputGenerations[index] == generation;
    }
  }

  int pendingInputCount() {
    synchronized (lock) {
      int count = inputCount;
      for (int owner : inputOwners) if (owner == WORKER) count++;
      return count;
    }
  }

  private void releaseOutput(VideoDecoderOutputBuffer output) {
    synchronized (lock) {
      int index = outputIndex(output);
      if (index < 0 || outputOwners[index] != CALLER) {
        throw new IllegalStateException("Output is not held by its caller");
      }
      returnOutput(index);
      lock.notifyAll();
    }
  }

  private int outputIndex(VideoDecoderOutputBuffer output) {
    for (int i = 0; i < outputs.length; i++) if (outputs[i] == output) return i;
    return -1;
  }

  private void returnInput(int index) {
    inputs[index].clear();
    inputs[index].format = null;
    inputOwners[index] = AVAILABLE;
  }

  private void returnOutput(int index) {
    observer.outputReleased(outputs[index]);
    outputs[index].clear();
    outputs[index].format = null;
    outputOwners[index] = AVAILABLE;
  }

  private boolean active(long workGeneration) {
    return !released && failure == null && generation == workGeneration;
  }

  private void throwIfFailed() throws FfmpegHigh10VideoDecoderException {
    if (failure != null) throw failure;
  }

  private void run() {
    long workerGeneration = 0;
    try {
      for (;;) {
        int index = -1;
        long workGeneration;
        synchronized (lock) {
          while (!released && generation == workerGeneration && inputCount == 0) lock.wait();
          if (released) return;
          workGeneration = generation;
          if (workerGeneration == workGeneration) {
            index = inputQueue[inputHead];
            inputHead = (inputHead + 1) % inputQueue.length;
            inputCount--;
            inputOwners[index] = WORKER;
          }
        }
        if (index == -1) {
          backend.flush();
          workerGeneration = workGeneration;
          continue;
        }
        try {
          processInput(index, workGeneration);
        } catch (Exception | OutOfMemoryError error) {
          synchronized (lock) {
            // Flush invalidates failures from cancelled work as well as its frames.
            if (active(workGeneration)) throw error;
          }
        } finally {
          synchronized (lock) {
            if (inputOwners[index] == WORKER) returnInput(index);
            lock.notifyAll();
          }
        }
      }
    } catch (Exception | OutOfMemoryError error) {
      synchronized (lock) {
        if (!released) failure = error instanceof FfmpegHigh10VideoDecoderException
            ? (FfmpegHigh10VideoDecoderException) error
            : new FfmpegHigh10VideoDecoderException("Unexpected decoder failure", error);
        lock.notifyAll();
      }
    }
  }

  private void processInput(int index, long workGeneration) throws Exception {
    DecoderInputBuffer input = inputs[index];
    boolean eos = input.isEndOfStream();
    boolean firstSample = input.isFirstSample();
    observer.inputStarted(input);
    for (;;) {
      synchronized (lock) { if (!active(workGeneration)) return; }
      int result = eos ? backend.beginDrain() : backend.send(input);
      if (result == High10DecodeBackend.ACCEPTED) break;
      if (result == High10DecodeBackend.INVALID_DATA && !eos) {
        synchronized (lock) { if (active(workGeneration)) skippedOutputs++; }
        return;
      }
      if (result != High10DecodeBackend.AGAIN) {
        throw new FfmpegHigh10VideoDecoderException("Packet/drain rejected: " + result);
      }
      int received = receiveAvailable(workGeneration, false);
      synchronized (lock) { if (!active(workGeneration)) return; }
      if (received == 0) throw new FfmpegHigh10VideoDecoderException("Send/receive made no progress");
    }
    synchronized (lock) {
      if (active(workGeneration) && firstSample) firstSamplePending = true;
      returnInput(index); // Packet bytes may be reused only after native acceptance.
      lock.notifyAll();
    }
    receiveAvailable(workGeneration, eos);
  }

  private int acquireOutput(long workGeneration) throws InterruptedException {
    synchronized (lock) {
      for (;;) {
        if (!active(workGeneration)) return -1;
        for (int i = 0; i < outputs.length; i++) {
          if (outputOwners[i] == AVAILABLE) {
            outputOwners[i] = WORKER;
            outputGenerations[i] = workGeneration;
            outputs[i].init(C.TIME_UNSET, C.VIDEO_OUTPUT_MODE_SURFACE_YUV, null);
            return i;
          }
        }
        lock.wait();
      }
    }
  }

  private int receiveAvailable(long workGeneration, boolean draining) throws Exception {
    int received = 0;
    for (;;) {
      int index = acquireOutput(workGeneration);
      if (index < 0) return received;
      VideoDecoderOutputBuffer output = outputs[index];
      int result;
      try {
        result = backend.receive(output);
      } catch (Exception | Error error) {
        synchronized (lock) { returnOutput(index); }
        throw error;
      }
      synchronized (lock) {
        if (!active(workGeneration)) {
          returnOutput(index);
          return received;
        }
        if (result == High10DecodeBackend.ACCEPTED) {
          if (output.timeUs == C.TIME_UNSET || output.timeUs == Long.MIN_VALUE) {
            returnOutput(index);
            throw new FfmpegHigh10VideoDecoderException("Decoded frame has no presentation timestamp");
          }
          received++;
          observer.frameDecoded(output);
          if (outputStartTimeUs != C.TIME_UNSET && output.timeUs < outputStartTimeUs) {
            skippedOutputs++;
            returnOutput(index);
          } else {
            if (firstSamplePending) {
              output.addFlag(C.BUFFER_FLAG_FIRST_SAMPLE);
              firstSamplePending = false;
            }
            publishOutput(index);
          }
        } else if (result == High10DecodeBackend.EOF && draining) {
          output.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
          publishOutput(index);
          return received;
        } else {
          returnOutput(index);
          if (result == High10DecodeBackend.AGAIN && !draining) return received;
          throw new FfmpegHigh10VideoDecoderException("Unexpected receive/drain status: " + result);
        }
      }
    }
  }

  private void publishOutput(int index) {
    outputs[index].skippedOutputBufferCount = skippedOutputs;
    skippedOutputs = 0;
    outputOwners[index] = QUEUED;
    outputQueue[(outputHead + outputCount) % outputQueue.length] = index;
    outputCount++;
    lock.notifyAll();
  }
}
