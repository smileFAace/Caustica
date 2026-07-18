package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.client.CausticaSettingsScreen;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Video Settings stays vanilla-shaped under RT: drop superseded quality options, and offer a single
 * button into the dedicated {@link CausticaSettingsScreen} instead of dumping every RT slider here.
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin {
	@Shadow
	private static OptionInstance<?>[] qualityOptions(Options options) {
		throw new AssertionError("mixin stub");
	}

	@Redirect(
		method = "addOptions",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/options/VideoSettingsScreen;qualityOptions(Lnet/minecraft/client/Options;)[Lnet/minecraft/client/OptionInstance;"))
	private OptionInstance<?>[] caustica$filterQualityOptions(Options options) {
		OptionInstance<?>[] base = qualityOptions(options);
		if (!CausticaConfig.Rt.ENABLED.value()) {
			return base;
		}
		List<OptionInstance<?>> kept = new ArrayList<>(base.length);
		for (OptionInstance<?> option : base) {
			if (option == options.ambientOcclusion() || option == options.entityShadows()) {
				continue;
			}
			kept.add(option);
		}
		return kept.toArray(OptionInstance<?>[]::new);
	}

	@Inject(method = "addOptions", at = @At("TAIL"))
	private void caustica$linkToSettingsScreen(CallbackInfo ci) {
		if (!CausticaConfig.Rt.ENABLED.value()) {
			return;
		}
		OptionsList list = ((OptionsSubScreenAccessor) (Object) this).getList();
		if (list == null) {
			return;
		}
		VideoSettingsScreen self = (VideoSettingsScreen) (Object) this;
		list.addHeader(Component.translatable("caustica.options.rt.header"));
		list.addBig(Button.builder(
						Component.translatable("caustica.options.screen.button"),
						b -> {
							Minecraft mc = Minecraft.getInstance();
							mc.setScreenAndShow(new CausticaSettingsScreen(self, mc.options));
						})
				.build());
	}
}
