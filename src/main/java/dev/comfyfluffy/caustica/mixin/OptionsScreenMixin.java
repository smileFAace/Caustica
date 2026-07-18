package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.comfyfluffy.caustica.client.CausticaSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a full-width {@code Caustica...} button on the main Options screen that opens
 * {@link CausticaSettingsScreen}. Uses MixinExtras {@code @Local} by type so 26.2's
 * LinearLayout + GridLayout mix does not break LVT ordinal capture.
 */
@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin {
	@Shadow
	private Options options;

	@Inject(
			method = "init",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/gui/layouts/HeaderAndFooterLayout;addToContents(Lnet/minecraft/client/gui/layouts/LayoutElement;)Lnet/minecraft/client/gui/layouts/LayoutElement;",
					ordinal = 0))
	private void caustica$addSettingsButton(CallbackInfo ci, @Local GridLayout.RowHelper rows) {
		OptionsScreen self = (OptionsScreen) (Object) this;
		rows.addChild(Button.builder(
						Component.translatable("caustica.options.screen.button"),
						b -> Minecraft.getInstance().setScreenAndShow(new CausticaSettingsScreen(self, this.options)))
				.width(310)
				.build(), 2);
	}
}
