package dev.ciderlight.backend;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The shader quality on the Ciderlight settings page (CiderlightSettingsScreen): Low or High. Kept apart from the
 * on/off switch (ShaderSetting), so turning the shaders off and on again keeps the chosen quality. Without a saved
 * choice Quality picks low for A-series GPUs and the plain M1 to M4, and high otherwise. Saved to
 * config/ciderlight.properties as "shaderQuality" = low|high; older files stored low/high in "shaders" and are read as
 * a fallback. With hot apply (ShaderReload) choosing it only stages a pending value for the Apply button; otherwise
 * it takes effect at the next start. -Dciderlight.quality=low|high overrides it.
 */
public enum ShaderQuality {
    LOW("ciderlight.value.low"),
    HIGH("ciderlight.value.high");

    private static final @Nullable ShaderQuality SAVED = load();
    /** What this session runs with, once the device exists (before that, only what the page shows). */
    private static ShaderQuality running = SAVED != null ? SAVED : HIGH;
    /** Staged on the settings page while hot apply is on; null when nothing is pending. */
    private static volatile @Nullable ShaderQuality chosen;

    /** Translation key of the label shown on the settings page. */
    public final String labelKey;

    ShaderQuality(final String labelKey) {
        this.labelKey = labelKey;
    }

    /** The choice saved when the game started, or null to let the GPU decide. */
    static @Nullable ShaderQuality saved() {
        return SAVED;
    }

    /** What this session is running with. */
    public static ShaderQuality running() {
        return running;
    }

    /** What the settings page shows: the staged value if there is one, otherwise what is running. */
    public static ShaderQuality current() {
        return chosen != null ? chosen : running;
    }

    /** Whether -Dciderlight.quality fixes it, so a choice here changes nothing. */
    public static boolean forced() {
        return System.getProperty("ciderlight.quality") != null;
    }

    /** True while a choice waits for the Apply button (hot apply only). */
    public static boolean pending() {
        return !forced() && chosen != null && chosen != running;
    }

    public static boolean restartRequired() {
        return !ShaderReload.HOT_ENABLED && pending();
    }

    public static void choose(final ShaderQuality quality) {
        if (forced() || quality == current()) {
            return;
        }
        chosen = quality;
        if (!ShaderReload.HOT_ENABLED) {
            CiderlightConfig.set("shaderQuality", quality.name().toLowerCase(Locale.ROOT));
        }
    }

    /** Commits the staged choice: called by the Apply button once the pipelines were rebuilt. */
    static void commit() {
        ShaderQuality staged = chosen;
        chosen = null;
        if (staged != null) {
            running = staged;
            CiderlightConfig.set("shaderQuality", staged.name().toLowerCase(Locale.ROOT));
        }
    }

    /** Called by MetalDevice with the quality the game actually started with. */
    static void started(final boolean low) {
        running = low ? LOW : HIGH;
        chosen = null;
    }

    /** Reads "shaderQuality", falling back to the old "shaders" = low/high value; null lets the GPU decide. */
    private static @Nullable ShaderQuality load() {
        String saved = CiderlightConfig.saved("shaderQuality");
        if (saved == null) {
            String legacy = CiderlightConfig.saved("shaders");
            if (legacy != null) {
                String value = legacy.trim().toLowerCase(Locale.ROOT);
                if (value.equals("low") || value.equals("high")) {
                    saved = value;
                }
            }
        }
        if (saved == null) {
            return null;
        }
        return switch (saved.trim().toLowerCase(Locale.ROOT)) {
            case "low" -> LOW;
            case "high" -> HIGH;
            default -> null;
        };
    }
}
