package dev.ciderlight.gui;

import dev.ciderlight.backend.ShaderSetting;
import dev.cidercore.api.SettingsPage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The hub-facing view of {@link CiderlightSettingsScreen}: the core adds the "Ciderlight..." button to Video Settings
 * and opens this page, exactly where Ciderlight's own VideoSettingsScreenMixin used to.
 */
public final class CiderlightSettingsPage implements SettingsPage {
	private static final Component TITLE = Component.translatable("ciderlight.settings.title");
	private static final Component BUTTON = Component.translatable("ciderlight.settings.button");
	private static final Component TOOLTIP = Component.translatable("ciderlight.settings.button.tooltip");

	@Override
	public Component title() {
		return TITLE;
	}

	@Override
	public Component buttonLabel() {
		return BUTTON;
	}

	@Override
	public Component buttonTooltip() {
		return TOOLTIP;
	}

	@Override
	public boolean available() {
		return ShaderSetting.available();
	}

	@Override
	public void open(final Minecraft minecraft, final @Nullable Screen parent) {
		minecraft.setScreenAndShow(new CiderlightSettingsScreen(parent, minecraft.options));
	}
}
