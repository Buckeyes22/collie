#pragma once

#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <limits>
#include <map>
#include <memory>
#include <new>
#include <string>
#include <type_traits>
#include <vector>

namespace collie {
constexpr std::size_t kHeapLimit = 16 * 1024 * 1024;
constexpr std::size_t kInputLimit = 2 * 1024 * 1024;
struct Budget { std::size_t used = 0; std::size_t peak = 0; };
extern thread_local Budget* current_budget;
struct BudgetScope {
  Budget* previous;
  explicit BudgetScope(Budget* budget) : previous(current_budget) { current_budget = budget; }
  ~BudgetScope() { current_budget = previous; }
};
void* Allocate(Budget* budget, std::size_t size) noexcept;
void Free(void* pointer) noexcept;
void* BrotliAllocate(void* opaque, std::size_t size) noexcept;
void BrotliFree(void*, void* pointer) noexcept;

template<class T> struct Allocator {
  using value_type = T;
  Budget* budget;
  Allocator() noexcept : budget(current_budget) {}
  template<class U> Allocator(const Allocator<U>& other) noexcept : budget(other.budget) {}
  T* allocate(std::size_t count) {
    static_assert(alignof(T) <= alignof(std::max_align_t), "Unsupported decoder alignment");
    if (count > kHeapLimit / sizeof(T)) throw std::bad_alloc();
    auto pointer = Allocate(budget, count * sizeof(T));
    if (!pointer) throw std::bad_alloc();
    return static_cast<T*>(pointer);
  }
  void deallocate(T* pointer, std::size_t) noexcept { Free(pointer); }
  template<class U> bool operator==(const Allocator<U>& other) const noexcept { return budget == other.budget; }
  template<class U> bool operator!=(const Allocator<U>& other) const noexcept { return !(*this == other); }
};
template<class T> using Vector = std::vector<T, Allocator<T>>;
template<class K, class V> using Map = std::map<K, V, std::less<K>, Allocator<std::pair<const K, V>>>;
using String = std::basic_string<char, std::char_traits<char>, Allocator<char>>;
template<class T> struct ArrayDeleter { void operator()(T* pointer) const noexcept { Free(pointer); } };
template<class T> using Array = std::unique_ptr<T[], ArrayDeleter<T>>;
template<class T> T* NewArray(std::size_t count) {
  static_assert(std::is_trivial<T>::value, "Decoder arrays must be trivial");
  auto pointer = Allocator<T>().allocate(count);
  std::uninitialized_default_construct_n(pointer, count);
  return pointer;
}
using OutputSink = bool (*)(const unsigned char*, std::size_t, void*);
bool DecodeFont(const unsigned char* data, std::size_t size, OutputSink sink, void* opaque,
                std::size_t* peak = nullptr) noexcept;
}  // namespace collie
