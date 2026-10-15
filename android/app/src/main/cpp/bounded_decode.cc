#include "bounded_memory.h"
#include <woff2/decode.h>
#include <woff2/output.h>

namespace collie {
static std::uint32_t Read32(const unsigned char* data) {
  return std::uint32_t(data[0]) << 24 | std::uint32_t(data[1]) << 16 |
         std::uint32_t(data[2]) << 8 | std::uint32_t(data[3]);
}
bool DecodeFont(const unsigned char* data, std::size_t size, OutputSink sink, void* opaque,
                std::size_t* peak) noexcept {
  if (!data || !sink || size < 48 || size > kInputLimit || Read32(data) != 0x774f4632 ||
      Read32(data + 8) != size || Read32(data + 4) == 0x74746366) return false;
  const auto count = std::uint16_t(data[12]) << 8 | data[13];
  if (count == 0 || count > 256) return false;
  Budget budget;
  BudgetScope scope(&budget);
  try {
    // Own the input under the same budget as all decoder vectors, maps, arrays, output and Brotli.
    Vector<unsigned char> input(data, data + size);
    String output;
    woff2::WOFF2StringOut out(&output);
    out.SetMaxSize(kHeapLimit);
    if (!woff2::ConvertWOFF2ToTTF(input.data(), input.size(), &out) || out.Size() < 12 ||
        out.Size() > kHeapLimit || out.Size() != output.size()) return false;
    if (peak) *peak = budget.peak;
    return sink(reinterpret_cast<const unsigned char*>(output.data()), out.Size(), opaque);
  } catch (...) {
    if (peak) *peak = budget.peak;
    return false;
  }
}
}  // namespace collie
