package dev.ciderlight.backend;

import java.util.Objects;

/**
 * A snapshot of the settings that are compiled into Ciderlight's shader pipelines: whether the shaders run at all,
 * their quality, and the effects that the Metal shader sources are built with (ShaderToggle's compiled ones).
 *
 * <p>Shadows and Ambient Occlusion are read every frame (they are live), and the render scale is resolved per frame
 * (RenderScaleSetting), so neither is part of this snapshot. {@link #needsPipelineReload(ShaderSettings)} says whether
 * moving to another snapshot requires recompiling the render pipelines, which is what makes a setting apply without
 * restarting the game.
 *
 * <p>Kept free of Minecraft classes so tests/java can check the reload rules on their own.
 */
public record ShaderSettings(boolean shaders, boolean lowQuality, boolean waving, boolean waterReflections, boolean waterWaves) {
    /** What the game runs with before its settings are read: shaders off, High, every effect on. */
    public static ShaderSettings defaults() {
        return new ShaderSettings(false, false, true, true, true);
    }

    /**
     * Whether switching from this snapshot to {@code next} needs the pipelines recompiled. Turning the shaders on or
     * off always does; while they are off, the quality and the effect toggles are not compiled into anything, so those
     * changes wait (harmlessly) until the shaders are turned on.
     */
    public boolean needsPipelineReload(final ShaderSettings next) {
        Objects.requireNonNull(next, "next");
        if (!this.shaders && !next.shaders) {
            return false;
        }
        return this.shaders != next.shaders
            || this.lowQuality != next.lowQuality
            || this.waving != next.waving
            || this.waterReflections != next.waterReflections
            || this.waterWaves != next.waterWaves;
    }
}
