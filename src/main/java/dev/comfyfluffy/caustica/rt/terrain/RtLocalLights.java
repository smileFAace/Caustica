package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-frame GPU buffer of block lights for local NEE.
 *
 * <p>Mesh time stores <b>one point light per emissive block</b> (16 floats: position + radiance +
 * importance weight). Each frame nearby lights are culled into a single storage buffer for the path
 * tracer. Layout matches {@code LocalLight} in {@code world_common.slang} (64 B / entry, std430):
 * <pre>
 *   float4 position;   // xyz terrain-rebase, w unused
 *   float4 radiance;   // xyz = tint * level01, w = pick weight (relative power)
 *   float4 pad0;
 *   float4 pad1;
 * </pre>
 */
public final class RtLocalLights {
	public static final RtLocalLights INSTANCE = new RtLocalLights();

	public static final int LIGHT_BYTES = 64;
	/** Host pack: pos.xyz, pad, rad.xyz, weight, + 8 pad floats = 16 floats. */
	public static final int UPLOAD_FLOATS = 16;

	private RtBuffer buffer;
	private int capacity;
	private int count;

	private RtLocalLights() {
	}

	public long bufferAddress() {
		return buffer != null ? buffer.deviceAddress : 0L;
	}

	public int count() {
		return count;
	}

	/**
	 * Pack one point light (section-local position). {@code weight} is relative sampling weight
	 * (typically level * max channel of tint); shader treats radiance.w as this weight.
	 */
	public static void writePointLight(float[] out, int base,
	                                   float x, float y, float z,
	                                   float rx, float ry, float rz, float weight) {
		out[base] = x;
		out[base + 1] = y;
		out[base + 2] = z;
		out[base + 3] = 0f;
		out[base + 4] = rx;
		out[base + 5] = ry;
		out[base + 6] = rz;
		out[base + 7] = Math.max(weight, 1.0e-4f);
		for (int i = 8; i < UPLOAD_FLOATS; i++) {
			out[base + i] = 0f;
		}
	}

	/**
	 * Rebuild the GPU buffer from resident section lights.
	 * Positions in {@code lights} are section-local; rebased into terrain origin space for the shader.
	 */
	public void update(RtContext ctx, List<SectionLights> sections,
	                   double camX, double camY, double camZ,
	                   int terrainBlockX, int terrainBlockY, int terrainBlockZ) {
		if (!CausticaConfig.Rt.LocalLights.ENABLED.value()) {
			count = 0;
			return;
		}
		int maxLights = CausticaConfig.Rt.LocalLights.MAX_LIGHTS.value();
		float range = CausticaConfig.Rt.LocalLights.RANGE.value();
		float rangeSq = range * range;

		ArrayList<Candidate> candidates = new ArrayList<>(Math.min(1024, maxLights * 2));
		float cx = (float) (camX - terrainBlockX);
		float cy = (float) (camY - terrainBlockY);
		float cz = (float) (camZ - terrainBlockZ);

		for (SectionLights sec : sections) {
			if (sec == null || sec.lights == null || sec.lights.length < UPLOAD_FLOATS) {
				continue;
			}
			float ox = sec.sx - terrainBlockX;
			float oy = sec.sy - terrainBlockY;
			float oz = sec.sz - terrainBlockZ;
			float[] L = sec.lights;
			for (int i = 0; i + UPLOAD_FLOATS <= L.length; i += UPLOAD_FLOATS) {
				float px = L[i] + ox, py = L[i + 1] + oy, pz = L[i + 2] + oz;
				float rx = L[i + 4], ry = L[i + 5], rz = L[i + 6], weight = L[i + 7];
				float dx = px - cx, dy = py - cy, dz = pz - cz;
				float distSq = dx * dx + dy * dy + dz * dz;
				// Keep a little beyond soft range so edge lights still contribute when walking.
				if (distSq > rangeSq * 2.25f) {
					continue;
				}
				// Score: bright nearby lights first. weight ~ emission power.
				float score = weight / (1f + distSq);
				candidates.add(new Candidate(px, py, pz, rx, ry, rz, weight, score));
			}
		}
		candidates.sort((a, b) -> Float.compare(b.score, a.score));
		int n = Math.min(maxLights, candidates.size());
		ensureCapacity(ctx, Math.max(n, 1));
		ByteBuffer mapped = MemoryUtil.memByteBuffer(buffer.mapped, capacity * LIGHT_BYTES);
		for (int i = 0; i < capacity * LIGHT_BYTES; i++) {
			mapped.put(i, (byte) 0);
		}
		for (int i = 0; i < n; i++) {
			Candidate c = candidates.get(i);
			int off = i * LIGHT_BYTES;
			mapped.putFloat(off, c.x);
			mapped.putFloat(off + 4, c.y);
			mapped.putFloat(off + 8, c.z);
			mapped.putFloat(off + 12, 0f);
			mapped.putFloat(off + 16, c.rx);
			mapped.putFloat(off + 20, c.ry);
			mapped.putFloat(off + 24, c.rz);
			mapped.putFloat(off + 28, c.weight);
			// pad0/pad1 left zero
		}
		if (n > 0) {
			buffer.flush(0, (long) n * LIGHT_BYTES);
		}
		count = n;
	}

	private void ensureCapacity(RtContext ctx, int needed) {
		if (buffer != null && capacity >= needed) {
			return;
		}
		if (buffer != null) {
			ctx.waitIdle();
			buffer.destroy();
			buffer = null;
		}
		int cap = 1;
		while (cap < needed) {
			cap <<= 1;
		}
		cap = Math.max(cap, 64);
		int storage = org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
		buffer = ctx.createBuffer((long) cap * LIGHT_BYTES, storage, true, "local lights " + cap);
		capacity = cap;
	}

	public void destroy() {
		if (buffer != null) {
			buffer.destroy();
			buffer = null;
		}
		capacity = 0;
		count = 0;
	}

	public record SectionLights(int sx, int sy, int sz, float[] lights) {
	}

	private record Candidate(
			float x, float y, float z,
			float rx, float ry, float rz, float weight,
			float score) {
	}
}
