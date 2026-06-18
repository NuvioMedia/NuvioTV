#include <jni.h>
#include <sys/mman.h>
#include <unistd.h>

#include <cstdint>
#include <cstdlib>
#include <cstring>

namespace {

jclass gAllocationClass = nullptr;
jmethodID gAllocationConstructor = nullptr;
size_t gPageAlignment = 4096;

void *allocateZeroedMemory(jint size) {
  void *memory = nullptr;
  if (size <= 0) {
    return nullptr;
  }
  if (posix_memalign(&memory, gPageAlignment, static_cast<size_t>(size)) != 0) {
    return nullptr;
  }
  std::memset(memory, 0, static_cast<size_t>(size));
  return memory;
}

// 1. DefaultAllocatorNative methods
jobject createAllocation(JNIEnv *env, jclass clazz, jint size) {
  void *memory = allocateZeroedMemory(size);
  if (memory == nullptr) {
    return nullptr;
  }

  jobject buffer = env->NewDirectByteBuffer(memory, size);
  if (buffer == nullptr) {
    free(memory);
    return nullptr;
  }

  if (gAllocationClass == nullptr || gAllocationConstructor == nullptr) {
    free(memory);
    return nullptr;
  }

  jobject allocation =
      env->NewObject(gAllocationClass, gAllocationConstructor, buffer, 0, reinterpret_cast<jlong>(memory));
  if (allocation == nullptr) {
    free(memory);
  }
  return allocation;
}

// CriticalNative optimization: No JNIEnv* or jclass!
void freeAllocation(jlong handle) {
  if (handle != 0) {
    free(reinterpret_cast<void *>(handle));
  }
}

// 2. SampleDataQueueNative methods
jboolean copyFromArray(JNIEnv *env, jclass clazz, jbyteArray source, jint sourceOffset,
                       jobject target, jint targetOffset, jint length) {
  auto *targetAddress = static_cast<uint8_t *>(env->GetDirectBufferAddress(target));
  if (targetAddress == nullptr || source == nullptr) {
    return JNI_FALSE;
  }

  env->GetByteArrayRegion(
      source, sourceOffset, length,
      reinterpret_cast<jbyte *>(targetAddress + targetOffset));
  return env->ExceptionCheck() ? JNI_FALSE : JNI_TRUE;
}

jboolean copyToArray(JNIEnv *env, jclass clazz, jobject source, jint sourceOffset,
                     jbyteArray target, jint targetOffset, jint length) {
  auto *sourceAddress = static_cast<uint8_t *>(env->GetDirectBufferAddress(source));
  if (sourceAddress == nullptr || target == nullptr) {
    return JNI_FALSE;
  }

  env->SetByteArrayRegion(
      target, targetOffset, length,
      reinterpret_cast<jbyte *>(sourceAddress + sourceOffset));
  return env->ExceptionCheck() ? JNI_FALSE : JNI_TRUE;
}

jboolean copyBetweenDirectBuffers(JNIEnv *env, jclass clazz, jobject source, jint sourceOffset,
                                  jobject target, jint targetOffset, jint length) {
  auto *sourceAddress = static_cast<uint8_t *>(env->GetDirectBufferAddress(source));
  auto *targetAddress = static_cast<uint8_t *>(env->GetDirectBufferAddress(target));
  if (sourceAddress == nullptr || targetAddress == nullptr) {
    return JNI_FALSE;
  }

  std::memcpy(targetAddress + targetOffset, sourceAddress + sourceOffset,
              static_cast<size_t>(length));
  return JNI_TRUE;
}

// Registration tables
const JNINativeMethod gAllocatorMethods[] = {
    {"nativeCreateAllocation", "(I)Landroidx/media3/exoplayer/upstream/Allocation;", reinterpret_cast<void *>(createAllocation)},
    {"nativeFreeAllocation", "(J)V", reinterpret_cast<void *>(freeAllocation)}
};

const JNINativeMethod gQueueMethods[] = {
    {"nativeCopyFromArray", "([BILjava/nio/ByteBuffer;II)Z", reinterpret_cast<void *>(copyFromArray)},
    {"nativeCopyToArray", "(Ljava/nio/ByteBuffer;I[BII)Z", reinterpret_cast<void *>(copyToArray)},
    {"nativeCopyBetweenDirectBuffers", "(Ljava/nio/ByteBuffer;ILjava/nio/ByteBuffer;II)Z", reinterpret_cast<void *>(copyBetweenDirectBuffers)}
};

} // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
  JNIEnv *env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
    return JNI_ERR;
  }

  long pageSize = sysconf(_SC_PAGESIZE);
  if (pageSize > 0) {
    gPageAlignment = static_cast<size_t>(pageSize);
  }

  // Find Allocation class
  jclass localClass = env->FindClass("androidx/media3/exoplayer/upstream/Allocation");
  if (localClass == nullptr) {
    env->ExceptionClear();
    return JNI_ERR;
  }

  gAllocationClass = reinterpret_cast<jclass>(env->NewGlobalRef(localClass));
  if (gAllocationClass == nullptr) {
    return JNI_ERR;
  }

  gAllocationConstructor = env->GetMethodID(gAllocationClass, "<init>", "(Ljava/nio/ByteBuffer;IJ)V");
  if (gAllocationConstructor == nullptr) {
    env->ExceptionClear();
    env->DeleteGlobalRef(gAllocationClass);
    gAllocationClass = nullptr;
    return JNI_ERR;
  }

  // Register DefaultAllocatorNative methods
  jclass allocatorClass = env->FindClass("androidx/media3/exoplayer/upstream/DefaultAllocatorNative");
  if (allocatorClass == nullptr) {
    env->ExceptionClear();
    return JNI_ERR;
  }
  if (env->RegisterNatives(allocatorClass, gAllocatorMethods, sizeof(gAllocatorMethods) / sizeof(JNINativeMethod)) < 0) {
    return JNI_ERR;
  }

  // Register SampleDataQueueNative methods
  jclass queueClass = env->FindClass("androidx/media3/exoplayer/source/SampleDataQueueNative");
  if (queueClass == nullptr) {
    env->ExceptionClear();
    return JNI_ERR;
  }
  if (env->RegisterNatives(queueClass, gQueueMethods, sizeof(gQueueMethods) / sizeof(JNINativeMethod)) < 0) {
    return JNI_ERR;
  }

  return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
  JNIEnv *env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK) {
    if (gAllocationClass != nullptr) {
      env->DeleteGlobalRef(gAllocationClass);
      gAllocationClass = nullptr;
    }
  }
}

} // extern "C"
