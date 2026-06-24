package dev.upscaler.rt.accel;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAccelerationStructureTrianglesOpacityMicromapEXT;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkMicromapBuildInfoEXT;
import org.lwjgl.vulkan.VkMicromapBuildSizesInfoEXT;
import org.lwjgl.vulkan.VkMicromapCreateInfoEXT;
import org.lwjgl.vulkan.VkMicromapTriangleEXT;
import org.lwjgl.vulkan.VkMicromapUsageEXT;

import dev.upscaler.rt.RtContext;
import dev.upscaler.rt.RtDebugLabels;

import java.util.List;

import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_ACCESS_2_MICROMAP_WRITE_BIT_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_BUFFER_USAGE_MICROMAP_BUILD_INPUT_READ_ONLY_BIT_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_BUFFER_USAGE_MICROMAP_STORAGE_BIT_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_BUILD_MICROMAP_MODE_BUILD_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_BUILD_MICROMAP_PREFER_FAST_TRACE_BIT_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_PIPELINE_STAGE_2_MICROMAP_BUILD_BIT_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.vkCmdBuildMicromapsEXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.vkCreateMicromapEXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.vkDestroyMicromapEXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.vkGetMicromapBuildSizesEXT;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPositionFetch.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_DATA_ACCESS_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_INDEX_TYPE_NONE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCreateAccelerationStructureKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkDestroyAccelerationStructureKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.vkCmdPipelineBarrier2KHR;

/**
 * A built acceleration structure (BLAS or TLAS) plus its backing buffer. Build with the static
 * factories; free with {@link #destroy()}. One BLAS per section; one TLAS rebuilt per frame.
 */
public final class RtAccel {
    private static final long MICROMAP_INPUT_ADDRESS_ALIGNMENT = 256L;

    public final long handle;
    public final long deviceAddress;

    private final RtBuffer backing;
    private final boolean ownsBacking;
    private final OpacityMicromap opacityMicromap;
    private final VkDevice vk;
    private boolean destroyed;

    private RtAccel(VkDevice vk, long handle, long deviceAddress, RtBuffer backing) {
        this(vk, handle, deviceAddress, backing, true);
    }

    private RtAccel(VkDevice vk, long handle, long deviceAddress, RtBuffer backing, boolean ownsBacking) {
        this(vk, handle, deviceAddress, backing, ownsBacking, null);
    }

    private RtAccel(VkDevice vk, long handle, long deviceAddress, RtBuffer backing, boolean ownsBacking,
                    OpacityMicromap opacityMicromap) {
        this.vk = vk;
        this.handle = handle;
        this.deviceAddress = deviceAddress;
        this.backing = backing;
        this.ownsBacking = ownsBacking;
        this.opacityMicromap = opacityMicromap;
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        if (handle != 0L) {
            vkDestroyAccelerationStructureKHR(vk, handle, null);
        }
        if (opacityMicromap != null) {
            opacityMicromap.destroy();
        }
        // A pooled BLAS's backing is owned by RtBufferPool (recycled, not destroyed here).
        if (ownsBacking) {
            backing.destroy();
        }
        destroyed = true;
    }

    /** CPU-generated opacity micromap input for one terrain geometry's triangle order. */
    public record OpacityMicromapInput(byte[] data, int triangleCount, int subdivisionLevel, int bytesPerTriangle) {
    }

    private static final class OpacityMicromap {
        final VkDevice vk;
        final long handle;
        final RtBuffer backing;
        RtBuffer data;
        RtBuffer triangles;
        RtBuffer scratch;
        final long dataAddress;
        final long triangleArrayAddress;
        final int triangleCount;
        final int subdivisionLevel;
        final int bytesPerTriangle;
        boolean destroyed;

        OpacityMicromap(VkDevice vk, long handle, RtBuffer backing, RtBuffer data, RtBuffer triangles,
                        RtBuffer scratch, long dataAddress, long triangleArrayAddress, int triangleCount,
                        int subdivisionLevel, int bytesPerTriangle) {
            this.vk = vk;
            this.handle = handle;
            this.backing = backing;
            this.data = data;
            this.triangles = triangles;
            this.scratch = scratch;
            this.dataAddress = dataAddress;
            this.triangleArrayAddress = triangleArrayAddress;
            this.triangleCount = triangleCount;
            this.subdivisionLevel = subdivisionLevel;
            this.bytesPerTriangle = bytesPerTriangle;
        }

        void freeBuildInputs() {
            if (scratch != null) {
                scratch.destroy();
                scratch = null;
            }
            if (triangles != null) {
                triangles.destroy();
                triangles = null;
            }
            if (data != null) {
                data.destroy();
                data = null;
            }
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            if (handle != 0L) {
                vkDestroyMicromapEXT(vk, handle, null);
            }
            freeBuildInputs();
            backing.destroy();
            destroyed = true;
        }
    }

