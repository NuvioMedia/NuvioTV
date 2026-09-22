#include "ffmpeg_vc1_pool.h"

#include <android/log.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <unistd.h>

#include <condition_variable>
#include <deque>
#include <mutex>
#include <thread>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/cpu.h>
}

#define LOG_TAG "ffmpeg_jni"
#define LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))
#define LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__))

namespace {

struct Job {
  int64_t pts;
  AVFrame* frame;
  int worker_index;
  bool failed;
};

struct Worker {
  AVCodecContext* ctx;
  AVPacket* packet;
  AVFrame* frame;
  std::mutex mu;
  std::condition_variable cv;
  std::thread thread;
  bool has_job;
  bool busy;
  bool finished;
  bool stop;
  int result;
};

struct Vc1PoolState {
  Worker workers[3];
  int worker_count;
  std::mutex mu;
  std::condition_variable cv;
  std::deque<Job> jobs;
  bool dead;
};


void worker_loop(Vc1PoolState* pool, Worker* worker) {
  pthread_setname_np(pthread_self(), "VC1BFrame");
  setpriority(PRIO_PROCESS, gettid(), -16);
  for (;;) {
    AVPacket* packet = NULL;
    {
      std::unique_lock<std::mutex> lock(worker->mu);
      worker->cv.wait(lock, [&] { return worker->stop || worker->has_job; });
      if (worker->stop && !worker->has_job) {
        break;
      }
      packet = worker->packet;
      worker->has_job = false;
      worker->busy = true;
      worker->finished = false;
    }
    AVFrame* frame = av_frame_alloc();
    int send_result = packet ? avcodec_send_packet(worker->ctx, packet) : AVERROR(EINVAL);
    int receive_result = send_result < 0 ? send_result : avcodec_receive_frame(worker->ctx, frame);
    if (packet) {
      av_packet_unref(packet);
    }
    {
      std::lock_guard<std::mutex> lock(worker->mu);
      worker->busy = false;
      worker->finished = true;
      if (receive_result < 0 || !frame) {
        av_frame_free(&frame);
        worker->frame = NULL;
        worker->result = -1;
      } else {
        worker->frame = frame;
        worker->result = 0;
      }
    }
    pool->cv.notify_all();
  }
}

void collect_finished_locked(Vc1PoolState* pool) {
  for (size_t i = 0; i < pool->jobs.size(); ++i) {
    Job* job = &pool->jobs[i];
    if (job->worker_index < 0) {
      continue;
    }
    Worker* worker = &pool->workers[job->worker_index];
    std::lock_guard<std::mutex> lock(worker->mu);
    if (!worker->finished) {
      continue;
    }
    job->frame = worker->frame;
    job->failed = worker->result < 0 || worker->frame == NULL;
    worker->frame = NULL;
    worker->finished = false;
    worker->result = 0;
    job->worker_index = -1;
    if (job->failed) {
      pool->dead = true;
    }
  }
}

int find_idle_worker(Vc1PoolState* pool) {
  for (int i = 0; i < pool->worker_count; ++i) {
    Worker* worker = &pool->workers[i];
    std::lock_guard<std::mutex> lock(worker->mu);
    if (!worker->has_job && !worker->busy && !worker->finished) {
      return i;
    }
  }
  return -1;
}

AVCodecContext* open_worker(const AVCodec* codec, const uint8_t* extradata, int extradata_size) {
  AVCodecContext* ctx = avcodec_alloc_context3(codec);
  if (!ctx) {
    return NULL;
  }
  if (extradata && extradata_size > 0) {
    ctx->extradata_size = extradata_size;
    ctx->extradata = (uint8_t*)av_mallocz(extradata_size + AV_INPUT_BUFFER_PADDING_SIZE);
    if (!ctx->extradata) {
      avcodec_free_context(&ctx);
      return NULL;
    }
    memcpy(ctx->extradata, extradata, extradata_size);
  }
  ctx->thread_count = 1;
  ctx->thread_type = 0;
  ctx->err_recognition = AV_EF_IGNORE_ERR;
  ctx->pkt_timebase = AV_TIME_BASE_Q;
  if (avcodec_open2(ctx, codec, NULL) < 0) {
    avcodec_free_context(&ctx);
    return NULL;
  }
  return ctx;
}

}  // namespace

Vc1Pool* vc1_pool_create(const AVCodec* codec, const uint8_t* extradata, int extradata_size) {
  int cores = av_cpu_count();
  int workers = cores - 1;
  if (workers < 1) {
    return NULL;
  }
  if (workers > 3) {
    workers = 3;
  }
  Vc1PoolState* pool = new Vc1PoolState();
  pool->worker_count = 0;
  pool->dead = false;
  for (int i = 0; i < workers; ++i) {
    Worker* worker = &pool->workers[i];
    worker->ctx = open_worker(codec, extradata, extradata_size);
    worker->packet = av_packet_alloc();
    worker->frame = NULL;
    worker->has_job = false;
    worker->busy = false;
    worker->finished = false;
    worker->stop = false;
    worker->result = 0;
    if (!worker->ctx || !worker->packet) {
      avcodec_free_context(&worker->ctx);
      av_packet_free(&worker->packet);
      vc1_pool_destroy((Vc1Pool*)pool);
      return NULL;
    }
    worker->thread = std::thread(worker_loop, pool, worker);
    pool->worker_count++;
  }
  LOGI("VC-1 B-frames on %d extra cores", pool->worker_count);
  return (Vc1Pool*)pool;
}

