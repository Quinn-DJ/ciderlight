package dev.ciderlight.backend;

import org.jspecify.annotations.Nullable;

/**
 * The brightness and light colour sliders on the Ciderlight settings page (CiderlightSettingsScreen), in percent
 * (100% is the tuned look). They are live: MetalShaders reads the current value every frame, once into the light
 * tuning fields of FrameData (ambient, torch, shadow, exposure, saturation) and once when the per-frame sun and sky
 * light colours are prepared (sunlight and ambient), so moving a slider changes the picture immediately without
 * rebuilding any pipeline. Explosion Light scales the short bursts queued by ExplosionLights on top of that.
 *
 * <p>Saved to config/ciderlight.properties under its key; -Dciderlight.KEY=<percent> overrides it and greys the
 * slider out. The keys are new, so a config written by an older Ciderlight simply uses the defaults.</p>
 */
public enum LightSetting {
    AMBIENT("ambientLight", "ciderlight.option.ambient_light", "ciderlight.tooltip.ambient_light", 100, 0, 300),
    SUNLIGHT("sunlight", "ciderlight.option.sunlight", "ciderlight.tooltip.sunlight", 100, 0, 200),
    SHADOW_DARKNESS("shadowDarkness", "ciderlight.option.shadow_darkness", "ciderlight.tooltip.shadow_darkness", 100, 0, 200),
    TORCH_LIGHT("torchLight", "ciderlight.option.torch_light", "ciderlight.tooltip.torch_light", 100, 0, 200),
    EXPOSURE("exposure", "ciderlight.option.exposure", "ciderlight.tooltip.exposure", 100, 25, 200),
    SATURATION("saturation", "ciderlight.option.saturation", "ciderlight.tooltip.saturation", 100, 0, 200),
    EXPLOSION("explosionLight", "ciderlight.option.explosion_light", "ciderlight.tooltip.explosion_light", 100, 0, 200);

    /** The property key, both in ciderlight.properties and as -Dciderlight.KEY. */
    public final String key;
    /** Translation key of the slider's label. */
    public final String labelKey;
    /** Translation key of the slider's tooltip. */
    public final String descriptionKey;
    public final int min;
    public final int max;
    public final int defaultValue;

    /** What this session runs with, in percent. */
    private volatile int running;
    /** Set by -Dciderlight.KEY, which wins over the settings page. */
    private final boolean forced;

    LightSetting(final String key, final String labelKey, final String descriptionKey, final int defaultValue,
                 final int min, final int max) {
        this.key = key;
        this.labelKey = labelKey;
        this.descriptionKey = descriptionKey;
        this.min = min;
        this.max = max;
        this.defaultValue = defaultValue;
        Integer forcedValue = parse(System.getProperty("ciderlight." + key));
        this.forced = forcedValue != null;
        this.running = Math.clamp(forcedValue != null ? forcedValue : saved(key, defaultValue), min, max);
    }

    /** What the setting runs with, in percent. */
    public int current() {
        return this.running;
    }

    /** What the shaders use: 1.0 is the tuned look. */
    public float scale() {
        return this.running / 100.0F;
    }

    /** Whether -Dciderlight.KEY sets it, so a choice here changes nothing until that is removed. */
    public boolean forced() {
        return this.forced;
    }

    /**
     * Applies and saves a new value. Live: the next frame already uses it, so there is no staging or Apply button.
     */
    public void choose(final int percent) {
        if (this.forced) {
            return;
        }
        int value = Math.clamp(percent, this.min, this.max);
        if (value != this.running) {
            this.running = value;
            CiderlightConfig.set(this.key, Integer.toString(value));
        }
    }

    private static int saved(final String key, final int defaultValue) {
        String saved = CiderlightConfig.saved(key);
        Integer value = parse(saved);
        return value != null ? value : defaultValue;
    }

    private static @Nullable Integer parse(final @Nullable String text) {
        if (text == null) {
            return null;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
