package dev.ciderlight;

import com.mojang.logging.LogUtils;
import dev.ciderlight.debug.HotApplyTest;
import dev.ciderlight.debug.ResourceReloadTest;
import dev.cidercore.api.CidercoreApi;
import dev.cidercore.api.CidercoreContext;
import dev.cidercore.api.CidercoreModule;
import dev.cidercore.api.Translations;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/**
 * The shader module's entry in the core-driven lifecycle ({@code cidercore:module} entrypoint, order 200). The core
 * discovers it, initialises it after the backend is up, ticks it and shuts it down in reverse order.
 */
public final class CiderlightModule implements CidercoreModule {
	private static final Logger LOGGER = LogUtils.getLogger();

	@Override
	public String id() {
		return "ciderlight";
	}

	@Override
	public int order() {
		return 200;
	}

	@Override
	public void initialise(final CidercoreContext context) {
		// i18n: hand the core our lang files so it can merge them into the game's language (no Fabric API needed).
		if (CidercoreApi.supports(CidercoreApi.CAPABILITY_TRANSLATIONS)) {
			Translations.register(id(), CiderlightModule.class);
		}
		if (context.apiVersion() != CidercoreApi.API_VERSION) {
			LOGGER.warn("Ciderlight: core API version {} does not match the compiled-against version {}; disabling the shader module",
				context.apiVersion(), CidercoreApi.API_VERSION);
			return;
		}
		LOGGER.info("Ciderlight: shader module initialised (core API {})", context.apiVersion());
	}

	@Override
	public void tick() {
		HotApplyTest.tick(Minecraft.getInstance());
		ResourceReloadTest.tick(Minecraft.getInstance());
	}

	@Override
	public void shutdown() {
		LOGGER.info("Ciderlight: shader module shut down");
	}
}
