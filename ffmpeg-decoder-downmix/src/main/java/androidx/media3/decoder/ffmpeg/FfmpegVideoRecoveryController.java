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
package androidx.media3.decoder.ffmpeg;

import androidx.media3.common.C;

/** Controls decoder load shedding and bounded keyframe resynchronization. */
final class FfmpegVideoRecoveryController {

  private static final long LOAD_SHEDDING_ENABLE_THRESHOLD_US = -200_000;
  private static final long LOAD_SHEDDING_ENABLE_DURATION_US = 500_000;
  // Recover before reaching exactly zero. Render-loop scheduling normally leaves a small negative
  // earlyUs even after the decoder has caught up, so requiring zero can leave non-reference frame
  // shedding enabled indefinitely.
  private static final long LOAD_SHEDDING_DISABLE_THRESHOLD_US = -10_000;
  private static final long AGGRESSIVE_LOAD_SHEDDING_ENABLE_THRESHOLD_US = -400_000;
  private static final long AGGRESSIVE_LOAD_SHEDDING_DISABLE_THRESHOLD_US = -100_000;

  @FfmpegVideoDecoder.DecodeLoadLevel private int decodeLoadLevel;
  private long loadSheddingCandidateStartRealtimeUs;
  private boolean keyframeResyncRequestedInLateEpisode;

  FfmpegVideoRecoveryController() {
    reset();
  }

  @FfmpegVideoDecoder.DecodeLoadLevel
  int updateDecodeLoadLevel(long earlyUs, long elapsedRealtimeUs, boolean startupGrace) {
    if (startupGrace) {
      resetLoadShedding();
      return decodeLoadLevel;
    }
    if (earlyUs >= LOAD_SHEDDING_DISABLE_THRESHOLD_US) {
      resetLoadShedding();
      keyframeResyncRequestedInLateEpisode = false;
      return decodeLoadLevel;
    }
    if (earlyUs < AGGRESSIVE_LOAD_SHEDDING_ENABLE_THRESHOLD_US) {
      loadSheddingCandidateStartRealtimeUs = C.TIME_UNSET;
      decodeLoadLevel = FfmpegVideoDecoder.DECODE_LOAD_AGGRESSIVE;
      return decodeLoadLevel;
    }
    if (decodeLoadLevel == FfmpegVideoDecoder.DECODE_LOAD_NORMAL) {
      if (earlyUs >= LOAD_SHEDDING_ENABLE_THRESHOLD_US) {
        loadSheddingCandidateStartRealtimeUs = C.TIME_UNSET;
      } else if (loadSheddingCandidateStartRealtimeUs == C.TIME_UNSET) {
        loadSheddingCandidateStartRealtimeUs = elapsedRealtimeUs;
      } else if (elapsedRealtimeUs - loadSheddingCandidateStartRealtimeUs
          >= LOAD_SHEDDING_ENABLE_DURATION_US) {
        loadSheddingCandidateStartRealtimeUs = C.TIME_UNSET;
        decodeLoadLevel = FfmpegVideoDecoder.DECODE_LOAD_NON_REFERENCE;
      }
    } else if (earlyUs >= AGGRESSIVE_LOAD_SHEDDING_DISABLE_THRESHOLD_US) {
      decodeLoadLevel = FfmpegVideoDecoder.DECODE_LOAD_NON_REFERENCE;
    }
    return decodeLoadLevel;
  }

  boolean shouldRequestKeyframeResync(boolean veryLate) {
    if (!veryLate || keyframeResyncRequestedInLateEpisode) {
      return false;
    }
    keyframeResyncRequestedInLateEpisode = true;
    return true;
  }

  void onDecoderFlushed() {
    // A keyframe resync flush does not end the current late episode.
    resetLoadShedding();
  }

  void reset() {
    resetLoadShedding();
    keyframeResyncRequestedInLateEpisode = false;
  }

  private void resetLoadShedding() {
    decodeLoadLevel = FfmpegVideoDecoder.DECODE_LOAD_NORMAL;
    loadSheddingCandidateStartRealtimeUs = C.TIME_UNSET;
  }
}
