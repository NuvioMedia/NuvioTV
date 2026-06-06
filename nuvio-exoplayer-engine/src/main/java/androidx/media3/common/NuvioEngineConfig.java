/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.common;

import androidx.media3.common.util.UnstableApi;

/**
 * Global configuration that controls the Nuvio Engine enhancements over stock ExoPlayer.
 *
 * <p>All feature flags must be set <b>before</b> building a player instance. Changing flags during
 * active playback is not supported and may cause undefined behaviour.
 *
 * <h3>Usage</h3>
 *
 * <pre>{@code
 * // Enable all Nuvio enhancements
 * NuvioEngineConfig.set(NuvioEngineConfig.nuvioMode());
 *
 * // Use stock ExoPlayer behaviour
 * NuvioEngineConfig.set(NuvioEngineConfig.stockMode());
 *
 * // Custom configuration
 * NuvioEngineConfig.set(
 *     new NuvioEngineConfig.Builder()
 *         .setAllocationMode(AllocationMode.NATIVE_OFF_HEAP)
 *         .setDataPipelineMode(DataPipelineMode.STOCK)
 *         .setExtractorScratchSize(65536)
 *         .build());
 * }</pre>
 */
@UnstableApi
public final class NuvioEngineConfig {

  /** Controls how sample data memory is allocated. */
  public enum AllocationMode {
    /** Stock ExoPlayer behaviour — on-heap {@code byte[]} allocations. */
    STOCK_HEAP,
    /** Nuvio enhancement — off-heap native memory via {@code posix_memalign} / DirectByteBuffer. */
    NATIVE_OFF_HEAP
  }

  /** Controls the data pipeline between DataSource and SampleQueue. */
  public enum DataPipelineMode {
    /** Stock ExoPlayer behaviour — {@code byte[]} based DataReader. */
    STOCK,
    /** Nuvio enhancement — zero-copy ByteBuffer pipeline through DataSource → Extractor → SampleQueue. */
    ZERO_COPY
  }

  /** Stock ExoPlayer scratch space size (bytes). */
  public static final int STOCK_EXTRACTOR_SCRATCH_SIZE = 4096;

  /** Nuvio enhanced extractor scratch space size (bytes). */
  public static final int NUVIO_EXTRACTOR_SCRATCH_SIZE = 65536;

  private static volatile NuvioEngineConfig instance = stockMode();

  private final AllocationMode allocationMode;
  private final DataPipelineMode dataPipelineMode;
  private final int extractorScratchSize;

  private NuvioEngineConfig(Builder builder) {
    this.allocationMode = builder.allocationMode;
    this.dataPipelineMode = builder.dataPipelineMode;
    this.extractorScratchSize = builder.extractorScratchSize;
  }

  /** Returns the current global configuration. */
  public static NuvioEngineConfig get() {
    return instance;
  }

  /**
   * Sets the global configuration. Must be called before building any player instance.
   *
   * @param config The configuration to apply globally.
   */
  public static void set(NuvioEngineConfig config) {
    instance = config;
  }

  /**
   * Returns a configuration with all Nuvio enhancements enabled.
   *
   * <ul>
   *   <li>Native off-heap allocation
   *   <li>Zero-copy data pipeline
   *   <li>64 KB extractor scratch buffer
   * </ul>
   */
  public static NuvioEngineConfig nuvioMode() {
    return new Builder()
        .setAllocationMode(AllocationMode.NATIVE_OFF_HEAP)
        .setDataPipelineMode(DataPipelineMode.ZERO_COPY)
        .setExtractorScratchSize(NUVIO_EXTRACTOR_SCRATCH_SIZE)
        .build();
  }

  /**
   * Returns a configuration that matches stock ExoPlayer behaviour.
   *
   * <ul>
   *   <li>On-heap byte[] allocation
   *   <li>Standard byte[] data pipeline
   *   <li>4 KB extractor scratch buffer
   * </ul>
   */
  public static NuvioEngineConfig stockMode() {
    return new Builder()
        .setAllocationMode(AllocationMode.STOCK_HEAP)
        .setDataPipelineMode(DataPipelineMode.STOCK)
        .setExtractorScratchSize(STOCK_EXTRACTOR_SCRATCH_SIZE)
        .build();
  }

  /** Returns the allocation mode. */
  public AllocationMode getAllocationMode() {
    return allocationMode;
  }

  /** Returns the data pipeline mode. */
  public DataPipelineMode getDataPipelineMode() {
    return dataPipelineMode;
  }

  /** Returns the extractor scratch buffer size in bytes. */
  public int getExtractorScratchSize() {
    return extractorScratchSize;
  }

  /** Returns {@code true} if native off-heap allocation is enabled. */
  public boolean isNativeAllocationEnabled() {
    return allocationMode == AllocationMode.NATIVE_OFF_HEAP;
  }

  /** Returns {@code true} if zero-copy data pipeline is enabled. */
  public boolean isZeroCopyEnabled() {
    return dataPipelineMode == DataPipelineMode.ZERO_COPY;
  }

  /** Builder for {@link NuvioEngineConfig}. */
  public static final class Builder {
    private AllocationMode allocationMode = AllocationMode.STOCK_HEAP;
    private DataPipelineMode dataPipelineMode = DataPipelineMode.STOCK;
    private int extractorScratchSize = STOCK_EXTRACTOR_SCRATCH_SIZE;

    public Builder() {}

    /** Sets the allocation mode. Default is {@link AllocationMode#STOCK_HEAP}. */
    public Builder setAllocationMode(AllocationMode allocationMode) {
      this.allocationMode = allocationMode;
      return this;
    }

    /** Sets the data pipeline mode. Default is {@link DataPipelineMode#STOCK}. */
    public Builder setDataPipelineMode(DataPipelineMode dataPipelineMode) {
      this.dataPipelineMode = dataPipelineMode;
      return this;
    }

    /** Sets the extractor input scratch buffer size in bytes. Default is 4096. */
    public Builder setExtractorScratchSize(int extractorScratchSize) {
      this.extractorScratchSize = extractorScratchSize;
      return this;
    }

    /** Builds the {@link NuvioEngineConfig}. */
    public NuvioEngineConfig build() {
      return new NuvioEngineConfig(this);
    }
  }
}
