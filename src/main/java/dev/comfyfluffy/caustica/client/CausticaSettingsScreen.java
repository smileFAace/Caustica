package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * Dedicated Caustica options page (not embedded in vanilla Video Settings).
 * Hosts all runtime-tunable RT / emission / debug controls via {@link RtVideoOptions}.
 */
public final class CausticaSettingsScreen extends OptionsSubScreen {
	private static final Component TITLE = Component.translatable("caustica.options.screen.title");

	public CausticaSettingsScreen(Screen lastScreen, Options options) {
		super(lastScreen, options, TITLE);
	}

	@Override
	protected void addOptions() {
		this.list.addHeader(Component.translatable("caustica.options.rt.header.quality"));
		this.list.addSmall(RtVideoOptions.qualityOptions());

		this.list.addHeader(Component.translatable("caustica.options.rt.header.emission"));
		this.list.addSmall(RtVideoOptions.emissionOptions());

		this.list.addHeader(Component.translatable("caustica.options.rt.header.features"));
		this.list.addSmall(RtVideoOptions.featureOptions());

		this.list.addHeader(Component.translatable("caustica.options.rt.header.upscale"));
		this.list.addSmall(RtVideoOptions.upscaleOptions());

		this.list.addHeader(Component.translatable("caustica.options.rt.header.debug"));
		this.list.addSmall(RtVideoOptions.debugOptions());
	}

	@Override
	public void removed() {
		super.removed();
		CausticaConfig.save();
	}
}
