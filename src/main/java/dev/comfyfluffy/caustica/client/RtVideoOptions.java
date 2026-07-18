package dev.comfyfluffy.caustica.client;

import com.mojang.serialization.Codec;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaConfig.BooleanSetting;
import dev.comfyfluffy.caustica.CausticaConfig.FloatSetting;
import dev.comfyfluffy.caustica.CausticaConfig.IntSetting;
import dev.comfyfluffy.caustica.CausticaConfig.StringSetting;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/**
 * Builds {@link OptionInstance} widgets for {@link CausticaSettingsScreen}.
 * Each option binds to a {@link CausticaConfig} runtime setting and takes effect next frame where possible.
 *
 * <p>Device/buffer rebuild toggles (worker threads, OMM, max entity capacities) stay on the
 * {@code -Dcaustica.*} / toml startup surface only.
 */
public final class RtVideoOptions {
	private RtVideoOptions() {
	}

	/** Full flat list (legacy callers / tests). Prefer the sectioned helpers below. */
	public static OptionInstance<?>[] runtimeOptions() {
		OptionInstance<?>[] quality = qualityOptions();
		OptionInstance<?>[] emission = emissionOptions();
		OptionInstance<?>[] features = featureOptions();
		OptionInstance<?>[] upscale = upscaleOptions();
		OptionInstance<?>[] debug = debugOptions();
		OptionInstance<?>[] all = new OptionInstance<?>[quality.length + emission.length + features.length
				+ upscale.length + debug.length];
		int i = 0;
		System.arraycopy(quality, 0, all, i, quality.length);
		i += quality.length;
		System.arraycopy(emission, 0, all, i, emission.length);
		i += emission.length;
		System.arraycopy(features, 0, all, i, features.length);
		i += features.length;
		System.arraycopy(upscale, 0, all, i, upscale.length);
		i += upscale.length;
		System.arraycopy(debug, 0, all, i, debug.length);
		return all;
	}

	public static OptionInstance<?>[] qualityOptions() {
		return new OptionInstance<?>[] {
			exposureMode(),
			manualEv(),
			spp(),
			maxBounces(),
			sunSize(),
			hdrEnabled(),
			hdrPaperWhite(),
			hdrPeak(),
		};
	}

	public static OptionInstance<?>[] emissionOptions() {
		return new OptionInstance<?>[] {
			emissionStrength(),
			emissionLightLevelPower(),
			emissionTintStrength(),
		};
	}

	public static OptionInstance<?>[] featureOptions() {
		return new OptionInstance<?>[] {
			entities(),
			particles(),
			glow(),
			nameTags(),
			blockOutline(),
			waterWaves(),
			localLights(),
			localLightSamples(),
			localLightRange(),
		};
	}

	/** DLSS-RR / Frame Generation / Reflex — all re-read at runtime (FG needs capable HW). */
	public static OptionInstance<?>[] upscaleOptions() {
		return new OptionInstance<?>[] {
			dlssRrEnabled(),
			dlssQuality(),
			frameGenerationEnabled(),
			frameGenerationCount(),
			reflexEnabled(),
			reflexBoost(),
		};
	}

	public static OptionInstance<?>[] debugOptions() {
		return new OptionInstance<?>[] {
			debugView(),
			frameStats(),
		};
	}

	private static OptionInstance<String> exposureMode() {
		StringSetting setting = CausticaConfig.Rt.Exposure.MODE;
		return new OptionInstance<>(
			"caustica.options.rt.exposureMode",
			OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.exposureMode.tooltip")),
			(caption, value) -> Component.translatable("caustica.options.rt.exposureMode." + value),
			new OptionInstance.Enum<>(List.of("auto", "manual"), Codec.STRING),
			setting.get(),
			setting::set);
	}

	private static OptionInstance<Integer> manualEv() {
		FloatSetting setting = CausticaConfig.Rt.Exposure.MANUAL_EV;
		return new OptionInstance<>(
			"caustica.options.rt.manualEv",
			OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.manualEv.tooltip")),
			(caption, tenths) -> {
				float ev = tenths / 10.0f;
				String sign = ev > 0.0f ? "+" : "";
				return Options.genericValueLabel(caption,
						Component.literal(sign + String.format(Locale.ROOT, "%.1f EV", ev)));
			},
			new OptionInstance.IntRange(-50, 50),
			Math.clamp(Math.round(setting.value() * 10.0f), -50, 50),
			tenths -> setting.set(tenths / 10.0f));
	}

	private static OptionInstance<Integer> spp() {
		IntSetting setting = CausticaConfig.Rt.Composite.SPP;
		return new OptionInstance<>(
			"caustica.options.rt.spp",
			OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.spp.tooltip")),
			(caption, value) -> Options.genericValueLabel(caption, value),
			new OptionInstance.IntRange(1, 8),
			Math.clamp(setting.value(), 1, 8),
			setting::set);
	}

