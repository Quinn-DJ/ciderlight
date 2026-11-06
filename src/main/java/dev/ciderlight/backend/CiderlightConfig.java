package dev.ciderlight.backend;

import dev.cidercore.api.CidercoreApi;
import dev.cidercore.api.ConfigFile;
import org.jspecify.annotations.Nullable;

/**
 * config/ciderlight.properties: what the Ciderlight settings page (CiderlightSettingsScreen) saves.
 *
 * <p>The file handling now lives in the core ({@link ConfigFile}); this class keeps the shader module's file name and
 * the two read/write helpers the settings classes use. Saving a key rewrites the file at once (so the next session
 * starts with it), while {@link #saved(String)} is what the current session started with.</p>
 */
final class CiderlightConfig {
	private static final ConfigFile FILE = CidercoreApi.config("ciderlight");

	private CiderlightConfig() {
	}

	/** The value saved when the game started, or null if there was none. */
	static @Nullable String saved(final String key) {
		return FILE.saved(key);
	}

	static void set(final String key, final String value) {
		FILE.set(key, value);
	}
}
