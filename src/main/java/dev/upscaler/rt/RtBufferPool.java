package dev.upscaler.rt;

import dev.upscaler.UpscalerMod;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * P5-perf #1 (step 1): a size-bucketed recycling free-list for the per-frame entity path.
 *
 * <p>{@link RtEntities} re-captures every model entity into a fresh mesh + BLAS each frame, churning
 * ~6 VMA allocations per entity per frame (4 mesh buffers + AS backing + scratch). Routing those through
 * this pool — {@link #acquire} instead of {@code ctx.createBuffer}, {@link #release} instead of
 * {@code destroy()} at the deferred-free horizon — drives steady-state allocations to ~zero (allocate
 * once, recycle), relieving {@code maxMemoryAllocationCount} pressure and CPU alloc cost.
 *
 * <p>Buffers are bucketed to the next power of two (≤2× waste, O(1) reuse). The caller writes/reads only
 * the requested-size prefix; the device address is the buffer base, so the unused tail is harmless and
 * geometry/table addresses stay correct. The pool is entity-agnostic (no per-id identity) — that, plus
 * UPDATE-mode BLAS refit, is the deliberate follow-up step.
 *
 * <p>Correctness: callers only {@link #release} at the existing {@code frameCounter + KEEP_FRAMES}
 * horizon, i.e. once a buffer's last GPU use is off all queues, so a reacquired buffer is always safe to
 * overwrite. Single-threaded (render thread). Not safe to acquire/release concurrently.
 */
public final class RtBufferPool {
    private static final boolean STATS = Boolean.parseBoolean(System.getProperty("upscaler.rt.poolStats", "false"));
    private static final int STATS_INTERVAL = 600; // frames between stat dumps
    // Floor so tiny meshes don't fragment the pool into many small buckets.
    private static final long MIN_BUCKET = 256;
    // Cap the free-list depth per (usage,hostVisible,bucket) so a transient entity spike can't strand
    // unbounded VRAM; surplus buffers are destroyed on release.
    private static final int MAX_FREE_PER_KEY = 64;

    private record PoolKey(int usage, boolean hostVisible, long bucket) {
    }

    private final Map<PoolKey, ArrayDeque<RtBuffer>> free = new HashMap<>();

    private long created;
    private long reused;
    private long statsCounter;

    /** Acquire a buffer with capacity ≥ {@code minSize} for the given usage, reusing a free one if available. */
    public RtBuffer acquire(RtContext ctx, long minSize, int usage, boolean hostVisible) {
        long bucket = ceilPow2(Math.max(minSize, MIN_BUCKET));
        ArrayDeque<RtBuffer> list = free.get(new PoolKey(usage, hostVisible, bucket));
        if (list != null && !list.isEmpty()) {
            reused++;
            return list.pop();
        }
        created++;
        return ctx.createBuffer(bucket, usage, hostVisible);
    }

    /** Return a buffer to the pool for reuse (or destroy it if the matching free-list is already full). */
    public void release(RtBuffer b) {
        PoolKey key = new PoolKey(b.usage, b.hostVisible, b.size);
        ArrayDeque<RtBuffer> list = free.computeIfAbsent(key, k -> new ArrayDeque<>());
        if (list.size() >= MAX_FREE_PER_KEY) {
            b.destroy();
        } else {
            list.push(b);
        }
    }

    /** Destroy every pooled buffer (teardown; caller must have drained outstanding deferred releases first). */
    public void destroyAll() {
        for (ArrayDeque<RtBuffer> list : free.values()) {
            for (RtBuffer b : list) {
                b.destroy();
            }
        }
        free.clear();
    }

    /** Periodic stat dump (opt-in via {@code -Dupscaler.rt.poolStats}); call once per frame. */
    public void maybeLogStats() {
        if (!STATS || (statsCounter++ % STATS_INTERVAL) != 0) {
            return;
        }
        long freeBytes = 0;
        int freeCount = 0;
        for (ArrayDeque<RtBuffer> list : free.values()) {
            for (RtBuffer b : list) {
                freeBytes += b.size;
                freeCount++;
            }
        }
        UpscalerMod.LOGGER.info("RT entity buffer pool: created={} reused={} (this run) | free-list {} buffers, {} KiB",
                created, reused, freeCount, freeBytes / 1024);
    }

    private static long ceilPow2(long v) {
        long p = 1;
        while (p < v) {
            p <<= 1;
        }
        return p;
    }
}