    /**
     * A BLAS whose AS + backing buffer are allocated but whose build command is recorded later, so
     * many sections' builds can be batched into one submission — one {@code vkQueueSubmit} + fence
     * wait per tick instead of one per section (each submit drains the graphics queue, so per-section
     * submits were the dominant terrain-streaming stall).
     * {@code opaque} marks geometry {@code OPAQUE} (solid, no any-hit) vs
     * {@code NO_DUPLICATE_ANY_HIT_INVOCATION} for alpha-tested cutout.
     */
    public static final class PreparedBlas {
        public final RtAccel accel;
        private final RtBuffer scratch;
        // Non-null only for a pooled BLAS (see prepareTrianglesBlasPooled): the AS backing buffer, owned
        // by RtBufferPool, so releaseBlasToPool can return it rather than destroying it.
        private final RtBuffer pooledBacking;
        private final long vertexAddr;
        private final long indexAddr;
        private final int maxVertex;
        private final int triangleCount;
        private final boolean opaque;
        private final String label;
        // Refit support. {@code updatable} = built with ALLOW_UPDATE (so it can be refit later);
        // {@code update} = this recorded op is an in-place UPDATE (refit) rather than a full BUILD.
        // Set for the entity refit path; false for terrain + pooled block entities.
        private final boolean updatable;
        private final boolean update;
        // Terrain multi-geometry split (any-hit opt): one geometry per material bucket, in the fixed packed
        // order { solid, cutout, water } (see TERRAIN_BUCKETS). Bucket 0 (solid) is flagged
        // VK_GEOMETRY_OPAQUE_BIT so the driver skips world.rahit entirely; cutout + water keep
        // NO_DUPLICATE_ANY_HIT (cutout alpha-tests; water only passes shadow rays through). Empty buckets are
        // omitted, so geometry indices are compacted — the section table stores the per-geometry triangle
        // base (gl_PrimitiveID restarts at 0 per geometry) and which geometry is water. All geometries share
        // this BLAS's vertex + index buffers; each bucket's triangle range is selected by primitiveOffset.
        // terrainSplit == false ⇒ the single-geometry path (entities / pooled / refit) keyed on triangleCount.
        private final boolean terrainSplit;
        private final int[] terrainTris; // per-bucket triangle counts in TERRAIN_BUCKETS order (null if !terrainSplit)
        private final OpacityMicromap opacityMicromap; // optional, terrain cutout bucket only

        private PreparedBlas(RtAccel accel, RtBuffer scratch, RtBuffer pooledBacking, long vertexAddr, long indexAddr,
                             int maxVertex, int triangleCount, boolean opaque, String label, boolean updatable, boolean update) {
            this(accel, scratch, pooledBacking, vertexAddr, indexAddr, maxVertex, triangleCount, opaque, label,
                    updatable, update, false, null, null);
        }

        private PreparedBlas(RtAccel accel, RtBuffer scratch, RtBuffer pooledBacking, long vertexAddr, long indexAddr,
                             int maxVertex, int triangleCount, boolean opaque, String label, boolean updatable, boolean update,
                             boolean terrainSplit, int[] terrainTris, OpacityMicromap opacityMicromap) {
            this.accel = accel;
            this.scratch = scratch;
            this.pooledBacking = pooledBacking;
            this.vertexAddr = vertexAddr;
            this.indexAddr = indexAddr;
            this.maxVertex = maxVertex;
            this.triangleCount = triangleCount;
            this.opaque = opaque;
            this.label = label;
            this.updatable = updatable;
            this.update = update;
            this.terrainSplit = terrainSplit;
            this.terrainTris = terrainTris;
            this.opacityMicromap = opacityMicromap;
        }

        /** A terrain section BLAS split into per-bucket geometries (solid opaque, then cutout, then water),
         *  in {@link RtAccel#TERRAIN_BUCKETS} order. Empty buckets are omitted (their geometry isn't built). */
        static PreparedBlas terrain(RtAccel accel, RtBuffer scratch, long vertexAddr, long indexAddr, int maxVertex,
                                    int[] terrainTris, OpacityMicromap opacityMicromap, String label) {
            int total = 0;
            for (int t : terrainTris) {
                total += t;
            }
            return new PreparedBlas(accel, scratch, null, vertexAddr, indexAddr, maxVertex,
                    total, false, label, false, false, true, terrainTris, opacityMicromap);
        }

        private void freeTransientBuildResources() {
            scratch.destroy();
            if (opacityMicromap != null) {
                opacityMicromap.freeBuildInputs();
            }
        }
    }

    /** Terrain material buckets, packed in this order into a section's geometry. Bucket 0 (solid) is the only
     *  opaque one (any-hit skipped); cutout alpha-tests; water only passes shadow rays through. */
    public static final int BUCKET_SOLID = 0;
    public static final int BUCKET_CUTOUT = 1;
    public static final int BUCKET_WATER = 2;
    public static final int TERRAIN_BUCKETS = 3;

    /**
     * Result of {@link #prepareUpdatableBlasBuild}: the per-frame BUILD op to record, plus the persistent
     * resources the caller's per-entity ring must keep ({@code backing}) and cache ({@code updateScratchSize}
     * for sizing later refit scratch). The {@code scratch} is this frame's transient build scratch (release
     * at the frames-in-flight horizon, like the mesh buffers); the {@code op.accel} + {@code backing} persist.
     */
    public record UpdatableBuild(PreparedBlas op, RtAccel accel, RtBuffer backing, RtBuffer scratch, long updateScratchSize) {
    }

