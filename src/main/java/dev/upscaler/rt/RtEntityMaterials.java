package dev.upscaler.rt;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * LabPBR {@code _s}/{@code _n} for <b>block entities</b> (chests, signs, beds, shulker boxes, banners, …),
 * which texture from their own dedicated sprite atlases (the chest sheet, sign sheet, …) rather than the
 * block atlas. For each such atlas we build a parallel {@code _s}/{@code _n} atlas ({@link RtParallelAtlas})
 * mirroring its layout, and bind those views into the bindless entity-material arrays at the atlas's albedo
 * slot ({@link RtEntityTextures#slotForBlockEntityAtlas}). The hit shader then samples them via the existing
 * per-type bindless path (prim {@code mat} code 1) at the captured atlas UV — no shader change.
 *
 * <p>Built lazily per atlas on first sight (block-entity atlases are few and small, so up-front
 * enumeration isn't worth it). Render-thread only ({@link RtParallelAtlas} creates/uploads GPU textures).
 */
public final class RtEntityMaterials {
    public static final RtEntityMaterials INSTANCE = new RtEntityMaterials();

    private final Map<Identifier, RtParallelAtlas> atlases = new HashMap<>();

    private RtEntityMaterials() {
    }

    /**
     * The parallel {@code _s}/{@code _n} atlas for a source atlas, created (and sized to the source atlas)
     * on first sight and retried each call until the source atlas is ready. Returns null while it isn't.
     * Render-thread only.
     */
    public RtParallelAtlas atlasFor(Identifier atlasLocation) {
        if (atlasLocation == null) {
            return null;
        }
        RtParallelAtlas pa = atlases.get(atlasLocation);
        if (pa == null) {
            pa = new RtParallelAtlas(atlasLocation, label(atlasLocation));
            atlases.put(atlasLocation, pa);
        }
        if (!pa.isReady()) {
            pa.reset(); // source atlas may not have been resolved at first sight; retry until it is
        }
        return pa.isReady() ? pa : null;
    }

    /** The {@code HAS_S}|{@code HAS_N} presence bitmask for a block-entity sprite (0 if its atlas isn't ready). */
    public int ensure(TextureAtlasSprite sprite) {
        if (sprite == null) {
            return 0;
        }
        RtParallelAtlas pa = atlasFor(sprite.atlasLocation());
        return pa != null ? pa.ensure(sprite) : 0;
    }

    /** Re-upload every block-entity atlas that gained sprites since the last flush. Pre-trace each frame. */
    public void flushAll() {
        for (RtParallelAtlas pa : atlases.values()) {
            pa.flush();
        }
    }

    /** Free every parallel atlas + drop the registry (pipeline recreate / resource reload). */
    public void reset() {
        for (RtParallelAtlas pa : atlases.values()) {
            pa.close();
        }
        atlases.clear();
    }

    private static String label(Identifier atlasLocation) {
        return "rt_mat_" + atlasLocation.getNamespace() + "_" + atlasLocation.getPath().replace('/', '_');
    }
}
