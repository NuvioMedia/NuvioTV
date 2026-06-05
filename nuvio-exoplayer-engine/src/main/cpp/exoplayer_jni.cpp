#include <jni.h>
#include <sys/mman.h>
#include <unistd.h>
#include <dlfcn.h>
#include <android/api-level.h>
#include <android/log.h>

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <new>

#define LOG_TAG "NuvioNativeAlloc"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

// ─── Allocation class cache ──────────────────────────────────────────────────

jclass gAllocationClass = nullptr;
jmethodID gAllocationConstructor = nullptr;       // (ByteBuffer, int, long)
jmethodID gHwAllocationConstructor = nullptr;      // (ByteBuffer, int, long, boolean)

// ─── AHardwareBuffer API via dlsym (API 26+) ────────────────────────────────

// AHardwareBuffer_Desc layout — matches NDK <android/hardware_buffer.h>
struct NuvioHwBufferDesc {
  uint32_t width;
  uint32_t height;
  uint32_t layers;
  uint32_t format;
  uint64_t usage;
  uint32_t stride;
  uint32_t rfu0;
  uint32_t rfu1;
};

// Opaque handle — never dereferenced, only passed to API functions
typedef void NuvioHwBuffer;

// Constants matching NDK definitions
constexpr uint32_t NUVIO_AHWB_FORMAT_BLOB       = 0x21;
constexpr uint64_t NUVIO_AHWB_USAGE_CPU_READ    = 0x00000003ULL; // CPU_READ_OFTEN
constexpr uint64_t NUVIO_AHWB_USAGE_CPU_WRITE   = 0x00000030ULL; // CPU_WRITE_OFTEN

// Function pointer types
using FnAllocate = int (*)(const NuvioHwBufferDesc *, NuvioHwBuffer **);
using FnRelease  = void (*)(NuvioHwBuffer *);
using FnLock     = int (*)(NuvioHwBuffer *, uint64_t, int32_t, const void *, void **);
using FnUnlock   = int (*)(NuvioHwBuffer *, int32_t *);

struct HwBufferApi {
  FnAllocate allocate = nullptr;
  FnRelease  release  = nullptr;
  FnLock     lock     = nullptr;
  FnUnlock   unlock   = nullptr;
  bool       available = false;
};

static HwBufferApi gHwApi;

// Persistent-lock allocation: HardwareBuffer stays locked for its entire lifetime
// to avoid lock/unlock overhead on the hot playback path.
struct HwBufferAllocation {
  NuvioHwBuffer *hwBuffer;
  void          *cpuAddress;
  size_t         size;
};