    /** Allocate a BLAS (AS + backing + scratch) and query sizes, deferring the build to {@link #recordBlasBuilds}. */
    public static PreparedBlas prepareTrianglesBlas(RtContext ctx, RtBuffer positions, int vertexCount,
                                                    RtBuffer indices, int indexCount, boolean opaque, String label) {
        VkDevice vk = ctx.vk();
        String debugLabel = labelOr(label, "BLAS");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildSizesInfoKHR sizes = queryBlasSizes(vk, stack, positions, indices, vertexCount, indexCount, opaque, false);
            RtBuffer backing = ctx.createBuffer(sizes.accelerationStructureSize(), VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false,
                    debugLabel + " backing");
            RtBuffer scratch = ctx.createBuffer(sizes.buildScratchSize(), VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false,
                    debugLabel + " build scratch");
            RtAccel accel = createBlasOn(ctx, stack, backing, sizes.accelerationStructureSize(), true, debugLabel);
            return new PreparedBlas(accel, scratch, null, positions.deviceAddress, indices.deviceAddress, vertexCount - 1,
                    indexCount / 3, opaque, debugLabel, false, false);
        }
    }

    /**
     * Allocate a terrain section BLAS split into one geometry per material bucket (any-hit opt). {@code
     * bucketTris} holds the triangle count of each bucket in {@link #TERRAIN_BUCKETS} order: solid (flagged
     * {@code OPAQUE} so the driver never invokes {@code world.rahit} for it — the bulk of every scene),
     * cutout (alpha-tested foliage/glass), and water (shadow passthrough only). All geometries reference the
     * SAME packed vertex/index buffers, which the caller must concatenate in bucket order so each geometry's
     * triangles form a contiguous range. Build is deferred to {@link #recordBlasBuilds}; empty buckets are
     * omitted (their geometry isn't built), so an all-solid section is a single opaque geometry.
     */
    public static PreparedBlas prepareTerrainBlas(RtContext ctx, RtBuffer positions, int vertexCount,
                                                  RtBuffer indices, int[] bucketTris, OpacityMicromapInput opacityMicromapInput,
                                                  String label) {
        VkDevice vk = ctx.vk();
        String debugLabel = labelOr(label, "terrain BLAS");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            OpacityMicromap opacityMicromap = prepareOpacityMicromap(ctx, opacityMicromapInput, debugLabel);
            VkAccelerationStructureBuildSizesInfoKHR sizes = queryTerrainBlasSizes(vk, stack, positions, indices,
                    vertexCount, bucketTris, opacityMicromap);
            RtBuffer backing = ctx.createBuffer(sizes.accelerationStructureSize(), VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false,
                    debugLabel + " backing");
            RtBuffer scratch = ctx.createBuffer(sizes.buildScratchSize(), VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false,
                    debugLabel + " build scratch");
            RtAccel accel = createBlasOn(ctx, stack, backing, sizes.accelerationStructureSize(), true, debugLabel, opacityMicromap);
            return PreparedBlas.terrain(accel, scratch, positions.deviceAddress, indices.deviceAddress, vertexCount - 1,
                    bucketTris, opacityMicromap, debugLabel);
        }
    }

    private static OpacityMicromap prepareOpacityMicromap(RtContext ctx, OpacityMicromapInput input, String blasLabel) {
        if (input == null || input.triangleCount() <= 0) {
            return null;
        }
        VkDevice vk = ctx.vk();
        String label = blasLabel + " opacity micromap";
        int inputUsage = VK_BUFFER_USAGE_MICROMAP_BUILD_INPUT_READ_ONLY_BIT_EXT;
        RtBuffer data = ctx.createBuffer(input.data().length + MICROMAP_INPUT_ADDRESS_ALIGNMENT - 1, inputUsage, true, label + " data");
        long dataOffset = alignUp(data.deviceAddress, MICROMAP_INPUT_ADDRESS_ALIGNMENT) - data.deviceAddress;
        long dataAddress = data.deviceAddress + dataOffset;
        MemoryUtil.memByteBuffer(data.mapped + dataOffset, input.data().length).put(input.data());
        long triangleBytes = (long) VkMicromapTriangleEXT.SIZEOF * input.triangleCount();
        RtBuffer triangles = ctx.createBuffer(triangleBytes + MICROMAP_INPUT_ADDRESS_ALIGNMENT - 1, inputUsage, true,
                label + " triangles");
        long triangleOffset = alignUp(triangles.deviceAddress, MICROMAP_INPUT_ADDRESS_ALIGNMENT) - triangles.deviceAddress;
        long triangleArrayAddress = triangles.deviceAddress + triangleOffset;
        VkMicromapTriangleEXT.Buffer triangleArray = VkMicromapTriangleEXT.create(triangles.mapped + triangleOffset, input.triangleCount());
        for (int t = 0; t < input.triangleCount(); t++) {
            triangleArray.get(t)
                    .dataOffset(t * input.bytesPerTriangle())
                    .subdivisionLevel((short) input.subdivisionLevel())
                    .format((short) VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMicromapUsageEXT.Buffer usage = micromapUsage(stack, input.triangleCount(), input.subdivisionLevel());
            VkMicromapBuildInfoEXT build = micromapBuildInfo(stack, dataAddress, 0L, triangleArrayAddress, 0L, usage);
            VkMicromapBuildSizesInfoEXT sizes = VkMicromapBuildSizesInfoEXT.calloc(stack).sType$Default();
            vkGetMicromapBuildSizesEXT(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, build, sizes);

            RtBuffer backing = ctx.createBuffer(sizes.micromapSize(), VK_BUFFER_USAGE_MICROMAP_STORAGE_BIT_EXT, false,
                    label + " backing");
            VkMicromapCreateInfoEXT ci = VkMicromapCreateInfoEXT.calloc(stack).sType$Default()
                    .buffer(backing.handle).offset(0).size(sizes.micromapSize()).type(VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT);
            java.nio.LongBuffer pMicromap = stack.mallocLong(1);
            RtContext.check(vkCreateMicromapEXT(vk, ci, null, pMicromap), "vkCreateMicromapEXT");
            long handle = pMicromap.get(0);
            RtDebugLabels.nameMicromap(ctx, handle, label);

            RtBuffer scratch = ctx.createBuffer(sizes.buildScratchSize(), VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false,
                    label + " build scratch");
            return new OpacityMicromap(vk, handle, backing, data, triangles, scratch,
                    dataAddress, triangleArrayAddress, input.triangleCount(), input.subdivisionLevel(), input.bytesPerTriangle());
        }
    }

    /**
     * Pooled variant of {@link #prepareTrianglesBlas} for the per-frame entity path: the AS backing +
     * scratch buffers come from {@code pool} (recycled, not freshly allocated). The BLAS is reclaimed with
     * {@link #releaseBlasToPool} (NOT {@code freeBlasScratch} + {@code accel.destroy()}). Used only by
     * {@link RtEntities}; the terrain path keeps {@link #prepareTrianglesBlas}.
     */
    public static PreparedBlas prepareTrianglesBlasPooled(RtContext ctx, RtBufferPool pool, RtBuffer positions, int vertexCount,
                                                          RtBuffer indices, int indexCount, boolean opaque, String label) {
        VkDevice vk = ctx.vk();
        String debugLabel = labelOr(label, "pooled BLAS");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildSizesInfoKHR sizes = queryBlasSizes(vk, stack, positions, indices, vertexCount, indexCount, opaque, false);
            // acquire() returns capacity ≥ requested size; the AS is created with the exact queried size.
            RtBuffer backing = pool.acquire(ctx, sizes.accelerationStructureSize(), VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false,
                    debugLabel + " backing");
            RtBuffer scratch = pool.acquire(ctx, sizes.buildScratchSize(), VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false,
                    debugLabel + " build scratch");
            RtAccel accel = createBlasOn(ctx, stack, backing, sizes.accelerationStructureSize(), false, debugLabel);
            return new PreparedBlas(accel, scratch, backing, positions.deviceAddress, indices.deviceAddress, vertexCount - 1,
                    indexCount / 3, opaque, debugLabel, false, false);
        }
    }

    /**
     * Create a new <em>updatable</em> (ALLOW_UPDATE) BLAS sized for this mesh and a pool-backed backing
     * buffer, and prepare its initial full BUILD. The {@code accel} + {@code backing} persist in the
     * caller's per-entity ring (NOT released per frame); later frames refit it with {@link #refitUpdate}
     * (cheap in-place UPDATE) while the topology is stable, and free it with {@link #destroyPooledAccel}
     * on eviction / topology change.
     */
    public static UpdatableBuild prepareUpdatableBlasBuild(RtContext ctx, RtBufferPool pool, RtBuffer positions, int vertexCount,
                                                           RtBuffer indices, int indexCount, boolean opaque, String label) {
        VkDevice vk = ctx.vk();
        String debugLabel = labelOr(label, "updatable BLAS");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildSizesInfoKHR sizes = queryBlasSizes(vk, stack, positions, indices, vertexCount, indexCount, opaque, true);
            long accelSize = sizes.accelerationStructureSize();
            long updateScratch = sizes.updateScratchSize();
            RtBuffer backing = pool.acquire(ctx, accelSize, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false,
                    debugLabel + " backing");
            RtBuffer scratch = pool.acquire(ctx, sizes.buildScratchSize(), VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false,
                    debugLabel + " build scratch");
            RtAccel accel = createBlasOn(ctx, stack, backing, accelSize, false, debugLabel);
            PreparedBlas op = new PreparedBlas(accel, scratch, backing, positions.deviceAddress, indices.deviceAddress,
                    vertexCount - 1, indexCount / 3, opaque, debugLabel, true, false);
            return new UpdatableBuild(op, accel, backing, scratch, updateScratch);
        }
    }

    /**
     * Prepare an in-place refit (UPDATE) of an existing updatable BLAS with new vertex data of the SAME
     * topology. {@code scratch} (sized {@code updateScratchSize}) and the mesh buffers are caller-owned
     * per-frame transients; the {@code accel} persists. Records nothing on its own — returned to {@link
     * #recordBlasBuilds} like a BUILD.
     */
    public static PreparedBlas refitUpdate(RtAccel accel, RtBuffer scratch, long vertexAddr, long indexAddr,
                                           int vertexCount, int indexCount, boolean opaque, String label) {
        String debugLabel = labelOr(label, "BLAS refit");
        return new PreparedBlas(accel, scratch, null, vertexAddr, indexAddr, vertexCount - 1, indexCount / 3,
                opaque, debugLabel, true, true);
    }

    /** Reclaim a pooled BLAS: destroy its AS handle and return its backing + scratch buffers to the pool. */
    public static void releaseBlasToPool(RtBufferPool pool, PreparedBlas blas) {
        blas.accel.destroy(); // ownsBacking == false → destroys only the AS handle, not the backing buffer
        pool.release(blas.pooledBacking);
        pool.release(blas.scratch);
    }

    /** Destroy a pool-backed (updatable-entity) AS: destroy the handle, return its backing buffer to the pool. */
    public static void destroyPooledAccel(RtBufferPool pool, RtAccel accel, RtBuffer backing) {
        accel.destroy(); // ownsBacking == false → handle only
        pool.release(backing);
    }

    private static VkAccelerationStructureBuildSizesInfoKHR queryBlasSizes(VkDevice vk, MemoryStack stack, RtBuffer positions,
                                                                           RtBuffer indices, int vertexCount, int indexCount, boolean opaque, boolean allowUpdate) {
        VkAccelerationStructureGeometryKHR.Buffer geom = triangleGeometry(stack, positions.deviceAddress,
                indices.deviceAddress, vertexCount, opaque);
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        build.sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(buildFlags(allowUpdate))
                .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).geometryCount(1).pGeometries(geom);
        VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
        vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                build.get(0), stack.ints(indexCount / 3), sizes);
        return sizes;
    }

    private static int buildFlags(boolean allowUpdate) {
        // ALLOW_DATA_ACCESS lets the closest-hit read vertex positions from the BLAS via
        // gl_HitTriangleVertexPositionsEXT (VK_KHR_ray_tracing_position_fetch) for the normal-map TBN.
        // Applied to every BLAS (terrain/entity) AND the refit path, so the build/UPDATE flags stay
        // identical (a refit invariant) — this is the single shared flag source.
        return VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
                | VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_DATA_ACCESS_BIT_KHR
                | (allowUpdate ? VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR : 0);
    }

    private static RtAccel createBlasOn(RtContext ctx, MemoryStack stack, RtBuffer backing, long accelSize,
                                        boolean ownsBacking, String label) {
        return createBlasOn(ctx, stack, backing, accelSize, ownsBacking, label, null);
    }

    private static RtAccel createBlasOn(RtContext ctx, MemoryStack stack, RtBuffer backing, long accelSize,
                                        boolean ownsBacking, String label, OpacityMicromap opacityMicromap) {
        VkDevice vk = ctx.vk();
        VkAccelerationStructureCreateInfoKHR ci = VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                .buffer(backing.handle).offset(0).size(accelSize).type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
        java.nio.LongBuffer pAs = stack.mallocLong(1);
        RtContext.check(vkCreateAccelerationStructureKHR(vk, ci, null, pAs), "vkCreateAccelerationStructureKHR");
        long handle = pAs.get(0);
        RtDebugLabels.nameAccelerationStructure(ctx, handle, label);
        VkAccelerationStructureDeviceAddressInfoKHR addrInfo = VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                .sType$Default().accelerationStructure(handle);
        long deviceAddress = vkGetAccelerationStructureDeviceAddressKHR(vk, addrInfo);
        return new RtAccel(vk, handle, deviceAddress, backing, ownsBacking, opacityMicromap);
    }

    private static VkAccelerationStructureGeometryKHR.Buffer triangleGeometry(MemoryStack stack, long vertexAddr, long indexAddr, int vertexCount, boolean opaque) {
        VkAccelerationStructureGeometryKHR.Buffer geom = VkAccelerationStructureGeometryKHR.calloc(1, stack);
        fillTriangleGeometry(geom.get(0), vertexAddr, indexAddr, vertexCount, opaque);
        return geom;
    }

    private static void fillTriangleGeometry(VkAccelerationStructureGeometryKHR geom, long vertexAddr, long indexAddr, int vertexCount, boolean opaque) {
        geom.sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                .flags(opaque ? VK_GEOMETRY_OPAQUE_BIT_KHR : VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR);
        var tri = geom.geometry().triangles();
        tri.sType$Default()
                .vertexFormat(VK10.VK_FORMAT_R32G32B32_SFLOAT).vertexStride(3L * Float.BYTES)
                .maxVertex(vertexCount - 1).indexType(VK10.VK_INDEX_TYPE_UINT32);
        tri.vertexData().deviceAddress(vertexAddr);
        tri.indexData().deviceAddress(indexAddr);
    }

    private static VkMicromapUsageEXT.Buffer micromapUsage(MemoryStack stack, int triangleCount, int subdivisionLevel) {
        VkMicromapUsageEXT.Buffer usage = VkMicromapUsageEXT.calloc(1, stack);
        usage.get(0).count(triangleCount)
                .subdivisionLevel(subdivisionLevel)
                .format(VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT);
        return usage;
    }

    private static VkMicromapBuildInfoEXT micromapBuildInfo(MemoryStack stack, long dataAddr, long scratchAddr,
                                                            long triangleArrayAddr, long dstMicromap,
                                                            VkMicromapUsageEXT.Buffer usage) {
        VkMicromapBuildInfoEXT build = VkMicromapBuildInfoEXT.calloc(stack).sType$Default()
                .type(VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT)
                .flags(VK_BUILD_MICROMAP_PREFER_FAST_TRACE_BIT_EXT)
                .mode(VK_BUILD_MICROMAP_MODE_BUILD_EXT)
                .dstMicromap(dstMicromap)
                .usageCountsCount(usage.capacity())
                .pUsageCounts(usage)
                .triangleArrayStride(VkMicromapTriangleEXT.SIZEOF);
        build.data().deviceAddress(dataAddr);
        build.scratchData().deviceAddress(scratchAddr);
        build.triangleArray().deviceAddress(triangleArrayAddr);
        return build;
    }

    /** One triangle geometry per non-empty bucket, in {@link #TERRAIN_BUCKETS} order; only bucket
     *  {@link #BUCKET_SOLID} is flagged opaque. All geometries reference the same vertex/index buffers — each
     *  bucket's triangle range is selected by the build range's {@code primitiveOffset} (see
     *  {@link #terrainBuildRanges}). */
    private static VkAccelerationStructureGeometryKHR.Buffer terrainGeometries(MemoryStack stack, long vertexAddr,
                                                                               long indexAddr, int vertexCount, int[] bucketTris,
                                                                               OpacityMicromap opacityMicromap) {
        VkAccelerationStructureGeometryKHR.Buffer geom = VkAccelerationStructureGeometryKHR.calloc(terrainGeomCount(bucketTris), stack);
        VkAccelerationStructureTrianglesOpacityMicromapEXT ommAttachment = null;
        if (opacityMicromap != null && bucketTris[BUCKET_CUTOUT] > 0) {
            VkMicromapUsageEXT.Buffer usage = micromapUsage(stack, opacityMicromap.triangleCount, opacityMicromap.subdivisionLevel);
            ommAttachment = VkAccelerationStructureTrianglesOpacityMicromapEXT.calloc(stack).sType$Default()
                    .indexType(VK_INDEX_TYPE_NONE_KHR)
                    .indexStride(0L)
                    .baseTriangle(0)
                    .usageCountsCount(usage.capacity())
                    .pUsageCounts(usage)
                    .micromap(opacityMicromap.handle);
            ommAttachment.indexBuffer().deviceAddress(0L);
        }
        int g = 0;
        for (int b = 0; b < bucketTris.length; b++) {
            if (bucketTris[b] > 0) {
                VkAccelerationStructureGeometryKHR out = geom.get(g++);
                fillTriangleGeometry(out, vertexAddr, indexAddr, vertexCount, b == BUCKET_SOLID);
                if (b == BUCKET_CUTOUT && ommAttachment != null) {
                    out.geometry().triangles().pNext(ommAttachment.address());
                }
            }
        }
        return geom;
    }

    /** Build ranges parallel to {@link #terrainGeometries}: each non-empty bucket's triangles begin right
     *  after the preceding buckets in the shared index buffer (the section's buffers are packed in bucket
     *  order), so the offset is the running triangle count times the 3-index stride. */
    private static VkAccelerationStructureBuildRangeInfoKHR.Buffer terrainBuildRanges(MemoryStack stack, int[] bucketTris) {
        VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(terrainGeomCount(bucketTris), stack);
        int g = 0, acc = 0;
        for (int tris : bucketTris) {
            if (tris > 0) {
                range.get(g++).primitiveCount(tris).primitiveOffset(acc * 3 * Integer.BYTES).firstVertex(0).transformOffset(0);
                acc += tris;
            }
        }
        return range;
    }

    private static int terrainGeomCount(int[] bucketTris) {
        int n = 0;
        for (int t : bucketTris) {
            if (t > 0) {
                n++;
            }
        }
        return n;
    }

    private static VkAccelerationStructureBuildSizesInfoKHR queryTerrainBlasSizes(VkDevice vk, MemoryStack stack, RtBuffer positions,
                                                                                  RtBuffer indices, int vertexCount, int[] bucketTris,
                                                                                  OpacityMicromap opacityMicromap) {
        VkAccelerationStructureGeometryKHR.Buffer geom = terrainGeometries(stack, positions.deviceAddress, indices.deviceAddress,
                vertexCount, bucketTris, opacityMicromap);
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        build.sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(buildFlags(false))
                .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).geometryCount(geom.capacity()).pGeometries(geom);
        java.nio.IntBuffer maxPrims = stack.mallocInt(geom.capacity());
        for (int tris : bucketTris) {
            if (tris > 0) {
                maxPrims.put(tris);
            }
        }
        maxPrims.flip();
        VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
        vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                build.get(0), maxPrims, sizes);
        return sizes;
    }

    /**
     * A TLAS instance: a 3x4 row-major transform, the device address of its BLAS, the 24-bit
     * {@code instanceCustomIndex} the hit shaders read, and the 8-bit visibility {@code mask} (ANDed with
     * the trace cull mask). Terrain passes its section-table index; dynamic entities set the high
     * {@code ENTITY_BIT} flag so the hit shader takes the entity path. Mask defaults to 0xFF (visible to
     * every ray); particles override it (0x02) so they are seen only by the primary ray (camera-only).
     */
    public record Instance(float[] transform3x4, long blasDeviceAddress, int customIndex, int mask) {
        public Instance(float[] transform3x4, long blasDeviceAddress, int customIndex) {
            this(transform3x4, blasDeviceAddress, customIndex, 0xFF);
        }
    }

    /** A TLAS whose AS + backing + instance buffer are allocated but whose build is recorded later. */
    public static final class PreparedTlas {
        public final RtAccel accel;
        private final RtBuffer instanceBuffer;
        private final RtBuffer scratch;
        private final int instanceCount;
        private final String label;

        private PreparedTlas(RtAccel accel, RtBuffer instanceBuffer, RtBuffer scratch, int instanceCount, String label) {
            this.accel = accel;
            this.instanceBuffer = instanceBuffer;
            this.scratch = scratch;
            this.instanceCount = instanceCount;
            this.label = label;
        }

        /**
         * Free the TLAS, its instance buffer, and its scratch. For a per-frame TLAS the whole bundle
         * is retired together once the frame that traced it is no longer in flight (the instance +
         * scratch buffers are still read by the recorded build, and the AS by the recorded trace).
         */
        public void destroyAll() {
            accel.destroy();
            instanceBuffer.destroy();
            scratch.destroy();
        }
    }

    /** Allocate a TLAS (AS + backing + filled instance buffer + scratch), deferring the build. */
    public static PreparedTlas prepareTlas(RtContext ctx, List<Instance> instances) {
        VkDevice vk = ctx.vk();
        int count = instances.size();
        String label = "frame TLAS " + count + " instances";
        RtBuffer instanceBuffer = ctx.createBuffer((long) VkAccelerationStructureInstanceKHR.SIZEOF * count,
                org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR, true,
                label + " instance buffer");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Reuse a single record + transform buffer across all instances: allocating per-instance
            // on the MemoryStack (64 KB/thread) overflows it once there are hundreds of sections.
            VkAccelerationStructureInstanceKHR rec = VkAccelerationStructureInstanceKHR.calloc(stack);
            java.nio.FloatBuffer xform = stack.mallocFloat(12);
            for (int i = 0; i < count; i++) {
                Instance inst = instances.get(i);
                xform.clear();
                xform.put(inst.transform3x4()).flip();
                rec.transform().matrix(xform);
                rec.instanceCustomIndex(inst.customIndex()).mask(inst.mask()).instanceShaderBindingTableRecordOffset(0)
                        .flags(0x00000001) // VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR
                        .accelerationStructureReference(inst.blasDeviceAddress());
                MemoryUtil.memCopy(rec.address(), instanceBuffer.mapped + (long) i * VkAccelerationStructureInstanceKHR.SIZEOF,
                        VkAccelerationStructureInstanceKHR.SIZEOF);
            }

            VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = tlasBuildInfo(stack, instanceBuffer.deviceAddress);
            VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    build.get(0), stack.ints(count), sizes);

            RtBuffer backing = ctx.createBuffer(sizes.accelerationStructureSize(), VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false,
                    label + " backing");
            VkAccelerationStructureCreateInfoKHR ci = VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                    .buffer(backing.handle).offset(0).size(sizes.accelerationStructureSize()).type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
            java.nio.LongBuffer pAs = stack.mallocLong(1);
            RtContext.check(vkCreateAccelerationStructureKHR(vk, ci, null, pAs), "vkCreateAccelerationStructureKHR");
            long handle = pAs.get(0);
            RtDebugLabels.nameAccelerationStructure(ctx, handle, label);
            RtBuffer scratch = ctx.createBuffer(sizes.buildScratchSize(), VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false,
                    label + " build scratch");
            VkAccelerationStructureDeviceAddressInfoKHR addrInfo = VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                    .sType$Default().accelerationStructure(handle);
            long deviceAddress = vkGetAccelerationStructureDeviceAddressKHR(vk, addrInfo);
            return new PreparedTlas(new RtAccel(vk, handle, deviceAddress, backing), instanceBuffer, scratch, count, label);
        }
    }

    private static VkAccelerationStructureBuildGeometryInfoKHR.Buffer tlasBuildInfo(MemoryStack stack, long instanceBufferAddr) {
        VkAccelerationStructureGeometryKHR.Buffer geom = VkAccelerationStructureGeometryKHR.calloc(1, stack);
        geom.sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR).flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
        geom.geometry().instances().sType$Default().arrayOfPointers(false);
        geom.geometry().instances().data().deviceAddress(instanceBufferAddr);
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        build.sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).geometryCount(1).pGeometries(geom);
        return build;
    }

    private static void recordBlasBuilds(VkCommandBuffer cmd, List<PreparedBlas> blas) {
        for (PreparedBlas b : blas) {
            try (MemoryStack stack = MemoryStack.stackPush()) { // per-iteration: avoid 64 KB stack overflow
                recordBlasBuild(cmd, stack, b);
            }
        }
    }

    /** Record labelled BLAS builds into the command buffer. */
    public static void recordBlasBuilds(RtContext ctx, VkCommandBuffer cmd, List<PreparedBlas> blas) {
        String label = blas.size() == 1 ? blas.get(0).label + (blas.get(0).update ? " refit" : " build")
                : "BLAS builds " + blas.size();
        try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, label)) {
            recordBlasBuilds(cmd, blas);
        }
    }

    /** Free the transient scratch buffers of a set of prepared BLAS (only after their build completed). */
    public static void freeBlasScratch(List<PreparedBlas> blas) {
        for (PreparedBlas b : blas) {
            b.freeTransientBuildResources();
        }
    }

    private static void recordTlasBuild(VkCommandBuffer cmd, PreparedTlas tlas) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = tlasBuildInfo(stack, tlas.instanceBuffer.deviceAddress);
            build.get(0).dstAccelerationStructure(tlas.accel.handle);
            build.get(0).scratchData().deviceAddress(tlas.scratch.deviceAddress);
            VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            range.get(0).primitiveCount(tlas.instanceCount).primitiveOffset(0).firstVertex(0).transformOffset(0);
            PointerBuffer ppRange = stack.mallocPointer(1).put(0, range.address());
            vkCmdBuildAccelerationStructuresKHR(cmd, build, ppRange);
        }
    }

    /** Record a labelled TLAS build into the command buffer. */
    public static void recordTlasBuild(RtContext ctx, VkCommandBuffer cmd, PreparedTlas tlas) {
        try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, tlas.label + " build")) {
            recordTlasBuild(cmd, tlas);
        }
    }

    private static void recordBlasBuild(VkCommandBuffer cmd, MemoryStack stack, PreparedBlas b) {
        if (b.terrainSplit) {
            recordTerrainBlasBuild(cmd, stack, b);
            return;
        }
        VkAccelerationStructureGeometryKHR.Buffer geom = triangleGeometry(stack, b.vertexAddr, b.indexAddr, b.maxVertex + 1, b.opaque);
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        build.sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(buildFlags(b.updatable))
                .mode(b.update ? VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR : VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .geometryCount(1).pGeometries(geom)
                .dstAccelerationStructure(b.accel.handle);
        if (b.update) {
            // In-place refit: the existing (off-queue) AS is both source and destination. The flags +
            // topology (primitiveCount/maxVertex) must match its original ALLOW_UPDATE build.
            build.get(0).srcAccelerationStructure(b.accel.handle);
        }
        build.get(0).scratchData().deviceAddress(b.scratch.deviceAddress);
        VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
        range.get(0).primitiveCount(b.triangleCount).primitiveOffset(0).firstVertex(0).transformOffset(0);
        PointerBuffer ppRange = stack.mallocPointer(1).put(0, range.address());
        vkCmdBuildAccelerationStructuresKHR(cmd, build, ppRange);
    }

    /** Record a terrain section's two-geometry (opaque + alpha) BUILD. Always a fresh BUILD — terrain
     *  sections are never refit in place (re-extraction allocates a new BLAS), so no UPDATE branch. */
    private static void recordTerrainBlasBuild(VkCommandBuffer cmd, MemoryStack stack, PreparedBlas b) {
        if (b.opacityMicromap != null) {
            recordMicromapBuild(cmd, stack, b.opacityMicromap);
            micromapBuildBarrier(cmd, stack);
        }
        VkAccelerationStructureGeometryKHR.Buffer geom = terrainGeometries(stack, b.vertexAddr, b.indexAddr,
                b.maxVertex + 1, b.terrainTris, b.opacityMicromap);
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer build = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        build.sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(buildFlags(false))
                .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .geometryCount(geom.capacity()).pGeometries(geom)
                .dstAccelerationStructure(b.accel.handle);
        build.get(0).scratchData().deviceAddress(b.scratch.deviceAddress);
        VkAccelerationStructureBuildRangeInfoKHR.Buffer range = terrainBuildRanges(stack, b.terrainTris);
        PointerBuffer ppRange = stack.mallocPointer(1).put(0, range.address());
        vkCmdBuildAccelerationStructuresKHR(cmd, build, ppRange);
    }

    private static void recordMicromapBuild(VkCommandBuffer cmd, MemoryStack stack, OpacityMicromap opacityMicromap) {
        VkMicromapUsageEXT.Buffer usage = micromapUsage(stack, opacityMicromap.triangleCount, opacityMicromap.subdivisionLevel);
        VkMicromapBuildInfoEXT.Buffer build = VkMicromapBuildInfoEXT.calloc(1, stack);
        build.get(0).set(micromapBuildInfo(stack, opacityMicromap.dataAddress, opacityMicromap.scratch.deviceAddress,
                opacityMicromap.triangleArrayAddress, opacityMicromap.handle, usage));
        vkCmdBuildMicromapsEXT(cmd, build);
    }

    private static void micromapBuildBarrier(VkCommandBuffer cmd, MemoryStack stack) {
        VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack);
        barrier.get(0).sType$Default()
                .srcStageMask(VK_PIPELINE_STAGE_2_MICROMAP_BUILD_BIT_EXT)
                .srcAccessMask(VK_ACCESS_2_MICROMAP_WRITE_BIT_EXT)
                .dstStageMask(VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR)
                .dstAccessMask(VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR);
        VkDependencyInfo dep = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier);
        vkCmdPipelineBarrier2KHR(cmd, dep);
    }

    private static long alignUp(long value, long alignment) {
        return (value + alignment - 1) & -alignment;
    }

    private static String labelOr(String label, String fallback) {
        return label == null || label.isBlank() ? fallback : label;
    }
}
