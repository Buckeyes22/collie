#include "bounded_memory.h"
#include <brotli/decode.h>
#include <fstream>
#include <iterator>
#include <iostream>
#include <cstring>

static bool Capture(const unsigned char* data, std::size_t size, void* opaque) {
  static_cast<std::string*>(opaque)->assign(reinterpret_cast<const char*>(data), size);
  return true;
}
int main(int argc, char** argv) {
  if (argc != 2 || BrotliDecoderVersion() != 0x01002000) return 1;
  std::ifstream file(argv[1], std::ios::binary);
  const std::string input((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
  std::string output;
  std::size_t peak = 0;
  if (!collie::DecodeFont(reinterpret_cast<const unsigned char*>(input.data()), input.size(), Capture, &output, &peak)) return 2;
  if (output.size() != 67420 || output[0] != 0 || output[1] != 1 || peak > collie::kHeapLimit) return 3;
  const auto font_peak = peak;
  {
    collie::Budget budget;
    collie::BudgetScope scope(&budget);
    void* first = collie::Allocate(&budget, 10 * 1024 * 1024);
    if (!first || collie::Allocate(&budget, 7 * 1024 * 1024)) return 4;
    collie::Free(first);
    if (budget.used != 0 || collie::Allocate(&budget, collie::kHeapLimit)) return 5;
  }
  std::string invalid = input;
  invalid[0] = 0;
  if (collie::DecodeFont(reinterpret_cast<const unsigned char*>(invalid.data()), invalid.size(), Capture, &output)) return 6;
  // Malformed original/transformed lengths remain native-budget bounded even when JNI is called
  // without the Kotlin admission helper (e.g. reflection). No invalid input receives an output.
  invalid = input;
  invalid[48] = static_cast<char>(0xff);
  if (collie::DecodeFont(reinterpret_cast<const unsigned char*>(invalid.data()), invalid.size(), Capture, &output)) return 7;
  // A 32-byte SFNT claim hiding a >16 MiB directory stream must also fail at the native boundary.
  unsigned char oversized[54] = {};
  std::memcpy(oversized, "wOF2", 4);
  oversized[5] = 1; oversized[11] = sizeof(oversized); oversized[13] = 1;
  oversized[19] = 32; oversized[23] = 1;
  oversized[49] = 0x88; oversized[50] = 0x80; oversized[51] = 0x80; oversized[52] = 1;
  if (collie::DecodeFont(oversized, sizeof(oversized), Capture, &output)) return 8;
  for (std::size_t index = 0; index < input.size(); index += 113) {
    invalid = input;
    invalid[index] ^= 0x20;
    output.clear(); peak = 0;
    const bool decoded = collie::DecodeFont(reinterpret_cast<const unsigned char*>(invalid.data()), invalid.size(), Capture, &output, &peak);
    if (peak > collie::kHeapLimit || decoded && output.size() > collie::kHeapLimit) return 9;
  }
  std::cout << "Brotli 1.2.0: actual font 67420 bytes; shared decoder heap peak " << font_peak
            << "; cumulative allocation refusal and malformed-input refusal passed\n";
  return 0;
}
