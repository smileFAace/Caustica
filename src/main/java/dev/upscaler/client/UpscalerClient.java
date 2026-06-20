package dev.upscaler.client;

import dev.upscaler.UpscalerMod;
import dev.upscaler.rt.RtContext;
import dev.upscaler.rt.RtDeviceBringup;
import dev.upscaler.rt.RtComposite;
import dev.upscaler.rt.RtEntities;
import dev.upscaler.rt.RtTerrain;
import dev.upscaler.rt.RtWorkerPool;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.InvalidateRenderStateCallback;

public final class UpscalerClient implements ClientModInitializer {
	private static boolean rtInitDone = false;

	@Override
	public void onInitializeClient() {
		UpscalerMod.LOGGER.info("Sodium Upscaler client initialized");

		// The GpuDevice exists well before the first tick, so a one-shot at tick start
		// runs on the render thread with the device idle between frames.
		ClientTickEvents.START_CLIENT_TICK.register(client -> {
			if (!VanillaRenderController.rtRuntimeWorkRequested()) {
				if (rtInitDone) {
					shutdownRt();
				}
				return;
			}

			// Bring up the RT device/context once; terrain residency + the composite follow below.
			if (!rtInitDone && RtDeviceBringup.rtRequested()) {
				RtContext ctx = RtContext.get();
				if (ctx != null) {
					rtInitDone = true;
				}
			}

			// P2: once RT is up, keep section residency synced to vanilla's loaded chunks around
			// the player — builds newly-in-range sections, frees out-of-range ones, per tick.
			if (rtInitDone && RtTerrain.ENABLED) {
				RtContext ctx = RtContext.currentOrNull();
				if (ctx != null) {
					RtTerrain.update(ctx);
				}
			}
		});

		// Vanilla's full render-state invalidation (LevelExtractor.allChanged(): dimension change via
		// setLevel, render-distance change, F3+A) — drop RT terrain residency so it rebuilds for the new
		// world. Fixes stale geometry persisting across an End→Overworld switch (coords alone aren't
		// world-unique). Resource reloads do NOT fire this; that path is handled separately.
		InvalidateRenderStateCallback.EVENT.register(RtTerrain::requestFullClear);

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			shutdownRt();
		});
	}

	private static void shutdownRt() {
		WorldRenderScaler.INSTANCE.destroy();
		RtWorkerPool.INSTANCE.shutdown(); // no-op if never started; stops worker threads on teardown
		if (!rtInitDone) {
			return;
		}

		RtContext ctx = RtContext.currentOrNull();
		if (ctx != null) {
			ctx.waitIdle();
			RtTerrain.shutdown(ctx);
			RtEntities.INSTANCE.shutdown();
		}
		RtComposite.INSTANCE.destroy();
		if (ctx != null) {
			ctx.destroy();
		}
		rtInitDone = false;
	}
}
