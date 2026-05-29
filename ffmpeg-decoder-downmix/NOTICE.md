This module is based on the AndroidX Media3 `decoder_ffmpeg` extension,
originally licensed under Apache-2.0.

Additional downmix behavior in this module was adapted from Kodi (`xbmc/xbmc`),
including the FFmpeg/AudioEngine handling of:

- center mix defaults and metadata offsets
- explicit output channel layout selection for downmix
- downmix normalization behavior

Provenance summary:

- Base implementation: AndroidX Media3 FFmpeg decoder extension
- Downmix behavior source: Kodi (`xbmc/xbmc`), licensed `GPL-2.0-or-later`
- Reference file:
  `xbmc/cores/AudioEngine/Engines/ActiveAE/ActiveAEResampleFFMPEG.cpp`
- Reference snapshot:
  https://github.com/xbmc/xbmc/blob/9746df6d7de2503047417099e2dcd8ec5276db40/xbmc/cores/AudioEngine/Engines/ActiveAE/ActiveAEResampleFFMPEG.cpp

This project is distributed under GPL-3.0. The modified decoder module is
distributed with this project under GPL-3.0-only, while preserving the
original AndroidX Media3 Apache-2.0 notices for the upstream base code.
Kodi code available under `GPL-2.0-or-later` is compatible with GPL-3.0.