void vc1_pool_destroy(Vc1Pool* opaque) {
  Vc1PoolState* pool = (Vc1PoolState*)opaque;
  if (!pool) {
    return;
  }
  for (int i = 0; i < pool->worker_count; ++i) {
    Worker* worker = &pool->workers[i];
    {
      std::lock_guard<std::mutex> lock(worker->mu);
      worker->stop = true;
    }
    worker->cv.notify_all();
  }
  for (int i = 0; i < pool->worker_count; ++i) {
    Worker* worker = &pool->workers[i];
    if (worker->thread.joinable()) {
      worker->thread.join();
    }
    av_frame_free(&worker->frame);
    av_packet_free(&worker->packet);
    avcodec_free_context(&worker->ctx);
  }
  for (size_t i = 0; i < pool->jobs.size(); ++i) {
    av_frame_free(&pool->jobs[i].frame);
  }
  delete pool;
}

void vc1_pool_flush(Vc1Pool* opaque) {
  Vc1PoolState* pool = (Vc1PoolState*)opaque;
  if (!pool) {
    return;
  }
  std::unique_lock<std::mutex> lock(pool->mu);
  pool->cv.wait(lock, [&] {
    collect_finished_locked(pool);
    for (int i = 0; i < pool->worker_count; ++i) {
      std::lock_guard<std::mutex> worker_lock(pool->workers[i].mu);
      if (pool->workers[i].has_job || pool->workers[i].busy) {
        return false;
      }
    }
    return true;
  });
  for (size_t i = 0; i < pool->jobs.size(); ++i) {
    av_frame_free(&pool->jobs[i].frame);
  }
  pool->jobs.clear();
  pool->dead = false;
  lock.unlock();
  for (int i = 0; i < pool->worker_count; ++i) {
    avcodec_flush_buffers(pool->workers[i].ctx);
  }
}

int vc1_pool_submit_b(Vc1Pool* opaque, AVCodecContext* main_ctx, const uint8_t* data, int size,
                      int64_t pts) {
  Vc1PoolState* pool = (Vc1PoolState*)opaque;
  if (!pool || pool->dead || !vc1_frame_is_progressive_b(main_ctx, data, size)) {
    return 0;
  }
  int worker_index;
  {
    std::lock_guard<std::mutex> lock(pool->mu);
    collect_finished_locked(pool);
    if (pool->dead) {
      return 0;
    }
    worker_index = find_idle_worker(pool);
  }
  if (worker_index < 0) {
    return 0;
  }
  Worker* worker = &pool->workers[worker_index];
  if (vc1_sync_worker(worker->ctx, main_ctx) < 0) {
    pool->dead = true;
    LOGE("VC-1 worker sync failed");
    return 0;
  }
  if (av_new_packet(worker->packet, size) < 0) {
    return 0;
  }
  memcpy(worker->packet->data, data, (size_t)size);
  {
    std::lock_guard<std::mutex> lock(worker->mu);
    worker->has_job = true;
    worker->finished = false;
  }
  worker->cv.notify_all();
  {
    std::lock_guard<std::mutex> lock(pool->mu);
    Job job;
    job.pts = pts;
    job.frame = NULL;
    job.worker_index = worker_index;
    job.failed = false;
    pool->jobs.push_back(job);
  }
  static int offloaded = 0;
  offloaded++;
  if (offloaded == 1 || offloaded % 120 == 0) {
    LOGI("VC-1 offloaded B-frames %d", offloaded);
  }
  return 1;
}

void vc1_pool_push_frame(Vc1Pool* opaque, AVFrame* frame, int64_t pts) {
  Vc1PoolState* pool = (Vc1PoolState*)opaque;
  if (!pool || !frame) {
    return;
  }
  AVFrame* copy = av_frame_alloc();
  if (!copy || av_frame_ref(copy, frame) < 0) {
    av_frame_free(&copy);
    return;
  }
  std::lock_guard<std::mutex> lock(pool->mu);
  Job job;
  job.pts = pts;
  job.frame = copy;
  job.worker_index = -1;
  job.failed = false;
  pool->jobs.push_back(job);
}

int vc1_pool_pop_frame(Vc1Pool* opaque, AVFrame** out_frame, int64_t* out_pts) {
  Vc1PoolState* pool = (Vc1PoolState*)opaque;
  if (!pool || !out_frame || !out_pts) {
    return 0;
  }
  std::unique_lock<std::mutex> lock(pool->mu);
  for (;;) {
    collect_finished_locked(pool);
    while (!pool->jobs.empty() && pool->jobs.front().failed) {
      pool->jobs.pop_front();
    }
    if (pool->jobs.empty()) {
      return 0;
    }
    Job* front = &pool->jobs.front();
    if (front->frame) {
      *out_frame = front->frame;
      *out_pts = front->pts;
      front->frame = NULL;
      pool->jobs.pop_front();
      return 1;
    }
    if ((int)pool->jobs.size() < pool->worker_count + 1) {
      return 0;
    }
    pool->cv.wait(lock);
  }
}
