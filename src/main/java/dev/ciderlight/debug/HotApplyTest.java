package dev.ciderlight.debug;

import com.mojang.logging.LogUtils;
import dev.ciderlight.backend.ShaderReload;
import dev.ciderlight.backend.ShaderSetting;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/**
 * Dev-only hot-apply exercise: {@code -Dciderlight.testHotApply=<ticks>} (dev client: {@code -PtestHotApply=<ticks>})
 * waits that many client ticks in a world, toggles the shaders and runs the real {@link ShaderReload#apply} path,
 * then logs OK/FAILED and quits. Used to verify the core-owned pipeline reload machinery without clicking the GUI.
 *
 * <p>Off by default; zero cost in normal play.</p>
 */
public final class HotApplyTest {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int AT_TICK = Integer.getInteger("ciderlight.testHotApply", 0);
	private static final long TIMEOUT_NS = 90_000_000_000L;
	private static int ticks;
	private static boolean started;
	private static boolean done;
	private static boolean stopped;
	private static long startedAt;

	private HotApplyTest() {
	}

	public static void tick(final Minecraft minecraft) {
		if (AT_TICK <= 0 || minecraft.level == null || minecraft.player == null) {
			return;
		}
		ticks++;
		if (!started && ticks >= AT_TICK) {
			started = true;
			startedAt = System.nanoTime();
			boolean turnOn = ShaderSetting.current() != ShaderSetting.ON;
			ShaderSetting.choose(turnOn ? ShaderSetting.ON : ShaderSetting.OFF);
			LOGGER.info("Ciderlight: HOT APPLY TEST starting (turning shaders {})", turnOn ? "on" : "off");
			ShaderReload.apply(
				() -> {
					LOGGER.info("Ciderlight: HOT APPLY TEST OK");
					done = true;
				},
				throwable -> {
					LOGGER.warn("Ciderlight: HOT APPLY TEST FAILED", throwable);
					done = true;
				}
			);
			return;
		}
		if (!started || stopped) {
			return;
		}
		if (!done && System.nanoTime() - startedAt > TIMEOUT_NS) {
			LOGGER.warn("Ciderlight: HOT APPLY TEST TIMEOUT (reload never finished)");
			stopped = true;
			minecraft.stop();
			return;
		}
		if (done) {
			stopped = true;
			LOGGER.info("Ciderlight: HOT APPLY TEST finished, stopping");
			minecraft.stop();
		}
	}
}
