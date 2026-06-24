package dev.upscaler.rt.entity;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Particle capture adapter. MC's {@code QuadParticleRenderState.buildLayer} drives a {@link VertexConsumer}
 * with the chained form {@code addVertex(x,y,z).setUv(u,v).setColor(argb).setLight(packed)} — four verts
 * per billboard quad. This adapter forwards each completed vertex to {@link RtEntityCapture}'s bulk
 * {@code addVertex} (with a zero normal, so {@code emitQuad} derives a geometric one from the quad edges),
 * letting particles reuse the entity mesh layout + BLAS/geometry-table/append path verbatim.
 *
 * <p>Positions arrive camera-relative ({@code SingleQuadParticle.extract} subtracts the camera position);
 * a per-frame {@link #setOffset offset} (camPos − rebaseOrigin) shifts them into the renderer's rebased
 * space so the TLAS instance transform is identity, exactly like captured entities. Light is ignored (v1
 * particles are unlit); the per-particle colour rides through as the bulk {@code addVertex} ARGB and the
 * texture slot through {@link RtEntityCapture#currentTexSlot} (set per layer by the caller).
 */
public final class RtParticleCapture implements VertexConsumer {
    private final RtEntityCapture out;
    private float ox, oy, oz;

    // Fully-lit packed lightmap (block 15 << 4 | sky 15 << 20) — default so a particle that never calls
    // setLight renders at full brightness rather than black.
    private static final int FULL_BRIGHT = 0xF000F0;

    // One buffered vertex: the chained protocol calls addVertex first, then setUv/setColor/setLight for the
    // SAME vertex, so the vertex is only complete at the next addVertex (or flush()).
    private boolean pending;
    private float x, y, z, u, v;
    private int color = 0xFFFFFFFF;
    private int light = FULL_BRIGHT;

    public RtParticleCapture(RtEntityCapture out) {
        this.out = out;
    }

    /** Camera-relative → rebased-space offset (camPos − rebaseOrigin), added to every captured vertex. */
    public void setOffset(float ox, float oy, float oz) {
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
    }

    /**
     * Forward the buffered vertex to the entity capture (zero normal → geometric; ARGB → tint; slot).
     * The albedo is dimmed by the particle's lightmap brightness so particles respond to ambient light
     * instead of rendering fullbright — otherwise an unlit ~0.8 particle blows out against a dark scene
     * once auto-exposure cranks up. Emissive particles (flame/lava/end-rod) report a fullbright lightmap
     * via getLightColor, so they stay bright; ambient-lit particles (smoke/splash) dim in the dark.
     */
    public void flush() {
        if (!pending) {
            return;
        }
        int block = (light >> 4) & 0xF;
        int sky = (light >> 20) & 0xF;
        float bright = Math.max(block, sky) / 15.0f;
        int a = (color >>> 24) & 0xFF;
        int r = Math.round(((color >> 16) & 0xFF) * bright);
        int g = Math.round(((color >> 8) & 0xFF) * bright);
        int b = Math.round((color & 0xFF) * bright);
        out.addVertex(x, y, z, (a << 24) | (r << 16) | (g << 8) | b, u, v, 0, 0, 0f, 0f, 0f);
        pending = false;
    }

    @Override
    public VertexConsumer addVertex(float vx, float vy, float vz) {
        flush(); // finalize the previous vertex (setUv/setColor follow addVertex in the chained protocol)
        x = vx + ox;
        y = vy + oy;
        z = vz + oz;
        u = 0f;
        v = 0f;
        color = 0xFFFFFFFF;
        light = FULL_BRIGHT;
        pending = true;
        return this;
    }

    @Override public VertexConsumer setUv(float pu, float pv) { u = pu; v = pv; return this; }
    @Override public VertexConsumer setColor(int c) { color = c; return this; }
    @Override public VertexConsumer setColor(int r, int g, int b, int a) {
        color = (a << 24) | (r << 16) | (g << 8) | b;
        return this;
    }
    @Override public VertexConsumer setLight(int packed) { light = packed; return this; }

    // Unused VertexConsumer surface (buildLayer only calls addVertex/setUv/setColor/setLight).
    @Override public VertexConsumer setUv1(int u1, int v1) { return this; }
    @Override public VertexConsumer setUv2(int u2, int v2) { return this; }
    @Override public VertexConsumer setNormal(float nx, float ny, float nz) { return this; }
    @Override public VertexConsumer setLineWidth(float width) { return this; }
}
