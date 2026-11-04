package dev.ciderlight.backend;

import org.jspecify.annotations.Nullable;

/**
 * The on/off effects on the Ciderlight settings page (CiderlightSettingsScreen), all on by default. Each is saved to
 * config/ciderlight.properties under its key. Shadows and Ambient Occlusion are live: MetalShaders checks them every
 * frame, so choosing them applies at once. Waving Plants, Water Reflections and Water Waves are compiled into the
 * pipelines: with hot apply (ShaderReload) a choice is staged until the Apply button commits it and rebuilds the
 * pipelines; without it, like the Shaders choice, it takes effect at the next start. -Dciderlight.KEY=true|false
 * still overrides the saved value.
 */
public enum ShaderToggle {
    SHADOWS("shadows", "Shadows", true,
        "The sun and moon cast shadows, and light shafts form in the haze. Off skips the shadow maps, the biggest single "
            + "cost on the GPU, and lights everything as if it stood in the open."),
    WAVING("waving", "Waving Plants", false, "Leaves, grass, flowers and crops sway in the wind."),
    WATER_REFLECTIONS("waterReflections", "Water Reflections", false,
        "Water mirrors the hills, trees and buildings around it. Off: water reflects the sky only, which costs less."),
    WATER_WAVES("waterWaves", "Water Waves", false,
        "Waves roll across lakes and the sea and run down waterfalls. Off: the water lies calm (rain still ripples it)."),
    AMBIENT_OCCLUSION("ao", "Ambient Occlusion", true, "Soft shade in corners, under ledges and between blocks.");

    /** The property key, both in ciderlight.properties and as -Dciderlight.KEY. */
    public final String key;
    public final String label;
    public final String description;
    /** Applies as soon as it is chosen, not through the Apply button. */
    public final boolean live;
    /** What this session runs with. */
    private volatile boolean running;
    /** Set by -Dciderlight.KEY, which wins over the settings page. */
    private final boolean forced;
    /** Staged on the settings page: the Apply button commits it (hot apply only). */
    private volatile @Nullable Boolean chosen;

    ShaderToggle(final String key, final String label, final boolean live, final String description) {
        this.key = key;
        this.label = label;
        this.live = live;
        this.description = description;
        String override = System.getProperty("ciderlight." + key);
        this.forced = override != null;
        this.running = !"false".equals(override != null ? override : CiderlightConfig.saved(key));
    }

    /**
     * Whether this session runs with the effect: live ones as they are chosen, the compiled ones only after the Apply
     * button (or at the next start without hot apply).
     */
    public boolean enabled() {
        return this.live ? this.current() : this.running;
    }

    /** What the settings page shows: the staged value if there is one, otherwise what is running. */
    public boolean current() {
        return this.chosen != null ? this.chosen : this.running;
    }

    /** Whether -Dciderlight.KEY sets it, so a choice here changes nothing until that is removed. */
    public boolean forced() {
        return this.forced;
    }

    /** True while a choice waits for the Apply button (hot apply only). */
    public boolean pending() {
        return this.chosen != null && this.chosen != this.running;
    }

    public boolean restartRequired() {
        return !this.live && !this.forced && !ShaderReload.HOT_ENABLED && this.pending();
    }

    public void choose(final boolean on) {
        if (this.live) {
            if (on != this.running) {
                this.running = on;
                CiderlightConfig.set(this.key, Boolean.toString(on));
            }
            this.chosen = null;
            return;
        }
        if (on == this.current()) {
            return;
        }
        this.chosen = on;
        if (!ShaderReload.HOT_ENABLED) {
            CiderlightConfig.set(this.key, Boolean.toString(on));
        }
    }

    /** Commits the staged choice: called by the Apply button once the pipelines were rebuilt. */
    void commit() {
        Boolean staged = this.chosen;
        this.chosen = null;
        if (staged != null) {
            this.running = staged;
            CiderlightConfig.set(this.key, Boolean.toString(staged));
        }
    }

    /** True while any compiled effect (Waving, Water Reflections or Water Waves) waits for the Apply button. */
    public static boolean anyPending() {
        for (ShaderToggle toggle : values()) {
            if (!toggle.live && toggle.pending()) {
                return true;
            }
        }
        return false;
    }

    public static boolean anyRestartRequired() {
        for (ShaderToggle toggle : values()) {
            if (toggle.restartRequired()) {
                return true;
            }
        }
        return false;
    }
}