	private static OptionInstance<Integer> maxBounces() {
		IntSetting setting = CausticaConfig.Rt.Composite.MAX_BOUNCES;
		return new OptionInstance<>(
			"caustica.options.rt.maxBounces",
			OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.maxBounces.tooltip")),
			(caption, value) -> Options.genericValueLabel(caption, value),
			new OptionInstance.IntRange(2, 8),
			Math.clamp(setting.value(), 2, 8),
			setting::set);
	}

	private static OptionInstance<Integer> sunSize() {
		FloatSetting setting = CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS;
		int initialTenths = Math.clamp(Math.round((float) Math.toDegrees(setting.value()) * 10.0f), 1, 50);
		return new OptionInstance<>(
			"caustica.options.rt.sunSize",
			OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.sunSize.tooltip")),
			(caption, tenths) -> Options.genericValueLabel(caption, Component.literal(String.format("%.1f°", tenths / 10.0))),
			new OptionInstance.IntRange(1, 50),
			initialTenths,
			tenths -> setting.set(tenths / 10.0f));
	}

	private static OptionInstance<Boolean> entities() {
		return bool("caustica.options.rt.entities", CausticaConfig.Rt.Entities.ENABLED);
	}

	private static OptionInstance<Boolean> particles() {
		return bool("caustica.options.rt.particles", CausticaConfig.Rt.Entities.PARTICLES_ENABLED);
	}

	private static OptionInstance<Boolean> glow() {
		return bool("caustica.options.rt.glow", CausticaConfig.Rt.Entities.GLOW_ENABLED);
	}

	private static OptionInstance<Boolean> nameTags() {
		return bool("caustica.options.rt.nameTags", CausticaConfig.Rt.Entities.NAME_TAGS_ENABLED);
	}

	private static OptionInstance<Boolean> blockOutline() {
		return bool("caustica.options.rt.blockOutline", CausticaConfig.Rt.Overlay.BLOCK_OUTLINE_ENABLED);
	}

	private static OptionInstance<Boolean> waterWaves() {
		return bool("caustica.options.rt.waterWaves", CausticaConfig.Rt.Composite.WATER_WAVES);
	}

	private static OptionInstance<Boolean> localLights() {
		return bool("caustica.options.rt.localLights", CausticaConfig.Rt.LocalLights.ENABLED);
	}

	private static OptionInstance<Integer> localLightSamples() {
		IntSetting setting = CausticaConfig.Rt.LocalLights.SAMPLES;
		return new OptionInstance<>(
			"caustica.options.rt.localLightSamples",
			OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.localLightSamples.tooltip")),
			(caption, value) -> Options.genericValueLabel(caption, Component.literal(Integer.toString(value))),
			new OptionInstance.IntRange(1, 4),
			Math.clamp(setting.value(), 1, 4),
			setting::set);
	}

	private static OptionInstance<Integer> localLightRange() {
		FloatSetting setting = CausticaConfig.Rt.LocalLights.RANGE;
		return new OptionInstance<>(
			"caustica.options.rt.localLightRange",
			OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.localLightRange.tooltip")),
			(caption, value) -> Options.genericValueLabel(caption, Component.literal(value + "")),
			new OptionInstance.IntRange(4, 64),
			Math.clamp(Math.round(setting.value()), 4, 64),
			v -> setting.set((float) v));
	}

	private static OptionInstance<Boolean> dlssRrEnabled() {
		return bool("caustica.options.rt.dlssRr", CausticaConfig.Rt.DlssRr.ENABLED);
	}

	private static OptionInstance<Boolean> frameGenerationEnabled() {
		return bool("caustica.options.rt.frameGeneration", CausticaConfig.Rt.Fg.ENABLED);
	}

	/** Generated frames per rendered frame (1 = 2x, 2 = 3x, …); clamped by driver at runtime. */
	private static OptionInstance<Integer> frameGenerationCount() {
		IntSetting setting = CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT;
		return new OptionInstance<>(
			"caustica.options.rt.frameGenerationCount",
			OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.frameGenerationCount.tooltip")),
			(caption, value) -> Options.genericValueLabel(caption, Component.literal((value + 1) + "x")),
			new OptionInstance.IntRange(1, 4),
			Math.clamp(setting.value(), 1, 4),
			setting::set);
	}

	private static OptionInstance<Boolean> reflexEnabled() {
		return bool("caustica.options.rt.reflex", CausticaConfig.Rt.Reflex.ENABLED);
	}

	private static OptionInstance<Boolean> reflexBoost() {
		return bool("caustica.options.rt.reflexBoost", CausticaConfig.Rt.Reflex.LOW_LATENCY_BOOST);
	}

	private static OptionInstance<Boolean> frameStats() {
		return bool("caustica.options.rt.frameStats", CausticaConfig.Rt.FrameStats.ENABLED);
	}

	private static final List<Integer> DLSS_QUALITY_ORDER = List.of(3, 0, 1, 2, 5);