static void initHwBufferApi() {
  if (android_get_device_api_level() < 26) {
    LOGI("API level < 26, HardwareBuffer disabled");
    return;
  }
  void *lib = dlopen("libnativewindow.so", RTLD_NOW);
  if (!lib) {
    LOGW("Failed to dlopen libnativewindow.so");
    return;
  }
  gHwApi.allocate = reinterpret_cast<FnAllocate>(dlsym(lib, "AHardwareBuffer_allocate"));
  gHwApi.release  = reinterpret_cast<FnRelease>(dlsym(lib, "AHardwareBuffer_release"));
  gHwApi.lock     = reinterpret_cast<FnLock>(dlsym(lib, "AHardwareBuffer_lock"));
  gHwApi.unlock   = reinterpret_cast<FnUnlock>(dlsym(lib, "AHardwareBuffer_unlock"));
  gHwApi.available = gHwApi.allocate && gHwApi.release
                     && gHwApi.lock && gHwApi.unlock;
  LOGI("HardwareBuffer API %s", gHwApi.available ? "available" : "NOT available");
  // Note: we intentionally do NOT dlclose — the library stays loaded for process lifetime
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

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

// Allocates gralloc-backed memory via AHardwareBuffer with persistent CPU lock.
// Returns the CPU-mapped address; on failure returns nullptr.
void *allocateHardwareBufferMemory(jint size, NuvioHwBuffer **outHwBuffer) {
  if (!gHwApi.available || size <= 0) {
    return nullptr;
  }

  NuvioHwBufferDesc desc = {};
  desc.width  = static_cast<uint32_t>(size);
  desc.height = 1;
  desc.layers = 1;
  desc.format = NUVIO_AHWB_FORMAT_BLOB;
  desc.usage  = NUVIO_AHWB_USAGE_CPU_READ | NUVIO_AHWB_USAGE_CPU_WRITE;

  NuvioHwBuffer *hwBuffer = nullptr;
  if (gHwApi.allocate(&desc, &hwBuffer) != 0 || !hwBuffer) {
    return nullptr;
  }

  void *cpuAddress = nullptr;
  if (gHwApi.lock(hwBuffer,
                  NUVIO_AHWB_USAGE_CPU_READ | NUVIO_AHWB_USAGE_CPU_WRITE,
                  -1,        // fence fd — no sync needed
                  nullptr,   // rect — lock entire buffer
                  &cpuAddress) != 0 || !cpuAddress) {
    gHwApi.release(hwBuffer);
    return nullptr;
  }

  std::memset(cpuAddress, 0, static_cast<size_t>(size));
  *outHwBuffer = hwBuffer;
  return cpuAddress;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
  JNIEnv *env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
    return JNI_ERR;
  }

  jclass localClass = env->FindClass("androidx/media3/exoplayer/upstream/Allocation");
  if (localClass == nullptr) {
    env->ExceptionClear();
    return JNI_ERR;
  }

  gAllocationClass = reinterpret_cast<jclass>(env->NewGlobalRef(localClass));
  if (gAllocationClass == nullptr) {
    return JNI_ERR;
  }

  // Cache the standard (ByteBuffer, int, long) constructor for posix_memalign allocations
  gAllocationConstructor = env->GetMethodID(gAllocationClass, "<init>", "(Ljava/nio/ByteBuffer;IJ)V");
  if (gAllocationConstructor == nullptr) {
    env->ExceptionClear();
    env->DeleteGlobalRef(gAllocationClass);
    gAllocationClass = nullptr;
    return JNI_ERR;
  }

  // Cache the (ByteBuffer, int, long, boolean) constructor for HardwareBuffer allocations
  gHwAllocationConstructor = env->GetMethodID(gAllocationClass, "<init>", "(Ljava/nio/ByteBuffer;IJZ)V");
  if (gHwAllocationConstructor == nullptr) {
    env->ExceptionClear();
    // Not fatal — HardwareBuffer path will be disabled, posix_memalign still works
    LOGW("HwBuffer Allocation constructor not found, HardwareBuffer path disabled");
  }

  // Initialize AHardwareBuffer API via dlsym
  initHwBufferApi();
  if (gHwAllocationConstructor == nullptr) {
    gHwApi.available = false;
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

// ─── posix_memalign allocation (existing) ────────────────────────────────────

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

  if (gAllocationClass == nullptr || gAllocationConstructor == nullptr) {
    free(memory);
    return nullptr;
  }

  jobject allocation =
      env->NewObject(gAllocationClass, gAllocationConstructor, buffer, 0, (jlong)memory);
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

// ─── AHardwareBuffer allocation (new) ────────────────────────────────────────

JNIEXPORT jobject JNICALL
Java_androidx_media3_exoplayer_upstream_DefaultAllocatorNative_nativeCreateHwBufferAllocation(
    JNIEnv *env, jclass clazz, jint size) {
  NuvioHwBuffer *hwBuffer = nullptr;
  void *cpuAddress = allocateHardwareBufferMemory(size, &hwBuffer);
  if (cpuAddress == nullptr) {
    return nullptr;
  }

  jobject directBuffer = env->NewDirectByteBuffer(cpuAddress, size);
  if (directBuffer == nullptr) {
    gHwApi.unlock(hwBuffer, nullptr);
    gHwApi.release(hwBuffer);
    return nullptr;
  }

  // Store both pointers in a heap struct so we can unlock+release on free
  auto *alloc = new (std::nothrow) HwBufferAllocation{hwBuffer, cpuAddress, static_cast<size_t>(size)};
  if (alloc == nullptr) {
    gHwApi.unlock(hwBuffer, nullptr);
    gHwApi.release(hwBuffer);
    return nullptr;
  }

  if (gAllocationClass == nullptr || gHwAllocationConstructor == nullptr) {
    gHwApi.unlock(hwBuffer, nullptr);
    gHwApi.release(hwBuffer);
    delete alloc;
    return nullptr;
  }

  jobject allocation = env->NewObject(
      gAllocationClass, gHwAllocationConstructor,
      directBuffer, 0, reinterpret_cast<jlong>(alloc), JNI_TRUE);
  if (allocation == nullptr) {
    gHwApi.unlock(hwBuffer, nullptr);
    gHwApi.release(hwBuffer);
    delete alloc;
  }
  return allocation;
}

JNIEXPORT void JNICALL
Java_androidx_media3_exoplayer_upstream_DefaultAllocatorNative_nativeFreeHwBufferAllocation(
    JNIEnv *env, jclass clazz, jlong handle) {
  if (handle == 0) {
    return;
  }
  auto *alloc = reinterpret_cast<HwBufferAllocation *>(handle);
  gHwApi.unlock(alloc->hwBuffer, nullptr);
  gHwApi.release(alloc->hwBuffer);
  delete alloc;
}

JNIEXPORT jboolean JNICALL
Java_androidx_media3_exoplayer_upstream_DefaultAllocatorNative_nativeIsHwBufferAvailable(
    JNIEnv *env, jclass clazz) {
  return gHwApi.available ? JNI_TRUE : JNI_FALSE;
}

// ─── Buffer copy helpers (unchanged) ─────────────────────────────────────────

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
