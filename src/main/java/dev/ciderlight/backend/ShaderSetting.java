package dev.ciderlight.backend;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The player's choice of shaders on the Ciderlight settings page (CiderlightSettingsScreen): off, low or high quality.
 * Without a saved choice, Quality picks low for A-series GPUs and the plain M1 to M4, and high otherwise. The choice
 * is saved to config/ciderlight.properties (CiderlightConfig). With hot apply (ShaderReload) choosing it only stages
 * a pending value, which the settings page's Apply button commits once the pipelines were rebuilt; otherwise it
 * takes effect at the next start. -Dciderlight.shaders=false and -Dciderlight.quality=low|high still override it.
 */
public enum ShaderSetting {
    OFF("Off"),
    LOW("Low"),
    HIGH("High");

    private static final @Nullable ShaderSetting SAVED = load();
    /** What this session runs with: what the game started with, then whatever Apply committed. */
    private static ShaderSetting running = initialRunning();
    /** Staged on the settings page while hot apply is on; null when nothing is pending. */
    private static volatile @Nullable ShaderSetting chosen;

    public final String label;

    ShaderSetting(final String label) {
        this.label = label;
    }

    /** The choice saved when the game started, or null to let the GPU decide. */
    static @Nullable ShaderSetting saved() {
        return SAVED;
    }

    /** Whether -Dciderlight.shaders enables the shaders whatever config says. */
    static boolean initialEnabled() {
        String override = System.getProperty("ciderlight.shaders");
        if (override != null) {
            return !"false".equals(override);
        }
        return SAVED != OFF;
    }

    /** Whether the Metal backend is running, so the setting means something. */
    public static boolean available() {
        return Quality.chosen() != null;
    }

    /** What this session is running with. */
    public static ShaderSetting running() {
        return running;
    }

    /** What the settings page shows: the staged value if there is one, otherwise what is running. */
    public static ShaderSetting current() {
        return chosen != null ? chosen : running;
    }

    /** Whether -Dciderlight.shaders fixes it, so a change on the settings page changes nothing. */
    public static boolean forced() {
        return System.getProperty("ciderlight.shaders") != null;
    }

    /** True while a choice waits for the Apply button (hot apply only). */
    public static boolean pending() {
        return !forced() && chosen != null && chosen != running;
    }

    public static boolean restartRequired() {
        return !ShaderReload.HOT_ENABLED && pending();
    }

    public static void choose(final ShaderSetting setting) {
        if (forced() || setting == current()) {
            return;
        }
        chosen = setting;
        if (!ShaderReload.HOT_ENABLED) {
            CiderlightConfig.set("shaders", setting.name().toLowerCase(Locale.ROOT));
        }
    }

    /** Commits the staged choice: called by the Apply button once the pipelines were rebuilt. */
    static void commit() {
        ShaderSetting staged = chosen;
        chosen = null;
        if (staged != null) {
            running = staged;
            CiderlightConfig.set("shaders", staged.name().toLowerCase(Locale.ROOT));
        }
    }

    /** Called by MetalDevice with what the game actually started with. */
    static void started(final ShaderSettings settings) {
        running = settings.shaders() ? (settings.lowQuality() ? LOW : HIGH) : OFF;
        chosen = null;
    }

    /** The value the game runs with before its device exists: enough for the settings page (which is hidden then). */
    private static ShaderSetting initialRunning() {
        if (!initialEnabled()) {
            return OFF;
        }
        return SAVED == LOW ? LOW : HIGH;
    }

    private static @Nullable ShaderSetting load() {
        String saved = CiderlightConfig.saved("shaders");
        if (saved == null) {
            return null;
        }
        String value = saved.trim().toUpperCase(Locale.ROOT);
        for (ShaderSetting setting : values()) {
            if (setting.name().equals(value)) {
                return setting;
            }
        }
        return null;
    }
}
