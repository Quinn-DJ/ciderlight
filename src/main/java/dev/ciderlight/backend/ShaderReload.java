package dev.ciderlight.backend;

import com.mojang.logging.LogUtils;
import dev.cidercore.api.CidercoreApi;
import dev.cidercore.api.PipelineReload;
import dev.cidercore.backend.MetalDevice;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Applies ShaderSetting and the compiled ShaderToggle effects without restarting the game: the settings page stages a
 * choice (see ShaderSetting/ShaderToggle), then its Apply button calls {@link #apply}. The game-side reload/rollback
 * state machine now lives in the core ({@link PipelineReload}); this class only builds the new shader generation and
 * turns it into the core's swap/rollback callbacks.
 *
 * <p>While a reload runs, GameRendererMixin (core) skips renderLevel: the previous pipelines are still the installed
 * ones, but the settings have already moved on, so a mixed frame is avoided by not drawing the world. The GUI keeps
 * rendering, which is what shows the settings page (and its progress) during the reload.</p>
 *
 * <p>A failed apply keeps the previous shaders and leaves the staged values in place, so the player can retry. If the
 * direct ShaderManager reload fails before any pipeline was compiled, the core's automatic mode falls back to a full
 * resource-pack reload once ({@code -Dciderlight.reloadMode=shader|packs|auto}, auto by default).</p>
 */
public final class ShaderReload {
	/** -Dciderlight.hotReload=false restores the restart-to-apply behaviour. */
	public static final boolean HOT_ENABLED = CidercoreApi.pipelineReload().hotApplyEnabled();
	private static final Logger LOGGER = LogUtils.getLogger();

	private ShaderReload() {
	}

	private static PipelineReload reload() {
		return CidercoreApi.pipelineReload();
	}

	/** Whether a reload is under way: GameRendererMixin skips the world draw, and the settings page shows progress. */
	public static boolean applying() {
		return reload().applying();
	}

	/** Pipelines compiled by the reload so far, and how many the game should compile (for the settings page). */
	public static int compiled() {
		return reload().compiled();
	}

	public static int total() {
		return reload().total();
	}

	/** Whether the reload has been running longer than expected (the settings page says so; the reload keeps going). */
	public static boolean slow() {
		return reload().slow();
	}

	/** True while a shader setting or a compiled effect has a staged value for the Apply button. */
	public static boolean pendingChanges() {
		return ShaderSetting.pending() || ShaderQuality.pending() || ShaderToggle.anyPending();
	}

	/** Called from the core for every pipeline it compiles while a reload is in flight. */
	static void notePipelineCompiled() {
		reload().notePipelineCompiled();
	}

	/**
	 * Applies the staged settings. Must be called on the render thread (the settings page is); the callbacks run on it
	 * too, after the reload finished or failed.
	 */
	public static void apply(final Runnable success, final Consumer<Throwable> failure) {
		if (!HOT_ENABLED || applying()) {
			return;
		}
		MetalDevice device = MetalDevice.current;
		if (device == null) {
			failure.accept(new IllegalStateException("Ciderlight is not the active renderer"));
			return;
		}
		CiderlightFrameExtension extension = activeExtension(device);
		if (extension == null) {
			failure.accept(new IllegalStateException("Ciderlight's shader module is not installed"));
			return;
		}
		Quality quality = resolveQuality(extension.quality());
		ShaderSettings target = new ShaderSettings(
			ShaderSetting.current() == ShaderSetting.ON,
			quality == Quality.LOW,
			ShaderToggle.WAVING.current(),
			ShaderToggle.WATER_REFLECTIONS.current(),
			ShaderToggle.WATER_WAVES.current()
		);
		ShaderSettings current = extension.settings();
		if (!current.needsPipelineReload(target)) {
			// Nothing reaches a compiled shader source (a choice while the shaders are off, or one made moot by a -D
			// override): commit it and save the config without rebuilding anything.
			ShaderSetting.commit();
			ShaderQuality.commit();
			ShaderToggle.commitAll();
			success.run();
			return;
		}

		MetalShaders previous = extension.shaders();
		ShaderSettings previousSettings = previous.settings();
		MetalShaders next;
		try {
			next = new MetalShaders(device, quality, target);
		} catch (RuntimeException e) {
			failure.accept(e);
			return;
		}
		LOGGER.info("Ciderlight: applying shaders {}", describe(target));
		PipelineReload.PipelineGeneration generation = new PipelineReload.PipelineGeneration() {
			@Override
			public void swapIn() {
				extension.replaceShaders(next);
				MetalShaders.commitSettings(target);
			}

			@Override
			public void swapBack() {
				extension.replaceShaders(previous);
				MetalShaders.commitSettings(previousSettings);
			}

			@Override
			public void commitSettings() {
				ShaderSetting.commit();
				ShaderQuality.commit();
				ShaderToggle.commitAll();
			}

			@Override
			public void releasePrevious() {
				device.releaseAfterSubmit(previous::destroy);
			}

			@Override
			public void releaseNew() {
				device.releaseAfterSubmit(next::destroy);
			}
		};
		reload().apply(generation, success, failure);
	}

	/** Called once per client tick from the core's Minecraft mixin: warns once when a reload is unusually slow. */
	public static void tick() {
		reload().tick();
	}

	private static @Nullable CiderlightFrameExtension activeExtension(final MetalDevice device) {
		return device.frameExtension() instanceof CiderlightFrameExtension extension ? extension : null;
	}

	/** The quality the staged settings ask for: the -D override wins, then the picked Low/High, then the current one. */
	private static Quality resolveQuality(final Quality current) {
		String forced = System.getProperty("ciderlight.quality", "").toLowerCase(java.util.Locale.ROOT);
		if (forced.equals("low")) {
			return Quality.LOW;
		}
		if (forced.equals("high")) {
			return Quality.HIGH;
		}
		if (ShaderSetting.current() == ShaderSetting.OFF) {
			return current; // switching the shaders off: the quality only matters to Auto render scale while on
		}
		return ShaderQuality.current() == ShaderQuality.LOW ? Quality.LOW : Quality.HIGH;
	}

	private static String describe(final ShaderSettings settings) {
		return (settings.shaders() ? "on" : "off") + ", " + (settings.lowQuality() ? "low" : "high")
			+ ", waving=" + settings.waving() + ", reflections=" + settings.waterReflections() + ", waves=" + settings.waterWaves();
	}
}
