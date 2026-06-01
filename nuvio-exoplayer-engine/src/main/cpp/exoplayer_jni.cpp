#include <jni.h>
#include <sys/mman.h>
#include <unistd.h>

#include <cstdint>
#include <cstdlib>
#include <cstring>

namespace {

bool isValidRange(jlong capacity, jint offset, jint length) {
  return capacity >= 0 && offset >= 0 && length >= 0 &&
         static_cast<jlong>(offset) + static_cast<jlong>(length) <= capacity;
}

void *allocateZeroedMemory(jint size) {
  void *memory = nullptr;
  if (size <= 0) {
    return nullptr;
  }
  long pageSize = sysconf(_SC_PAGESIZE);
  size_t alignment = pageSize > 0 ? static_cast<size_t>(pageSize) : 4096;
  if (posix_memalign(&memory, alignment, static_cast<size_t>(size)) != 0) {
    return nullptr;
  }
  std::memset(memory, 0, static_cast<size_t>(size));
  madvise(memory, static_cast<size_t>(size), MADV_SEQUENTIAL);
  return memory;
}

}

extern "C" {

JNIEXPORT jobject JNICALL
Java_androidx_media3_exoplayer_upstream_DefaultAllocatorNative_nativeCreateAllocation(
    JNIEnv *env, jclass clazz, jint size) {
  void *memory = allocateZeroedMemory(size);
  if (memory == nullptr) {
    return nullptr;
  }

  jobject buffer = env->NewDirectByteBuffer(memory, size);
  if (buffer == nullptr) {
    free(memory);
    return nullptr;
  }

  jclass allocationClass =
      env->FindClass("androidx/media3/exoplayer/upstream/Allocation");
  if (allocationClass == nullptr) {
    free(memory);
    return nullptr;
  }

  jmethodID constructor =
      env->GetMethodID(allocationClass, "<init>", "(Ljava/nio/ByteBuffer;IJ)V");
  if (constructor == nullptr) {
    free(memory);
    return nullptr;
  }

  jobject allocation =
      env->NewObject(allocationClass, constructor, buffer, 0, (jlong)memory);
  if (allocation == nullptr) {
    free(memory);
  }
  return allocation;
}

JNIEXPORT void JNICALL
Java_androidx_media3_exoplayer_upstream_DefaultAllocatorNative_nativeFreeAllocation(
    JNIEnv *env, jclass clazz, jlong handle) {
  if (handle != 0) {
    free(reinterpret_cast<void *>(handle));
  }
}

JNIEXPORT jboolean JNICALL
Java_androidx_media3_exoplayer_source_SampleDataQueueNative_nativeCopyFromArray(
    JNIEnv *env, jclass clazz, jbyteArray source, jint sourceOffset,
    jobject target, jint targetOffset, jint length) {
  if (length == 0) {
    return JNI_TRUE;
  }
  if (source == nullptr || target == nullptr) {
    return JNI_FALSE;
  }

  jsize sourceLength = env->GetArrayLength(source);
  jlong targetCapacity = env->GetDirectBufferCapacity(target);
  auto *targetAddress =
      static_cast<uint8_t *>(env->GetDirectBufferAddress(target));
  if (targetAddress == nullptr ||
      !isValidRange(sourceLength, sourceOffset, length) ||
      !isValidRange(targetCapacity, targetOffset, length)) {
    return JNI_FALSE;
  }

  env->GetByteArrayRegion(
      source, sourceOffset, length,
      reinterpret_cast<jbyte *>(targetAddress + targetOffset));
  return env->ExceptionCheck() ? JNI_FALSE : JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_androidx_media3_exoplayer_source_SampleDataQueueNative_nativeCopyToArray(
    JNIEnv *env, jclass clazz, jobject source, jint sourceOffset,
    jbyteArray target, jint targetOffset, jint length) {
  if (length == 0) {
    return JNI_TRUE;
  }
  if (source == nullptr || target == nullptr) {
    return JNI_FALSE;
  }

  jlong sourceCapacity = env->GetDirectBufferCapacity(source);
  auto *sourceAddress =
      static_cast<uint8_t *>(env->GetDirectBufferAddress(source));
  jsize targetLength = env->GetArrayLength(target);
  if (sourceAddress == nullptr ||
      !isValidRange(sourceCapacity, sourceOffset, length) ||
      !isValidRange(targetLength, targetOffset, length)) {
    return JNI_FALSE;
  }

  env->SetByteArrayRegion(
      target, targetOffset, length,
      reinterpret_cast<jbyte *>(sourceAddress + sourceOffset));
  return env->ExceptionCheck() ? JNI_FALSE : JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_androidx_media3_exoplayer_source_SampleDataQueueNative_nativeCopyBetweenDirectBuffers(
    JNIEnv *env, jclass clazz, jobject source, jint sourceOffset,
    jobject target, jint targetOffset, jint length) {
  if (length == 0) {
    return JNI_TRUE;
  }
  if (source == nullptr || target == nullptr) {
    return JNI_FALSE;
  }

  jlong sourceCapacity = env->GetDirectBufferCapacity(source);
  auto *sourceAddress =
      static_cast<uint8_t *>(env->GetDirectBufferAddress(source));
  jlong targetCapacity = env->GetDirectBufferCapacity(target);
  auto *targetAddress =
      static_cast<uint8_t *>(env->GetDirectBufferAddress(target));
  if (sourceAddress == nullptr || targetAddress == nullptr ||
      !isValidRange(sourceCapacity, sourceOffset, length) ||
      !isValidRange(targetCapacity, targetOffset, length)) {
    return JNI_FALSE;
  }

  std::memmove(targetAddress + targetOffset, sourceAddress + sourceOffset,
               static_cast<size_t>(length));
  return JNI_TRUE;
}

}
