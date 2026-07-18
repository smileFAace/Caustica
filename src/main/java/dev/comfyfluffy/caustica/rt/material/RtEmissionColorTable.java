package dev.comfyfluffy.caustica.rt.material;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WeatheringCopperCollection;
import net.minecraft.world.level.block.state.BlockState;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Built-in per-block emission color temperatures for path-traced local lights.
 *
 * <p>Without a table entry the path tracer multiplies albedo only (often reads as washed-out white on
 * torch-like sprites). Entries pack as 8-bit RGB into {@code TerrainPrim.aux0}; the hit shader blends
 * them with runtime {@code emission.tint-strength}. Changing this table requires a terrain rebuild
 * (F3+A) because aux0 is written at mesh time.
 *
 * <p>Table fill is lazy so pure unit tests can call {@link #packRgb} without Minecraft bootstrap.
 */
public final class RtEmissionColorTable {
	/** 0 means "no override" in the GPU path (white / albedo only). */
	public static final int NONE = 0;

	private static final Object LOCK = new Object();
	private static Map<Block, Integer> byBlock;

	private RtEmissionColorTable() {
	}

	public static int packedFor(BlockState state) {
		if (state == null) {
			return NONE;
		}
		return table().getOrDefault(state.getBlock(), NONE);
	}

	public static int packedFor(Block block) {
		return block == null ? NONE : table().getOrDefault(block, NONE);
	}

	/** Linear RGB in 0..1 → packed 0xRRGGBB (never returns 0; GPU reserves 0 for "no override"). */
	public static int packRgb(float r, float g, float b) {
		int ri = Math.clamp(Math.round(r * 255.0f), 0, 255);
		int gi = Math.clamp(Math.round(g * 255.0f), 0, 255);
		int bi = Math.clamp(Math.round(b * 255.0f), 0, 255);
		int packed = (ri << 16) | (gi << 8) | bi;
		return packed == 0 ? 0x010101 : packed;
	}

	/** Built-in entry count after first use (0 until {@link #table()} is forced in-game). */
	public static int size() {
		return byBlock == null ? 0 : byBlock.size();
	}

	/** Diagnostic: known id → packed, or NONE. */
	public static int packedForId(Identifier id) {
		if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
			return NONE;
		}
		return packedFor(BuiltInRegistries.BLOCK.getValue(id));
	}

	private static Map<Block, Integer> table() {
		Map<Block, Integer> current = byBlock;
		if (current != null) {
			return current;
		}
		synchronized (LOCK) {
			if (byBlock == null) {
				byBlock = build();
			}
			return byBlock;
		}
	}

	private static Map<Block, Integer> build() {
		Map<Block, Integer> map = new IdentityHashMap<>();
		// Warm fire family
		put(map, Blocks.TORCH, 1.00f, 0.55f, 0.18f);
		put(map, Blocks.WALL_TORCH, 1.00f, 0.55f, 0.18f);
		put(map, Blocks.SOUL_TORCH, 0.35f, 0.75f, 1.00f);
		put(map, Blocks.SOUL_WALL_TORCH, 0.35f, 0.75f, 1.00f);
		put(map, Blocks.LANTERN, 1.00f, 0.62f, 0.28f);
		put(map, Blocks.SOUL_LANTERN, 0.35f, 0.78f, 1.00f);
		put(map, Blocks.CAMPFIRE, 1.00f, 0.50f, 0.15f);
		put(map, Blocks.SOUL_CAMPFIRE, 0.30f, 0.72f, 1.00f);
		put(map, Blocks.FIRE, 1.00f, 0.45f, 0.12f);
		put(map, Blocks.SOUL_FIRE, 0.30f, 0.70f, 1.00f);
		put(map, Blocks.MAGMA_BLOCK, 1.00f, 0.35f, 0.10f);
		put(map, Blocks.LAVA, 1.00f, 0.40f, 0.10f);
		put(map, Blocks.LAVA_CAULDRON, 1.00f, 0.40f, 0.10f);

		// Warm mineral / furnace
		put(map, Blocks.GLOWSTONE, 1.00f, 0.88f, 0.45f);
		put(map, Blocks.SHROOMLIGHT, 1.00f, 0.72f, 0.28f);
		put(map, Blocks.JACK_O_LANTERN, 1.00f, 0.65f, 0.20f);
		put(map, Blocks.FURNACE, 1.00f, 0.55f, 0.18f);
		put(map, Blocks.BLAST_FURNACE, 1.00f, 0.50f, 0.15f);
		put(map, Blocks.SMOKER, 1.00f, 0.50f, 0.15f);
		putAll(map, Blocks.COPPER_BULB, 1.00f, 0.82f, 0.55f);
		putAll(map, Blocks.COPPER_LANTERN, 1.00f, 0.72f, 0.35f);

		// Cool / magical
		put(map, Blocks.SEA_LANTERN, 0.55f, 0.85f, 1.00f);
		put(map, Blocks.END_ROD, 0.85f, 0.90f, 1.00f);
		put(map, Blocks.BEACON, 0.70f, 0.95f, 1.00f);
		put(map, Blocks.CONDUIT, 0.40f, 0.80f, 1.00f);
		put(map, Blocks.CRYING_OBSIDIAN, 0.75f, 0.25f, 1.00f);
		put(map, Blocks.RESPAWN_ANCHOR, 0.85f, 0.30f, 1.00f);
		put(map, Blocks.AMETHYST_CLUSTER, 0.80f, 0.55f, 1.00f);
		put(map, Blocks.LARGE_AMETHYST_BUD, 0.80f, 0.55f, 1.00f);
		put(map, Blocks.MEDIUM_AMETHYST_BUD, 0.80f, 0.55f, 1.00f);
		put(map, Blocks.SMALL_AMETHYST_BUD, 0.80f, 0.55f, 1.00f);

		// Redstone family
		put(map, Blocks.REDSTONE_TORCH, 1.00f, 0.20f, 0.15f);
		put(map, Blocks.REDSTONE_WALL_TORCH, 1.00f, 0.20f, 0.15f);
		put(map, Blocks.REDSTONE_ORE, 1.00f, 0.18f, 0.12f);
		put(map, Blocks.DEEPSLATE_REDSTONE_ORE, 1.00f, 0.18f, 0.12f);
		put(map, Blocks.REDSTONE_BLOCK, 1.00f, 0.15f, 0.12f);
		put(map, Blocks.REDSTONE_LAMP, 1.00f, 0.90f, 0.55f);

		// Plants / sculk / nether flora
		put(map, Blocks.GLOW_LICHEN, 0.55f, 0.95f, 0.70f);
		put(map, Blocks.CAVE_VINES, 0.70f, 1.00f, 0.35f);
		put(map, Blocks.CAVE_VINES_PLANT, 0.70f, 1.00f, 0.35f);
		put(map, Blocks.SCULK_CATALYST, 0.25f, 0.85f, 0.80f);
		put(map, Blocks.PEARLESCENT_FROGLIGHT, 1.00f, 0.75f, 0.90f);
		put(map, Blocks.VERDANT_FROGLIGHT, 0.55f, 1.00f, 0.55f);
		put(map, Blocks.OCHRE_FROGLIGHT, 1.00f, 0.85f, 0.40f);
		put(map, Blocks.BREWING_STAND, 0.85f, 0.70f, 1.00f);
		put(map, Blocks.ENCHANTING_TABLE, 0.55f, 0.40f, 1.00f);
		put(map, Blocks.END_PORTAL_FRAME, 0.45f, 0.85f, 0.55f);
		put(map, Blocks.END_PORTAL, 0.20f, 0.05f, 0.35f);
		put(map, Blocks.END_GATEWAY, 0.20f, 0.05f, 0.35f);
		return Map.copyOf(map);
	}

	private static void put(Map<Block, Integer> map, Block block, float r, float g, float b) {
		map.put(block, packRgb(r, g, b));
	}

	private static void putAll(Map<Block, Integer> map, WeatheringCopperCollection<Block> collection,
			float r, float g, float b) {
		int packed = packRgb(r, g, b);
		collection.forEach(block -> map.put(block, packed));
	}
}
