package dev.upscaler.mixin;

import dev.upscaler.rt.RtTerrain;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forwards vanilla's block-dirty signal to the RT renderer so edited sections (and their boundary
 * neighbours) re-extract. In 26.2 the dirty methods live on {@link LevelExtractor}. We hook the two
 * <em>block-change</em> entry points and let {@link RtTerrain#markBlocksDirty} expand to sections:
 *
 * <ul>
 *   <li>{@code setBlockDirty(BlockPos, boolean)} — the primary path
 *       ({@code ClientLevel.sendBlockUpdated → blockChanged → setBlockDirty}). Vanilla expands this to
 *       a 3³ block area; we receive the single block at HEAD and do the same expansion ourselves.</li>
 *   <li>{@code setBlocksDirty(int×6)} — multi-block changes (explosions, etc.) and the
 *       {@code setBlockDirty(pos, old, new)} render-shape path.</li>
 * </ul>
 *
 * <p>We deliberately do <em>not</em> hook {@code setSectionDirty}: lighting-only invalidations
 * ({@code ClientChunkCache.onLightUpdate}) route straight through it, and we ray-trace lighting, so a
 * light change never alters our geometry. Hooking the block entry points keeps us off that churn.
 *
 * <p>These are real vanilla methods, so the injects work without Sodium (the intended config). If
 * Sodium is present it {@code @Overwrite}s the bodies, but a HEAD inject coexists with an overwrite.
 */
@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {
    @Inject(method = "setBlockDirty(Lnet/minecraft/core/BlockPos;Z)V", at = @At("HEAD"))
    private void upscaler$rtBlockDirty(BlockPos pos, boolean playerChanged, CallbackInfo ci) {
        RtTerrain.markBlocksDirty(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
    }

    @Inject(method = "setBlocksDirty(IIIIII)V", at = @At("HEAD"))
    private void upscaler$rtBlocksDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CallbackInfo ci) {
        RtTerrain.markBlocksDirty(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
