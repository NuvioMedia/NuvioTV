#include "ffmpeg_vc1_pool.h"

#include <android/log.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <unistd.h>

#include <chrono>
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


int drain_pending_frames(AVCodecContext* ctx) {
  AVFrame* junk = av_frame_alloc();
  if (!junk) {
    return AVERROR(ENOMEM);
  }
  int drained = 0;
  for (;;) {
    int ret = avcodec_receive_frame(ctx, junk);
    if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
      break;
    }
    if (ret < 0) {
      av_frame_free(&junk);
      return ret;
    }
    av_frame_unref(junk);
    drained++;
  }
  av_frame_free(&junk);
  return drained;
}

// Context copy can leave a finished I/P sitting in the worker. FFmpeg then
// returns EAGAIN on send until that frame is received. That leftover is not
// this B-frame, so discard it, send the packet, and only then keep output.
int decode_b_packet(AVCodecContext* ctx, AVPacket* packet, AVFrame* frame) {
  int drained = drain_pending_frames(ctx);
  if (drained < 0) {
    return drained;
  }
  if (drained > 0) {
    static int drain_logs = 0;
    drain_logs++;
    if (drain_logs <= 3 || drain_logs % 120 == 0) {
      LOGI("VC-1 worker dropped %d leftover frames before B", drained);
    }
  }

  int send = avcodec_send_packet(ctx, packet);
  if (send == AVERROR(EAGAIN)) {
    int rec = avcodec_receive_frame(ctx, frame);
    if (rec < 0 && rec != AVERROR(EAGAIN) && rec != AVERROR_EOF) {
      return rec;
    }
    if (rec >= 0) {
      av_frame_unref(frame);
    }
    send = avcodec_send_packet(ctx, packet);
  }
  if (send < 0) {
    return send;
  }

  int rec = avcodec_receive_frame(ctx, frame);
  if (rec != AVERROR(EAGAIN)) {
    return rec;
  }

  send = avcodec_send_packet(ctx, NULL);
  if (send < 0 && send != AVERROR_EOF) {
    return rec;
  }
  rec = avcodec_receive_frame(ctx, frame);
  avcodec_flush_buffers(ctx);
  return rec;
}

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
    int result = (packet && frame) ? decode_b_packet(worker->ctx, packet, frame)
                                   : AVERROR(EINVAL);
    if (packet) {
      av_packet_unref(packet);
    }
    {
      std::lock_guard<std::mutex> lock(worker->mu);
      worker->busy = false;
      worker->finished = true;
      if (result < 0 || !frame) {
        av_frame_free(&frame);
        worker->frame = NULL;
        worker->result = result < 0 ? result : -1;
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
    int result = worker->result;
    job->frame = worker->frame;
    job->failed = result < 0 || worker->frame == NULL;
    worker->frame = NULL;
    worker->finished = false;
    worker->result = 0;
    job->worker_index = -1;
    if (job->failed) {
      // One bad B-frame used to latch the pool off for the rest of playback,
      // which left every later frame on a single core.
      static int failures = 0;
      failures++;
      if (failures <= 3 || failures % 120 == 0) {
        LOGE("VC-1 B-frame worker failed err=%d count=%d", result, failures);
      }
      avcodec_flush_buffers(worker->ctx);
    }
  }
}

void reclaim_orphan_workers_locked(Vc1PoolState* pool) {
  for (int i = 0; i < pool->worker_count; ++i) {
    Worker* worker = &pool->workers[i];
    std::lock_guard<std::mutex> lock(worker->mu);
    if (!worker->finished) {
      continue;
    }
    bool referenced = false;
    for (size_t j = 0; j < pool->jobs.size(); ++j) {
      if (pool->jobs[j].worker_index == i) {
        referenced = true;
        break;
      }
    }
    if (referenced) {
      continue;
    }
    av_frame_free(&worker->frame);
    worker->finished = false;
    worker->result = 0;
  }
}

int find_idle_worker(Vc1PoolState* pool) {
  reclaim_orphan_workers_locked(pool);
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
  if (!pool || !vc1_frame_is_progressive_b(main_ctx, data, size)) {
    return 0;
  }
  int worker_index;
  {
    std::lock_guard<std::mutex> lock(pool->mu);
    collect_finished_locked(pool);
    worker_index = find_idle_worker(pool);
  }
  if (worker_index < 0) {
    return 0;
  }
  Worker* worker = &pool->workers[worker_index];
  if (vc1_sync_worker(worker->ctx, main_ctx) < 0) {
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
    // Leave a single running B-frame in the queue so the next packet can
    // start beside it. With two or more queued, deliver the oldest instead
    // of telling the renderer to skip.
    if ((int)pool->jobs.size() < 2) {
      return 0;
    }
    pool->cv.wait_for(lock, std::chrono::milliseconds(500), [&] {
      collect_finished_locked(pool);
      return pool->jobs.empty() || pool->jobs.front().failed || pool->jobs.front().frame != NULL;
    });
  }
}
