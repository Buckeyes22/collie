# Bounded operator font decoder

This is the decoder-only source subset of Google WOFF2 v1.0.2
(`1bccf208bca986e53a647dfe4811322adb06ecf8`) and Google Brotli v1.2.0
(`028fb5a23661f123017c060daa546b55cf4bde29`). Both use the MIT license; the
original licenses are under `third_party/woff2/LICENSE` and
`third_party/brotli/LICENSE`. Each `UPSTREAM.json` records the immutable source
commit, archive hash, original retained-file hashes and local patch hashes.
No encoder, command-line program, network downloader or third-party Java wrapper
is part of the Android library. Source provenance is the corresponding official
repositories, https://github.com/google/woff2 and https://github.com/google/brotli.

The old prebuilt wrapper embedded Brotli 1.0.1. Google fixed the >2 GiB input
overflow in v1.0.8 (https://github.com/google/brotli/releases/tag/v1.0.8);
Collie's 2 MiB input limit already excludes that trigger. Brotli v1.2.0 carries
the later decompression resource-limit changes
(https://github.com/google/brotli/releases/tag/v1.2.0). This replacement avoids
shipping the older embedded component; no live exploit is claimed.

Four WOFF2 files have local changes: decoder vectors,
maps and POD arrays use the private allocator; `WOFF2StringOut` uses its string
allocator while retaining `SetMaxSize`; Brotli uses its streaming API and the
same allocator callbacks. The portability header permits selecting the existing
byte-access branches instead of undefined unaligned typed loads/stores. Brotli
source is unmodified. The per-call 16 MiB
budget includes live decoder allocations and allocation headers, including its
input copy, reconstructed output capacity and Brotli state/ring buffer. All
owned containers release before the call-local budget disappears. It is thread
local, with no replacement of process-wide allocation functions. JNI's bounded
managed input pin/copy and final managed byte array are outside this private
budget, bounded separately to 2 MiB and 16 MiB. Output is never preallocated from
the untrusted WOFF2 header estimate. Allocation failure refuses the font.

`BoundedWoff2Decoder.decode` first runs `OperatorWoff2Bounds.validate`. JNI also
enforces input/header/table-count/collection limits and the native heap/output
budget even if that private native method is called directly. The decoder has
no filesystem, logging or network access. The caller builds the platform font
from the returned SFNT and remains responsible for its actor-bound custody.

The installed NDK 27.1.12297006 provides native headers/stubs through API 35 and
rejects `ANDROID_PLATFORM=android-37`. Configure this library with native-only
`ANDROID_PLATFORM=latest`, `ANDROID_STL=c++_static`, and
`ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`; the installed CMake is 3.22.1. The app
keeps compile SDK 37.2, min/target 37 and its existing JDK/AGP/Gradle/Kotlin pins.
This library calls no API newer than the available native stubs. Link options
set 16 KiB page alignment and export only the named JNI entry point.

For ordinary host regression, CMake builds `collie_woff2_smoke`; pass the real
`android/app/src/androidTest/assets/fonts/operator-space.woff2` path. It checks
the actual Brotli version, successful SFNT conversion, cumulative allocation
refusal, oversized/malformed-input refusal and bounded mutations. The Android
build compiles only `libcollie_woff2.so`, not the host executable. Parent-owned
AGP builds, Pixel checks and final signed-artifact scanning are still required.
