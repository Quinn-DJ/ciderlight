package dev.ciderlight.backend;

import com.mojang.logging.LogUtils;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Applies ShaderSetting and the compiled ShaderToggle effects without restarting the game: the settings page stages a
 * choice (see ShaderSetting/ShaderToggle), then its Apply button calls {@link #apply}, which
 *
 * <ol>
 *   <li>swaps in a new MetalShaders generation for the staged settings,</li>
 *   <li>rebuilds every render pipeline through the game's own ShaderManager reload path (the one resource packs use),
 *       so MetalDevice.compilePipeline builds them from the new shader sources,</li>
 *   <li>keeps the previous generation until the game installed the rebuilt pipelines, and rolls back if it could not.</li>
 * </ol>
 *
 * While it runs, GameRendererMixin skips renderLevel: the previous pipelines are still the installed ones, but the
 * settings have already moved on, so a mixed frame is avoided by not drawing the world. The GUI keeps rendering, which
 * is what shows the settings page (and its progress) during the reload.
 *
 * A failed apply keeps the previous shaders and leaves the staged values in place, so the player can retry. If the
 * direct ShaderManager reload fails before any pipeline was compiled, the whole resource-pack reload is tried once as
 * a fallback (-Dciderlight.reloadMode=shader|packs|auto, auto by default).
 */
public final class ShaderReload {
    /** -Dciderlight.hotReload=false restores the restart-to-apply behaviour. */
    public static final boolean HOT_ENABLED = Boolean.parseBoolean(System.getProperty("ciderlight.hotReload", "true"));
    private static final Logger LOGGER = LogUtils.getLogger();
    /** -Dciderlight.reloadMode: auto (ShaderManager, falling back to a full resource reload), shader, or packs. */
    private static final String MODE = System.getProperty("ciderlight.reloadMode", "auto");
    private static final long SLOW_MS = 30_000L;

    private static boolean applying;
    private static long startedAt;
    private static int compiled;
    private static int total;
    private static boolean slowWarned;
    private static @Nullable MetalShaders previousShaders;
    private static @Nullable ShaderSettings previousSettings;
    private static @Nullable Consumer<Throwable> onFailure;
    private static @Nullable Runnable onSuccess;

    private ShaderReload() {
    }

    /** Whether a reload is under way: GameRendererMixin skips the world draw, and the settings page shows progress. */
    public static boolean applying() {
        return applying;
    }

    /** Pipelines compiled by the reload so far, and how many the game should compile (for the settings page). */
    public static int compiled() {
        return compiled;
    }

    public static int total() {
        return total;
    }

    /** Whether the reload has been running longer than expected (the settings page says so; the reload keeps going). */
    public static boolean slow() {
        return applying && Util.getMillis() - startedAt > SLOW_MS;
    }

    /** True while ShaderSetting or a compiled effect has a staged value for the Apply button. */
    public static boolean pendingChanges() {
        return ShaderSetting.pending() || ShaderToggle.anyPending();
    }

    /** Called from MetalDevice.compilePipeline for every pipeline the reload rebuilds. */
    static void notePipelineCompiled() {
        compiled++;
    }

    /**
     * Applies the staged settings. Must be called on the render thread (the settings page is); the callbacks run on it
     * too, after the reload finished or failed.
     */
    public static void apply(final Runnable success, final Consumer<Throwable> failure) {
        if (!HOT_ENABLED) {
            return;
        }
        if (applying) {
            return;
        }
        MetalDevice device = MetalDevice.current;
        if (device == null) {
            failure.accept(new IllegalStateException("Ciderlight is not the active renderer"));
            return;
        }
        Quality quality = resolveQuality(device.shaders().quality());
        ShaderSettings target = new ShaderSettings(
            ShaderSetting.current() != ShaderSetting.OFF,
            quality == Quality.LOW,
            ShaderToggle.WAVING.current(),
            ShaderToggle.WATER_REFLECTIONS.current(),
            ShaderToggle.WATER_WAVES.current()
        );
        ShaderSettings current = device.settings();
        if (!current.needsPipelineReload(target)) {
            // Nothing reaches a compiled shader source (a choice while the shaders are off, or one made moot by a -D
            // override): commit it and save the config without rebuilding anything.
            ShaderSetting.commit();
            ShaderToggle.commitAll();
            success.run();
            return;
        }

        applying = true;
        startedAt = Util.getMillis();
        compiled = 0;
        total = RenderPipelines.requiredPipelines().size() + RenderPipelines.optionalPipelines().size();
        slowWarned = false;
        previousShaders = device.shaders();
        previousSettings = previousShaders.settings();
        onSuccess = success;
        onFailure = failure;
        LOGGER.info("Ciderlight: applying shaders {} ({} pipelines)", describe(target), total);

        MetalShaders next;
        try {
            next = new MetalShaders(device, quality, target);
        } catch (RuntimeException e) {
            finishFailure(device, e);
            return;
        }
        device.replaceShaders(next);
        MetalShaders.commitSettings(target);
        reload(game()).whenComplete((ignored, throwable) -> game().execute(() -> {
            if (throwable == null) {
                finishSuccess(device, previousShaders);
            } else {
                finishFailure(device, throwable);
            }
        }));
    }

    /** Called once per client tick from MinecraftMixin: warns once when a reload is unusually slow. */
    public static void tick() {
        if (applying && !slowWarned && Util.getMillis() - startedAt > SLOW_MS) {
            slowWarned = true;
            LOGGER.warn("Ciderlight: applying shaders is still running after {} ms; waiting for the shader compiler",
                Util.getMillis() - startedAt);
        }
    }

    private static Minecraft game() {
        return Minecraft.getInstance();
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
        ShaderSetting shown = ShaderSetting.current();
        if (shown == ShaderSetting.LOW) {
            return Quality.LOW;
        }
        if (shown == ShaderSetting.HIGH) {
            return Quality.HIGH;
        }
        return current;
    }

    /**
     * Rebuilds the pipelines through ShaderManager's reload: the same compile-and-swap path resource packs use, without
     * reloading the packs themselves. It compiles RenderPipelines.requiredPipelines/optionalPipelines through our
     * MetalDevice.compilePipeline, and applies the new PipelineCache on the render thread.
     */
    private static CompletableFuture<Void> reload(final Minecraft minecraft) {
        String mode = MODE.toLowerCase(java.util.Locale.ROOT);
        if (mode.equals("packs")) {
            return minecraft.reloadResourcePacks();
        }
        CompletableFuture<Void> direct;
        try {
            ShaderManager shaderManager = minecraft.getShaderManager();
            PreparableReloadListener.SharedState sharedState = new PreparableReloadListener.SharedState(minecraft.getResourceManager());
            PreparableReloadListener.PreparationBarrier barrier = CompletableFuture::completedFuture;
            direct = shaderManager.reload(sharedState, Util.backgroundExecutor(), barrier, minecraft);
        } catch (RuntimeException e) {
            direct = CompletableFuture.failedFuture(e);
        }
        if (mode.equals("shader")) {
            return direct;
        }
        // auto: the direct path may not even start (it is public, but not an entry point the game documents). If no
        // pipeline was compiled yet, the whole resource-pack reload is tried once; that path is the game's own.
        return direct.handle((ignored, throwable) -> {
            if (throwable == null) {
                return CompletableFuture.<Void>completedFuture(null);
            }
            if (compiled == 0) {
                LOGGER.warn("Ciderlight: the shader-only reload could not start ({}); reloading the resource packs instead",
                    throwable.toString());
                return minecraft.reloadResourcePacks();
            }
            return CompletableFuture.<Void>failedFuture(throwable);
        }).thenCompose(java.util.function.Function.identity());
    }

    private static void finishSuccess(final MetalDevice device, final @Nullable MetalShaders previous) {
        // Match the side effects a full resource reload has: failed post effects are reset.
        game().gameRenderer.onResourceManagerReload(game().getResourceManager());
        ShaderSetting.commit();
        ShaderToggle.commitAll();
        if (previous != null) {
            device.encoder().queueForDestroy(previous);
        }
        long elapsed = Util.getMillis() - startedAt;
        applying = false;
        previousShaders = null;
        previousSettings = null;
        Runnable success = onSuccess;
        onSuccess = null;
        onFailure = null;
        LOGGER.info("Ciderlight: applied shaders in {} ms ({} pipelines)", elapsed, compiled);
        if (success != null) {
            success.run();
        }
    }

    private static void finishFailure(final MetalDevice device, final Throwable throwable) {
        MetalShaders failed = device.shaders();
        if (previousShaders != null && failed != previousShaders) {
            // The new generation was already committed (the reload started); put the old one back and release it later.
            device.replaceShaders(previousShaders);
            MetalShaders.commitSettings(previousSettings != null ? previousSettings : previousShaders.settings());
            device.encoder().queueForDestroy(failed);
        }
        long elapsed = Util.getMillis() - startedAt;
        applying = false;
        slowWarned = false;
        previousShaders = null;
        previousSettings = null;
        onSuccess = null;
        Consumer<Throwable> failure = onFailure;
        onFailure = null;
        LOGGER.warn("Ciderlight: could not apply shaders after {} ms, keeping the previous ones: {}", elapsed, throwable.toString());
        if (failure != null) {
            failure.accept(throwable);
        }
    }

    private static String describe(final ShaderSettings settings) {
        return (settings.shaders() ? (settings.lowQuality() ? "low" : "high") : "off")
            + " waving=" + settings.waving() + " reflections=" + settings.waterReflections() + " waves=" + settings.waterWaves();
    }
}
