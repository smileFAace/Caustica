package dev.upscaler.rt;

import com.mojang.blaze3d.vertex.QuadInstance;
import dev.upscaler.UpscalerMod;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P2 step 1: per-section terrain residency. Block-model geometry around the player is tessellated
 * through vanilla's {@link ModelBlockRenderer} (correct shapes + neighbour-culled faces, biome tint,
 * alpha-cutout via the any-hit), but instead of one giant snapshot BLAS the geometry is grouped by
 * 16³ chunk section: each non-empty section gets its own buffers + BLAS, and a single TLAS holds one
 * instance per section.
 *
 * <p>Vertices are stored <b>section-local</b> (small coords → f32-exact). Each TLAS instance carries
 * a translation {@code sectionOrigin − rebaseOrigin} and an {@code instanceCustomIndex} into a BDA
 * <b>section table</b> ({@code {primAddr, idxAddr, uvAddr}} per section); the closest-hit/any-hit read
 * {@code gl_InstanceCustomIndexEXT} to find the hit section's geometry buffers. This is the structure
 * the lifecycle (load/unload/edit) and per-frame camera-relative TLAS rebuilds build on; the visible
 * result is identical to the single-BLAS snapshot.
 */
public final class RtTerrain {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("upscaler.rt.terrain", "true"));
    private static final int RADIUS = Integer.getInteger("upscaler.rt.terrainRadius", 16);
    // Re-extract once the player drifts this many blocks from the last snapshot center. Keep it well
    // below RADIUS so the player never reaches the snapshot edge before it refreshes.
    private static final int REEXTRACT_THRESHOLD = Integer.getInteger("upscaler.rt.reextract", 8);

    private static RtTerrain instance;

    private final List<RtBuffer> buffers;   // all per-section geometry buffers (positions/indices/uvs/material)
    private final List<RtAccel> blasList;   // one BLAS per section
    private final RtBuffer sectionTable;    // BDA array of {u64 primAddr, u64 idxAddr, u64 uvAddr} per section
    private final RtAccel tlas;
    public final int blockX;                // rebase origin (player block) for the instance transforms
    public final int blockY;
    public final int blockZ;

    private RtTerrain(List<RtBuffer> buffers, List<RtAccel> blasList, RtBuffer sectionTable, RtAccel tlas, int bx, int by, int bz) {
        this.buffers = buffers;
        this.blasList = blasList;
        this.sectionTable = sectionTable;
        this.tlas = tlas;
        this.blockX = bx;
        this.blockY = by;
        this.blockZ = bz;
    }

    public static RtTerrain currentOrNull() {
        return instance;
    }

    public long tlas() {
        return tlas.handle;
    }

    /** Device address of the section table: {@code {u64 primAddr, u64 idxAddr, u64 uvAddr}} per section, indexed by gl_InstanceCustomIndexEXT. */
    public long tableAddress() {
        return sectionTable.deviceAddress;
    }

    /**
     * (Re)extract the snapshot if none exists yet, or once the player has drifted past
     * {@link #REEXTRACT_THRESHOLD} blocks from the current snapshot center — a sliding snapshot that
     * follows the player. Each re-extraction re-rebases to the new player block, so vertices and
     * instance transforms stay small (f32-exact) at any absolute world coordinate. Called per tick;
     * self-throttles via the distance gate. (Crude full rebuild for now — incremental per-section
     * load/unload + a per-frame TLAS is the next refinement.)
     */
    public static boolean maybeExtract(RtContext ctx) {
        if (!ENABLED) {
            return false;
        }
        RtTerrain cur = instance;
        if (cur != null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) {
                return false;
            }
            BlockPos p = mc.player.blockPosition();
            int drift = Math.max(Math.abs(p.getX() - cur.blockX),
                    Math.max(Math.abs(p.getY() - cur.blockY), Math.abs(p.getZ() - cur.blockZ)));
            if (drift < REEXTRACT_THRESHOLD) {
                return false; // still near the snapshot center; keep the current geometry
            }
        }
        return extractAroundPlayer(ctx);
    }

    public static boolean extractAroundPlayer(RtContext ctx) {
        if (!ENABLED) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return false; // not in a world yet
        }
        BlockPos center = mc.player.blockPosition();
        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();

        BlockStateModelSet modelSet = mc.getModelManager().getBlockStateModelSet();
        ModelBlockRenderer renderer = new ModelBlockRenderer(false, true, mc.getBlockColors());
        BlockAndTintGetter view = new LevelView(level);

        QuadCapture capture = new QuadCapture();
        capture.blockColors = mc.getBlockColors();
        capture.view = view;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        // Sections are accumulated lazily as blocks are visited; LinkedHashMap keeps a stable order
        // so the table index == TLAS instance index == gl_InstanceCustomIndexEXT.
        Map<Long, Section> sectionMap = new LinkedHashMap<>();
        int blocks = 0;

        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dy = -RADIUS; dy <= RADIUS; dy++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    int wx = cx + dx, wy = cy + dy, wz = cz + dz;
                    m.set(wx, wy, wz);
                    BlockState state = level.getBlockState(m);
                    if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) {
                        continue;
                    }
                    BlockStateModel model = modelSet.get(state);
                    if (model == null) {
                        continue;
                    }
                    int sox = (wx >> 4) << 4, soy = (wy >> 4) << 4, soz = (wz >> 4) << 4;
                    Section section = sectionMap.computeIfAbsent(sectionKey(wx, wy, wz), k -> new Section(sox, soy, soz));
                    try {
                        capture.cur = section;
                        capture.state = state;
                        capture.pos = m;
                        // Section-local block offset (0..15) so emitted vertices are section-local.
                        renderer.tesselateBlock(capture, wx - sox, wy - soy, wz - soz, view, m, state, model, state.getSeed(m));
                        blocks++;
                    } catch (Throwable t) {
                        // skip a block whose model rendering throws rather than failing the whole snapshot
                    }
                }
            }
        }

        List<Section> sections = new ArrayList<>();
        for (Section s : sectionMap.values()) {
            if (!s.idx.isEmpty()) {
                sections.add(s);
            }
        }
        if (sections.isEmpty()) {
            UpscalerMod.LOGGER.info("RT terrain: no model geometry around ({},{},{}) — skipping", cx, cy, cz);
            return false;
        }

        int asInput = org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
        int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        List<RtBuffer> buffers = new ArrayList<>();
        List<RtAccel> blasList = new ArrayList<>();
        List<RtAccel.Instance> instances = new ArrayList<>();
        long[] primAddr = new long[sections.size()];
        long[] idxAddr = new long[sections.size()];
        long[] uvAddr = new long[sections.size()];
        int totalTris = 0;
        int totalVerts = 0;

        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            int vertCount = s.verts.size() / 3;
            int idxCount = s.idx.size();
            RtBuffer positions = ctx.createBuffer((long) s.verts.size() * Float.BYTES, asInput, true);
            RtBuffer indices = ctx.createBuffer((long) s.idx.size() * Integer.BYTES, asInput | storage, true);
            RtBuffer uvs = ctx.createBuffer((long) s.uvList.size() * Float.BYTES, storage, true);
            RtBuffer material = ctx.createBuffer((long) s.prim.size() * Float.BYTES, storage, true);
            MemoryUtil.memFloatBuffer(positions.mapped, s.verts.size()).put(s.verts.elements(), 0, s.verts.size());
            MemoryUtil.memIntBuffer(indices.mapped, s.idx.size()).put(s.idx.elements(), 0, s.idx.size());
            MemoryUtil.memFloatBuffer(uvs.mapped, s.uvList.size()).put(s.uvList.elements(), 0, s.uvList.size());
            MemoryUtil.memFloatBuffer(material.mapped, s.prim.size()).put(s.prim.elements(), 0, s.prim.size());

            // Cutout geometry: non-opaque so the any-hit shader alpha-tests the atlas (foliage/glass).
            RtAccel blas = RtAccel.buildTrianglesBlas(ctx, positions, vertCount, indices, idxCount, false);
            buffers.add(positions);
            buffers.add(indices);
            buffers.add(uvs);
            buffers.add(material);
            blasList.add(blas);
            primAddr[i] = material.deviceAddress;
            idxAddr[i] = indices.deviceAddress;
            uvAddr[i] = uvs.deviceAddress;

            // Pure-translation instance transform: sectionOrigin − rebaseOrigin (player block). Combined
            // with section-local BLAS vertices this puts geometry at world − rebaseOrigin, matching the
            // ray origin's camOffset. A future step rebuilds this per frame relative to the camera.
            float[] xform = {1, 0, 0, s.sx - cx, 0, 1, 0, s.sy - cy, 0, 0, 1, s.sz - cz};
            instances.add(new RtAccel.Instance(xform, blas.deviceAddress));
            totalTris += idxCount / 3;
            totalVerts += vertCount;
        }

        RtBuffer sectionTable = ctx.createBuffer((long) sections.size() * SECTION_ENTRY_BYTES, storage, true);
        for (int i = 0; i < sections.size(); i++) {
            long base = sectionTable.mapped + (long) i * SECTION_ENTRY_BYTES;
            MemoryUtil.memPutLong(base, primAddr[i]);
            MemoryUtil.memPutLong(base + 8, idxAddr[i]);
            MemoryUtil.memPutLong(base + 16, uvAddr[i]);
        }

        RtAccel tlas = RtAccel.buildTlas(ctx, instances);

        if (instance != null) {
            ctx.waitIdle(); // a previous frame may still read the old AS/buffers before we free them
            instance.destroy();
        }
        instance = new RtTerrain(buffers, blasList, sectionTable, tlas, cx, cy, cz);
        UpscalerMod.LOGGER.info("RT terrain: {} blocks -> {} triangles ({} verts) in {} sections around ({},{},{}); per-section BLAS + TLAS built",
                blocks, totalTris, totalVerts, sections.size(), cx, cy, cz);
        return true;
    }

    public void destroy() {
        tlas.destroy();
        for (RtAccel blas : blasList) {
            blas.destroy();
        }
        sectionTable.destroy();
        for (RtBuffer b : buffers) {
            b.destroy();
        }
        if (instance == this) {
            instance = null;
        }
    }

    /** 24-byte section table entry: {u64 primAddr, u64 idxAddr, u64 uvAddr} (std430 stride). */
    private static final int SECTION_ENTRY_BYTES = 24;

    /** Pack section coords (block >> 4) into a stable map key; ranges fit comfortably in the masks. */
    private static long sectionKey(int wx, int wy, int wz) {
        long scx = wx >> 4, scy = wy >> 4, scz = wz >> 4;
        return (scx & 0x3FFFFFFL) | ((scz & 0x3FFFFFFL) << 26) | ((scy & 0xFFFL) << 52);
    }

    /** Accumulates one section's quads (section-local) into RT position/index/uv/material streams. */
    private static final class Section {
        final int sx;
        final int sy;
        final int sz; // section origin in world blocks
        final FloatArrayList verts = new FloatArrayList();
        final IntArrayList idx = new IntArrayList();
        final FloatArrayList uvList = new FloatArrayList(); // 2 floats/vertex: atlas UV
        final FloatArrayList prim = new FloatArrayList();   // 8 floats/triangle: normal.xyz0, tint.rgb0

        Section(int sx, int sy, int sz) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
        }
    }

    /** Captures the quads vanilla's model renderer emits into the current section's streams. */
    private static final class QuadCapture implements BlockQuadOutput {
        Section cur; // set before each tesselateBlock call

        // Per-block context for biome tint, set before each tesselateBlock call. We resolve the tint
        // straight from BlockColors (pure biome color) rather than QuadInstance.getColor, which bakes
        // in vanilla AO + directional shading we don't want — our tint must be unlit albedo.
        BlockColors blockColors;
        BlockAndTintGetter view;
        BlockState state;
        BlockPos pos;

        @Override
        public void put(float x, float y, float z, BakedQuad quad, QuadInstance instance) {
            FloatArrayList verts = cur.verts;
            IntArrayList idx = cur.idx;
            int base = verts.size() / 3;
            Vector3fc p0 = quad.position(0);
            Vector3fc p1 = quad.position(1);
            Vector3fc p2 = quad.position(2);
            Vector3fc p3 = quad.position(3);
            addVertex(p0, x, y, z);
            addVertex(p1, x, y, z);
            addVertex(p2, x, y, z);
            addVertex(p3, x, y, z);
            addUv(quad.packedUV(0));
            addUv(quad.packedUV(1));
            addUv(quad.packedUV(2));
            addUv(quad.packedUV(3));
            idx.add(base);
            idx.add(base + 1);
            idx.add(base + 2);
            idx.add(base);
            idx.add(base + 2);
            idx.add(base + 3);

            float ex1 = p1.x() - p0.x(), ey1 = p1.y() - p0.y(), ez1 = p1.z() - p0.z();
            float ex2 = p2.x() - p0.x(), ey2 = p2.y() - p0.y(), ez2 = p2.z() - p0.z();
            float nx = ey1 * ez2 - ez1 * ey2;
            float ny = ez1 * ex2 - ex1 * ez2;
            float nz = ex1 * ey2 - ey1 * ex2;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1.0e-6f) {
                nx /= len;
                ny /= len;
                nz /= len;
            }
            // Biome tint: tintIndex >= 0 means the quad is biome-colored (grass/foliage/etc.). In 26.2
            // the color comes from a BlockTintSource; colorInWorld blends the biome color at this pos
            // (0x00RRGGBB). Untinted quads (tintIndex < 0) stay white.
            int tintIndex = quad.materialInfo().tintIndex();
            float tr = 1f, tg = 1f, tb = 1f;
            if (tintIndex >= 0 && blockColors != null && state != null) {
                BlockTintSource src = blockColors.getTintSource(state, tintIndex);
                if (src != null) {
                    int rgb = src.colorInWorld(state, view, pos);
                    tr = ((rgb >> 16) & 0xFF) * (1f / 255f);
                    tg = ((rgb >> 8) & 0xFF) * (1f / 255f);
                    tb = (rgb & 0xFF) * (1f / 255f);
                }
            }

            FloatArrayList prim = cur.prim;
            for (int t = 0; t < 2; t++) { // one {normal, tint} record per triangle
                prim.add(nx);
                prim.add(ny);
                prim.add(nz);
                prim.add(0f);
                prim.add(tr);
                prim.add(tg);
                prim.add(tb);
                prim.add(0f);
            }
        }

        private void addVertex(Vector3fc p, float x, float y, float z) {
            cur.verts.add(p.x() + x);
            cur.verts.add(p.y() + y);
            cur.verts.add(p.z() + z);
        }

        private void addUv(long packedUV) {
            // UVPair packs u in the high 32 bits, v in the low 32 (atlas-space, no sprite remap needed).
            cur.uvList.add(Float.intBitsToFloat((int) (packedUV >>> 32)));
            cur.uvList.add(Float.intBitsToFloat((int) packedUV));
        }
    }

    /** Minimal {@link BlockAndTintGetter} over the client level so the model renderer can cull + tint. */
    private record LevelView(ClientLevel level) implements BlockAndTintGetter {
        @Override
        public CardinalLighting cardinalLighting() {
            return CardinalLighting.DEFAULT;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver color) {
            return level.getBlockTint(pos, color);
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return level.getLightEngine();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return level.getBlockEntity(pos);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return level.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return level.getFluidState(pos);
        }

        @Override
        public int getHeight() {
            return level.getHeight();
        }

        @Override
        public int getMinY() {
            return level.getMinY();
        }
    }
}
