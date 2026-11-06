package dev.ciderlight;

import dev.ciderlight.backend.ShaderQuality;
import dev.ciderlight.backend.ShaderReload;
import dev.ciderlight.backend.ShaderSetting;
import dev.ciderlight.backend.ShaderToggle;
import dev.ciderlight.gui.CiderlightSettingsPage;
import dev.cidercore.api.KeybindGroup;
import dev.cidercore.api.SettingsContributor;
import dev.cidercore.api.SettingsPage;
import java.util.List;

/**
 * Contributes the shader module to the core settings hub: the Ciderlight settings page (the button in Video Settings),
 * the K/O key bindings and the restart-required notice when hot apply is off.
 */
public final class CiderlightSettingsContributor implements SettingsContributor {
	@Override
	public String moduleId() {
		return "ciderlight";
	}

	@Override
	public List<SettingsPage> pages() {
		return List.of(new CiderlightSettingsPage());
	}

	@Override
	public List<KeybindGroup> keybinds() {
		return List.of(CiderlightKeybinds.GROUP);
	}

	@Override
	public boolean restartRequired() {
		return !ShaderReload.HOT_ENABLED
			&& (ShaderSetting.restartRequired() || ShaderQuality.restartRequired() || ShaderToggle.anyRestartRequired());
	}
}
