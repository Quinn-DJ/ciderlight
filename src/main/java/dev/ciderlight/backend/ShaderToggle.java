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
    SHADOWS("shadows", "ciderlight.option.shadows", true, "ciderlight.tooltip.shadows"),
    WAVING("waving", "ciderlight.option.waving", false, "ciderlight.tooltip.waving"),
    WATER_REFLECTIONS("waterReflections", "ciderlight.option.water_reflections", false, "ciderlight.tooltip.water_reflections"),
    WATER_WAVES("waterWaves", "ciderlight.option.water_waves", false, "ciderlight.tooltip.water_waves"),
    AMBIENT_OCCLUSION("ao", "ciderlight.option.ao", true, "ciderlight.tooltip.ao");

    /** The property key, both in ciderlight.properties and as -Dciderlight.KEY. */
    public final String key;
    /** Translation key of the option's label. */
    public final String labelKey;
    /** Translation key of the option's tooltip. */
    public final String descriptionKey;
    /** Applies as soon as it is chosen, not through the Apply button. */
    public final boolean live;
    /** What this session runs with. */
    private volatile boolean running;
    /** Set by -Dciderlight.KEY, which wins over the settings page. */
    private final boolean forced;
    /** Staged on the settings page: the Apply button commits it (hot apply only). */
    private volatile @Nullable Boolean chosen;

    ShaderToggle(final String key, final String labelKey, final boolean live, final String descriptionKey) {
        this.key = key;
        this.labelKey = labelKey;
        this.live = live;
        this.descriptionKey = descriptionKey;
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
        return !this.forced && this.chosen != null && this.chosen != this.running;
    }

    public boolean restartRequired() {
        return !this.live && !this.forced && !ShaderReload.HOT_ENABLED && this.pending();
    }

    public void choose(final boolean on) {
        if (this.forced) {
            return;
        }
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

    /** Commits every staged compiled effect (live ones are never staged). Called by the Apply button. */
    public static void commitAll() {
        for (ShaderToggle toggle : values()) {
            toggle.commit();
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
