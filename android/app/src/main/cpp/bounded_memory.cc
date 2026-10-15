#include "bounded_memory.h"
#include <algorithm>
#include <cstdint>

namespace collie {
thread_local Budget* current_budget = nullptr;
struct alignas(std::max_align_t) Allocation { Budget* budget; std::size_t charge; };
void* Allocate(Budget* budget, std::size_t size) noexcept {
  if (!budget || size > kHeapLimit - sizeof(Allocation)) return nullptr;
  const auto charge = std::max<std::size_t>(size, 1) + sizeof(Allocation);
  if (charge > kHeapLimit - budget->used) return nullptr;
  auto allocation = static_cast<Allocation*>(std::malloc(charge));
  if (!allocation) return nullptr;
  allocation->budget = budget;
  allocation->charge = charge;
  budget->used += charge;
  budget->peak = std::max(budget->peak, budget->used);
  return allocation + 1;
}
void Free(void* pointer) noexcept {
  if (!pointer) return;
  auto allocation = static_cast<Allocation*>(pointer) - 1;
  allocation->budget->used -= allocation->charge;
  std::free(allocation);
}
void* BrotliAllocate(void* opaque, std::size_t size) noexcept { return Allocate(static_cast<Budget*>(opaque), size); }
void BrotliFree(void*, void* pointer) noexcept { Free(pointer); }
}  // namespace collie
