/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.nuvio.tv.core.player.dvmkv;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import java.util.Locale;
import java.util.Objects;

/**
 * Runtime compatibility helpers for Dolby Vision to HEVC fallback flows.
 *
 * <p>This class intentionally stores transformer hooks as raw {@link Object} references so that
 * shared modules can exchange hook instances without introducing module dependency cycles.
 */
@UnstableApi
public final class DolbyVisionCompatibility {

  private static volatile boolean mapDv7ToHevcEnabled;
  @Nullable private static volatile Object matroskaDolbyVisionSampleTransformer;
  @Nullable private static volatile Object mp4DolbyVisionSampleTransformer;
  @Nullable private static volatile Object fragmentedMp4DolbyVisionSampleTransformer;
  @Nullable private static volatile Object tsDolbyVisionNalTransformer;

  public static void setMapDv7ToHevcEnabled(boolean enabled) {
    mapDv7ToHevcEnabled = enabled;
  }

  public static boolean isMapDv7ToHevcEnabled() {
    return mapDv7ToHevcEnabled;
  }

  public static boolean shouldMapDolbyVisionProfile7(
      @Nullable String sampleMimeType, @Nullable String codecs) {
    if (!mapDv7ToHevcEnabled) {
      return false;
    }
    if (sampleMimeType != null && !Objects.equals(sampleMimeType, MimeTypes.VIDEO_DOLBY_VISION)) {
      return false;
    }
    if (codecs == null || codecs.trim().isEmpty()) {
      // If we already know this is a Dolby Vision track by mime type, allow fallback mapping even
      // without an explicit codec string.
      return Objects.equals(sampleMimeType, MimeTypes.VIDEO_DOLBY_VISION);
    }
    return extractDolbyVisionCodec(codecs) != null;
  }

  public static boolean isDolbyVisionProfile7(@Nullable String codecs) {
    @Nullable String dvCodecs = extractDolbyVisionCodec(codecs);
    if (dvCodecs == null) {
      return false;
    }
    String[] parts = dvCodecs.split("\\.");
    if (parts.length < 2) {
      return false;
    }
    int profile = parseIntOrUnset(parts[1]);
    return profile == 7;
  }

  @Nullable
  public static String chooseHevcCodecsString(
      @Nullable String codecs, @Nullable String supplementalCodecs) {
    if (isHevcCodecsString(codecs)) {
      return codecs;
    }
    if (isHevcCodecsString(supplementalCodecs)) {
      return supplementalCodecs;
    }
    return null;
  }

  public static void setMatroskaDolbyVisionSampleTransformer(@Nullable Object transformer) {
    matroskaDolbyVisionSampleTransformer = transformer;
  }

  @Nullable
  public static Object getMatroskaDolbyVisionSampleTransformer() {
    return matroskaDolbyVisionSampleTransformer;
  }

  public static void setMp4DolbyVisionSampleTransformer(@Nullable Object transformer) {
    mp4DolbyVisionSampleTransformer = transformer;
  }

  @Nullable
  public static Object getMp4DolbyVisionSampleTransformer() {
    return mp4DolbyVisionSampleTransformer;
  }

  public static void setFragmentedMp4DolbyVisionSampleTransformer(@Nullable Object transformer) {
    fragmentedMp4DolbyVisionSampleTransformer = transformer;
  }

  @Nullable
  public static Object getFragmentedMp4DolbyVisionSampleTransformer() {
    return fragmentedMp4DolbyVisionSampleTransformer;
  }

  public static void setTsDolbyVisionNalTransformer(@Nullable Object transformer) {
    tsDolbyVisionNalTransformer = transformer;
  }

  @Nullable
  public static Object getTsDolbyVisionNalTransformer() {
    return tsDolbyVisionNalTransformer;
  }

  private static boolean isHevcCodecsString(@Nullable String codecs) {
    if (codecs == null) {
      return false;
    }
    String trimmed = codecs.trim();
    return trimmed.startsWith("hvc1") || trimmed.startsWith("hev1");
  }

  @Nullable
  private static String extractDolbyVisionCodec(@Nullable String codecs) {
    if (codecs == null) {
      return null;
    }
    String[] codecParts = Util.splitCodecs(codecs);
    for (int i = 0; i < codecParts.length; i++) {
      String codec = codecParts[i].trim().toLowerCase(Locale.US);
      if (codec.startsWith("dvhe") || codec.startsWith("dvh1")) {
        return codec;
      }
    }
    return null;
  }

  private static int parseIntOrUnset(String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return Format.NO_VALUE;
    }
  }

  private DolbyVisionCompatibility() {}
}
