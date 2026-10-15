cmake_minimum_required(VERSION 3.22.1)
project(collie_woff2 LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(collie_woff2_core STATIC
    bounded_memory.cc bounded_decode.cc
    third_party/woff2/src/woff2_dec.cc
    third_party/woff2/src/woff2_out.cc
    third_party/woff2/src/woff2_common.cc
    third_party/woff2/src/table_tags.cc
    third_party/woff2/src/variable_length.cc
    third_party/brotli/c/common/constants.c
    third_party/brotli/c/common/context.c
    third_party/brotli/c/common/dictionary.c
    third_party/brotli/c/common/platform.c
    third_party/brotli/c/common/shared_dictionary.c
    third_party/brotli/c/common/transform.c
    third_party/brotli/c/dec/bit_reader.c
    third_party/brotli/c/dec/decode.c
    third_party/brotli/c/dec/huffman.c
    third_party/brotli/c/dec/prefix.c
    third_party/brotli/c/dec/state.c
    third_party/brotli/c/dec/static_init.c)
set_target_properties(collie_woff2_core PROPERTIES POSITION_INDEPENDENT_CODE ON C_VISIBILITY_PRESET hidden CXX_VISIBILITY_PRESET hidden)
target_include_directories(collie_woff2_core PUBLIC . third_party/woff2/include third_party/brotli/c/include)
target_compile_definitions(collie_woff2_core PRIVATE BROTLI_BUILD_PORTABLE=1 COLLIE_WOFF2_PORTABLE=1)
target_compile_options(collie_woff2_core PRIVATE $<$<COMPILE_LANGUAGE:CXX>:-fexceptions>)

if(ANDROID)
  add_library(collie_woff2 SHARED woff2_jni.cc)
  target_link_libraries(collie_woff2 PRIVATE collie_woff2_core)
  target_link_options(collie_woff2 PRIVATE "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map" "-Wl,--exclude-libs,ALL" "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
  set_target_properties(collie_woff2 PROPERTIES CXX_VISIBILITY_PRESET hidden)
else()
  # Ordinary host regression executable; Android builds compile only the JNI decoder.
  add_executable(collie_woff2_smoke decoder_smoke.cc)
  target_link_libraries(collie_woff2_smoke PRIVATE collie_woff2_core)
endif()
