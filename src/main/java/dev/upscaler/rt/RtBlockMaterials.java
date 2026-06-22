package dev.upscaler.rt;

import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * P6.2 LabPBR material ingestion for terrain: the {@code _s}/{@code _n} parallel atlases mirroring the
 * <b>block atlas</b>. A thin wrapper over the generalized {@link RtParallelAtlas} (which also backs the
 * block-entity atlases in {@link RtEntityMaterials}); the block atlas is pre-enumerated by {@link
 * #prepareAll} so terrain extraction's {@link #ensure} is a pure cache lookup.
 *
 * <p>The closest-hit samples the atlases at the same UV as albedo via fixed bindings (terrain prims flag
 * presence in {@code mat.z}/{@code mat.w}); block-like entities sampling the block atlas reuse these same
 * atlases (mat code 2). See {@link RtParallelAtlas} for the build/blit mechanics.
 */
public final class RtBlockMaterials {
    public static final RtBlockMaterials INSTANCE = new RtBlockMaterials();

    /** Per-prim flag bits returned by {@link #ensure} (stored in {@code mat.z}/{@code mat.w}). */
    public static final int HAS_S = RtParallelAtlas.HAS_S;
    public static final int HAS_N = RtParallelAtlas.HAS_N;

    private final RtParallelAtlas atlas = new RtParallelAtlas(TextureAtlas.LOCATION_BLOCKS, "rt_blocks");

    private RtBlockMaterials() {
    }

    /** (Re)create both parallel atlases sized to the current block atlas. */
    public void reset() {
        atlas.reset();
    }

    /** Build the {@code _s}/{@code _n} atlases for every block-atlas sprite up front (parallel decode). */
    public void prepareAll() {
        atlas.prepareAll();
    }

    /** The {@link #HAS_S}|{@link #HAS_N} presence bitmask for a block-atlas sprite. */
    public int ensure(TextureAtlasSprite sprite) {
        return atlas.ensure(sprite);
    }

    /** Re-upload the atlases that gained sprites since the last flush. Call before the trace records. */
    public void flush() {
        atlas.flush();
    }

    /** Vulkan image-view handle of the {@code _s} atlas, or 0 if not created. Stable across uploads. */
    public long viewS() {
        return atlas.viewS();
    }

    /** Vulkan image-view handle of the {@code _n} atlas, or 0 if not created. Stable across uploads. */
    public long viewN() {
        return atlas.viewN();
    }
}
