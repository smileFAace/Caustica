package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-frame GPU buffer of explicit block area lights (simplified Radiance LightInfo path).
 *
 * <p>Each resident section may carry a host-side light list built at mesh time. Every frame the
 * composite gathers nearby lights into a single storage buffer addressed from {@code WorldPush}.
 * Layout matches {@code LocalLight} in {@code world_common.slang} (64 bytes/entry, std430).
 */
public final class RtLocalLights {
	public static final RtLocalLights INSTANCE = new RtLocalLights();

	/** Matches slang {@code LocalLight} / std430 packing. */
	public static final int LIGHT_BYTES = 64;
	public static final int FLOATS_PER_LIGHT = 12; // 3*float4 written as floats on CPU pack path

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
	 * Pack one area light into a host float array (12 floats = 48 bytes of the 64-byte struct; last
	 * float4 is written as area + pad). Order matches slang:
	 * {@code p0, p1, p2, radiance.xyz + area}.
	 */
	public static void packLight(float[] out, int base,
	                             float p0x, float p0y, float p0z,
	                             float p1x, float p1y, float p1z,
	                             float p2x, float p2y, float p2z,
	                             float rx, float ry, float rz, float area) {
		out[base] = p0x;
		out[base + 1] = p0y;
		out[base + 2] = p0z;
		out[base + 3] = 0f;
		out[base + 4] = p1x;
		out[base + 5] = p1y;
		out[base + 6] = p1z;
		out[base + 7] = 0f;
		out[base + 8] = p2x;
		out[base + 9] = p2y;
		out[base + 10] = p2z;
		out[base + 11] = 0f;
		// radiance + area occupies the last float4; we store as 4 floats after the three points
		// but FLOATS_PER_LIGHT is only 12 — callers use the extended 16-float form via ByteBuffer path.
	}

	/** 16 floats per light for upload (p0.xyzw, p1.xyzw, p2.xyzw, radiance.xyz + area). */
	public static final int UPLOAD_FLOATS = 16;

	public static void writeLight(float[] out, int base,
	                              float p0x, float p0y, float p0z,
	                              float p1x, float p1y, float p1z,
	                              float p2x, float p2y, float p2z,
	                              float rx, float ry, float rz, float area) {
		out[base] = p0x;
		out[base + 1] = p0y;
		out[base + 2] = p0z;
		out[base + 3] = 0f;
		out[base + 4] = p1x;
		out[base + 5] = p1y;
		out[base + 6] = p1z;
		out[base + 7] = 0f;
		out[base + 8] = p2x;
		out[base + 9] = p2y;
		out[base + 10] = p2z;
		out[base + 11] = 0f;
		out[base + 12] = rx;
		out[base + 13] = ry;
		out[base + 14] = rz;
		out[base + 15] = area;
	}

	/**
	 * Rebuild the GPU buffer from resident section lights. Positions in {@code lights} are
	 * section-local; this method rebases them into the terrain origin used by the path tracer
	 * ({@code world = sectionOrigin + local}, then camera-relative via shader camOffset).
	 *
	 * @param sections list of (section origin + host light float array) pairs; null/empty arrays skipped
	 * @param camX/Y/Z world camera for distance culling
	 * @param terrainBlockX/Y/Z terrain rebase origin (same as instance transforms)
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

		// Score + keep best lights. Each candidate stores 16 floats + score.
		ArrayList<Candidate> candidates = new ArrayList<>(Math.min(1024, maxLights * 2));
		for (SectionLights sec : sections) {
			if (sec == null || sec.lights == null || sec.lights.length < UPLOAD_FLOATS) {
				continue;
			}
			float ox = sec.sx - terrainBlockX;
			float oy = sec.sy - terrainBlockY;
			float oz = sec.sz - terrainBlockZ;
			// camera relative to terrain origin
			float cx = (float) (camX - terrainBlockX);
			float cy = (float) (camY - terrainBlockY);
			float cz = (float) (camZ - terrainBlockZ);
			float[] L = sec.lights;
			for (int i = 0; i + UPLOAD_FLOATS <= L.length; i += UPLOAD_FLOATS) {
				float p0x = L[i] + ox, p0y = L[i + 1] + oy, p0z = L[i + 2] + oz;
				float p1x = L[i + 4] + ox, p1y = L[i + 5] + oy, p1z = L[i + 6] + oz;
				float p2x = L[i + 8] + ox, p2y = L[i + 9] + oy, p2z = L[i + 10] + oz;
				float rx = L[i + 12], ry = L[i + 13], rz = L[i + 14], area = L[i + 15];
				// centroid for distance
				float mx = (p0x + p1x + p2x) * (1f / 3f);
				float my = (p0y + p1y + p2y) * (1f / 3f);
				float mz = (p0z + p1z + p2z) * (1f / 3f);
				float dx = mx - cx, dy = my - cy, dz = mz - cz;
				float distSq = dx * dx + dy * dy + dz * dz;
				if (distSq > rangeSq * 4f) { // hard cull beyond 2*range
					continue;
				}
				float lum = 0.2126f * rx + 0.7152f * ry + 0.0722f * rz;
				float score = lum * Math.max(area, 1.0e-4f) / (1f + distSq);
				candidates.add(new Candidate(p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, rx, ry, rz, area, score));
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
			mapped.putFloat(off, c.p0x);
			mapped.putFloat(off + 4, c.p0y);
			mapped.putFloat(off + 8, c.p0z);
			mapped.putFloat(off + 12, 0f);
			mapped.putFloat(off + 16, c.p1x);
			mapped.putFloat(off + 20, c.p1y);
			mapped.putFloat(off + 24, c.p1z);
			mapped.putFloat(off + 28, 0f);
			mapped.putFloat(off + 32, c.p2x);
			mapped.putFloat(off + 36, c.p2y);
			mapped.putFloat(off + 40, c.p2z);
			mapped.putFloat(off + 44, 0f);
			mapped.putFloat(off + 48, c.rx);
			mapped.putFloat(off + 52, c.ry);
			mapped.putFloat(off + 56, c.rz);
			mapped.putFloat(off + 60, c.area);
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
			// Grow: destroy old after idle — rare path (max-lights change / first use).
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

	/** Host light list for one section (section-local positions). */
	public record SectionLights(int sx, int sy, int sz, float[] lights) {
	}

	private record Candidate(
			float p0x, float p0y, float p0z,
			float p1x, float p1y, float p1z,
			float p2x, float p2y, float p2z,
			float rx, float ry, float rz, float area,
			float score) {
	}
}
