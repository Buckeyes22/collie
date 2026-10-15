#include <jni.h>
#include "bounded_memory.h"

namespace {
struct JavaOutput { JNIEnv* env; jbyteArray bytes = nullptr; };
bool WriteJavaOutput(const unsigned char* data, std::size_t size, void* opaque) {
  auto& output = *static_cast<JavaOutput*>(opaque);
  output.bytes = output.env->NewByteArray(static_cast<jsize>(size));
  if (!output.bytes) return false;
  output.env->SetByteArrayRegion(output.bytes, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(data));
  return !output.env->ExceptionCheck();
}
void Refuse(JNIEnv* env) {
  if (env->ExceptionCheck()) return;
  jclass type = env->FindClass("java/lang/IllegalArgumentException");
  if (type) env->ThrowNew(type, "Invalid or oversized operator WOFF2 font");
}
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_lateapex_collie_ui_BoundedWoff2Decoder_decodeNative(JNIEnv* env, jobject, jbyteArray input) {
  if (!input) { Refuse(env); return nullptr; }
  const auto size = env->GetArrayLength(input);
  if (size < 48 || static_cast<std::size_t>(size) > collie::kInputLimit) { Refuse(env); return nullptr; }
  // JNI may pin or copy at most the already-bounded 2 MiB managed input. The private decoder heap
  // has its own shared 16 MiB budget; the final managed output is separately bounded to 16 MiB.
  jbyte* bytes = env->GetByteArrayElements(input, nullptr);
  if (!bytes) return nullptr;
  JavaOutput output{env};
  const bool success = collie::DecodeFont(reinterpret_cast<unsigned char*>(bytes), static_cast<std::size_t>(size), WriteJavaOutput, &output);
  env->ReleaseByteArrayElements(input, bytes, JNI_ABORT);
  if (!success) { Refuse(env); return nullptr; }
  return output.bytes;
}
