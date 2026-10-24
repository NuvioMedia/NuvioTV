This C compatible library provides functions for implementing [yEnc](http://www.yenc.org/yenc-draft.1.3.txt) where speed is important.

Note that it only handles the underlying encoding/decoding routines - yEnc headers aren’t handled.

Features
---------

-   implementation uses x86/ARM/RISC-V SIMD capabilities, with support for ARMv7 NEON, ARMv8 ASIMD or the following x86 SIMD extensions: SSE2, SSSE3, AVX, AVX2, AVX512-BW (128/256-bit), AVX512-VBMI2 (or AVX10.1)
-   CPU detection and dynamic dispatch (i.e. select best implementation for currently running CPU)
-   incremental processing, including detection of yEnc/NNTP end sequences in decoder
-   raw yEnc encoding with the ability to specify line length. A single thread can achieve \>450MB/s on a Raspberry Pi 3, or \>5GB/s on a Core-i series CPU.
-   yEnc decoding, with and without NNTP layer dot unstuffing. A single thread can achieve \>300MB/s on a Raspberry Pi 3, or \>4.5GB/s on a Core-i series CPU.
-   CRC32 implementation via [crcutil](https://code.google.com/p/crcutil/) or [PCLMULQDQ instruction](http://www.intel.com/content/dam/www/public/us/en/documents/white-papers/fast-crc-computation-generic-polynomials-pclmulqdq-paper.pdf), ARMv8’s CRC instructions, or RISC-V’s Zb(k)c extension (\>1GB/s on a low power Atom/ARM CPU, \>15GB/s on a modern Intel CPU)
-   ability to combine two CRC32 hashes into one (useful for amalgamating *pcrc32s* into a *crc32* for yEnc), as well as quickly compute the CRC32 of a sequence of null bytes

Building
==========

A build file/project can be created using CMake.

```
mkdir build
cd build
cmake ..
cmake --build . --config Release
```

After compilation, a shared and static library should be generated, as well as a benchmark and sample CLI application.

## Build Options

The following options can be passed into CMake:

* **BUILD_NATIVE**: Optimise for and target only the build host’s CPU; this build may not be re-distributable
* **DISABLE_AVX256**: Disable the use of 256-bit AVX instructions on x86 processors
* **DISABLE_CRCUTIL**: Disable crcutil usage. crcutil is only ever enabled for x86 builds
* **DISABLE_ENCODE**: Remove yEnc encode functionality from build. `rapidyenc_encode`* functions, except `rapidyenc_encode_max_length`, will be unavailable
* **DISABLE_DECODE**: Remove yEnc decode functionality from build. `rapidyenc_decode`* functions will be unavailable
* **DISABLE_CRC**: Remove CRC32 functionality from build. `rapidyenc_crc`* functions will be unavailable. Implies *DISABLE_CRCUTIL*
* **DISABLE_TOOL**: Don't build the bench/cli tool applications
* **DISABLE_SHARED**: Don't build the shared library

API
===

Before any encoding/decoding/CRC functions can be used, the respective `_init` function must be called. These functions set up the necessary state for computation. Note that `_init` functions aren’t thread-safe, but all others are.

Functions documented in the [header file](rapidyenc.h).

[cli.c](tool/cli.c) is a simple command-line application which encodes/decodes stdin to stdout. It demonstrates how to do incremental encoding/decoding/CRC32 using this library.

# Other Language Bindings

* [node-yencode](https://github.com/animetosho/node-yencode): for NodeJS/Bun
* [go-yencode](https://github.com/mnightingale/go-yencode): for Golang
* [sabctools](https://github.com/sabnzbd/sabctools): for Python (Sabnzbd specific binding)
* [RapidYencSharp](https://github.com/nzbdav-dev/RapidYencSharp): for C#/.NET
* https://github.com/cryeprecision/rapidyenc-rs
* https://github.com/skjaere/rapidyenc-kotlin-wrapper

Algorithm
=========

A brief description of how the SIMD yEnc encoding algorithm works [can be found here](https://github.com/animetosho/node-yencode/issues/4#issuecomment-330025192).
I may eventually write up something more detailed, regarding optimizations and such used.

License
=======

This module is Public Domain or [CC0](https://creativecommons.org/publicdomain/zero/1.0/legalcode) (or equivalent) if PD isn’t recognised.

[crcutil](https://code.google.com/p/crcutil/), used for CRC32 calculation, is licensed under the [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0)

[zlib-ng](https://github.com/Dead2/zlib-ng), from where the CRC32 calculation using folding approach was stolen, is under a [zlib license](https://github.com/Dead2/zlib-ng/blob/develop/LICENSE.md)
