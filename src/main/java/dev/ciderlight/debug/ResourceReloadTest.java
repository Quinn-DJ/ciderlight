package dev.ciderlight.debug;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/**
 * Dev-only resource-reload exercise: {@code -Dciderlight.testResourceReload=<ticks>} (dev client:
 * {@code -PtestResourceReload=<ticks>}) waits that many client ticks in a world, triggers the game's resource-pack
 * reload (the same path as F3+T, which rebuilds shaders and pipelines) and logs OK/FAILED, then quits.
 *
 * <p>Off by default; zero cost in normal play.</p>
 */
public final class ResourceReloadTest {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int AT_TICK = Integer.getInteger("ciderlight.testResourceReload", 0);
	private static final long TIMEOUT_NS = 120_000_000_000L;
	private static int ticks;
	private static boolean started;
	private static boolean done;
	private static boolean stopped;
	private static long startedAt;

	private ResourceReloadTest() {
	}

	public static void tick(final Minecraft minecraft) {
		if (AT_TICK <= 0 || minecraft.level == null || minecraft.player == null) {
			return;
		}
		ticks++;
		if (!started && ticks >= AT_TICK) {
			started = true;
			startedAt = System.nanoTime();
			LOGGER.info("Ciderlight: RESOURCE RELOAD TEST starting");
			minecraft.reloadResourcePacks().whenComplete((ignored, throwable) -> minecraft.execute(() -> {
				if (throwable == null) {
					LOGGER.info("Ciderlight: RESOURCE RELOAD TEST OK");
				} else {
					LOGGER.warn("Ciderlight: RESOURCE RELOAD TEST FAILED", throwable);
				}
				done = true;
			}));
			return;
		}
		if (!started || stopped) {
			return;
		}
		if (!done && System.nanoTime() - startedAt > TIMEOUT_NS) {
			LOGGER.warn("Ciderlight: RESOURCE RELOAD TEST TIMEOUT");
			stopped = true;
			minecraft.stop();
			return;
		}
		if (done) {
			stopped = true;
			LOGGER.info("Ciderlight: RESOURCE RELOAD TEST finished, stopping");
			minecraft.stop();
		}
	}
}