	private static OptionInstance<Integer> dlssQuality() {
		IntSetting setting = CausticaConfig.Rt.DlssRr.QUALITY;
		int initialQuality = DLSS_QUALITY_ORDER.contains(setting.value()) ? setting.value() : 0;
		int initialPosition = DLSS_QUALITY_ORDER.indexOf(initialQuality);
		return new OptionInstance<>(
			"caustica.options.rt.dlssQuality",
			OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.dlssQuality.tooltip")),
			(caption, position) -> Options.genericValueLabel(caption,
					Component.translatable("caustica.options.rt.dlssQuality." + DLSS_QUALITY_ORDER.get(position))),
			new OptionInstance.IntRange(0, DLSS_QUALITY_ORDER.size() - 1),
			initialPosition,
			position -> setting.set(DLSS_QUALITY_ORDER.get(position)));
	}

	private static OptionInstance<Boolean> hdrEnabled() {
		return bool("caustica.options.rt.hdr", CausticaConfig.Rt.Hdr.ENABLED);
	}

	private static OptionInstance<Integer> hdrPaperWhite() {
		FloatSetting setting = CausticaConfig.Rt.Hdr.PAPER_WHITE_NITS;
		return new OptionInstance<>(
			"caustica.options.rt.hdrPaperWhite",
			OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrPaperWhite.tooltip")),
			(caption, nits) -> Options.genericValueLabel(caption, Component.literal(nits + " nits")),
			new OptionInstance.IntRange(80, 1000),
			Math.clamp(Math.round(setting.value()), 80, 1000),
			nits -> setting.set(nits.floatValue()));
	}

	private static OptionInstance<Integer> hdrPeak() {
		FloatSetting setting = CausticaConfig.Rt.Hdr.PEAK_NITS;
		return new OptionInstance<>(
			"caustica.options.rt.hdrPeak",
			OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrPeak.tooltip")),
			(caption, nits) -> Options.genericValueLabel(caption, Component.literal(nits + " nits")),
			new OptionInstance.IntRange(80, 10000),
			Math.clamp(Math.round(setting.value()), 80, 10000),
			nits -> setting.set(nits.floatValue()));
	}

	/** 0.0–32.0 strength as tenths for the slider (0–320). */
	private static OptionInstance<Integer> emissionStrength() {
		FloatSetting setting = CausticaConfig.Rt.Emission.STRENGTH;
		return new OptionInstance<>(
			"caustica.options.rt.emissionStrength",
			OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.emissionStrength.tooltip")),
			(caption, tenths) -> Options.genericValueLabel(caption,
					Component.literal(String.format(Locale.ROOT, "%.1f", tenths / 10.0f))),
			new OptionInstance.IntRange(0, 320),
			Math.clamp(Math.round(setting.value() * 10.0f), 0, 320),
			tenths -> setting.set(tenths / 10.0f));
	}

	/** 0.25–4.00 as hundredths (25–400). */
	private static OptionInstance<Integer> emissionLightLevelPower() {
		FloatSetting setting = CausticaConfig.Rt.Emission.LIGHT_LEVEL_POWER;
		return new OptionInstance<>(
			"caustica.options.rt.emissionLightLevelPower",
			OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.emissionLightLevelPower.tooltip")),
			(caption, hundredths) -> Options.genericValueLabel(caption,
					Component.literal(String.format(Locale.ROOT, "%.2f", hundredths / 100.0f))),
			new OptionInstance.IntRange(25, 400),
			Math.clamp(Math.round(setting.value() * 100.0f), 25, 400),
			hundredths -> setting.set(hundredths / 100.0f));
	}

	/** 0–100% tint blend. */
	private static OptionInstance<Integer> emissionTintStrength() {
		FloatSetting setting = CausticaConfig.Rt.Emission.TINT_STRENGTH;
		return new OptionInstance<>(
			"caustica.options.rt.emissionTintStrength",
			OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.emissionTintStrength.tooltip")),
			(caption, percent) -> Options.genericValueLabel(caption, Component.literal(percent + "%")),
			new OptionInstance.IntRange(0, 100),
			Math.clamp(Math.round(setting.value() * 100.0f), 0, 100),
			percent -> setting.set(percent / 100.0f));
	}

	private static OptionInstance<Integer> debugView() {
		IntSetting setting = CausticaConfig.Rt.Composite.DEBUG_VIEW;
		return new OptionInstance<>(
			"caustica.options.rt.debugView",
			OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.debugView.tooltip")),
			(caption, value) -> Component.translatable("caustica.options.rt.debugView." + value),
			new OptionInstance.Enum<>(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13), Codec.INT),
			Math.clamp(setting.value(), 0, 13),
			setting::set);
	}

	private static OptionInstance<Boolean> bool(String captionKey, BooleanSetting setting) {
		return OptionInstance.createBoolean(
			captionKey,
			OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
			setting.value(),
			setting::set);
	}
}
