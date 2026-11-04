package dev.ciderlight.backend;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Whether Ciderlight's shaders run: the on/off switch on the Ciderlight settings page (CiderlightSettingsScreen). The
 * quality is a separate setting (ShaderQuality), so turning the shaders off does not lose the Low/High choice: the
 * keybind (ShaderKeybind) turns this switch on and off and keeps the quality. Saved to
 * config/ciderlight.properties as "shaders" = on|off; older files stored off/low/high there and are read as on (the
 * quality comes from ShaderQuality, which reads the old low/high value as a fallback). With hot apply (ShaderReload)
 * choosing it only stages a pending value, which the settings page's Apply button commits once the pipelines were
 * rebuilt; otherwise it takes effect at the next start.
 */
public enum ShaderSetting {
    OFF("Off"),
    ON("On");

    private static final @Nullable ShaderSetting SAVED = load();
    /** What this session runs with: what the game started with, then whatever Apply committed. */
    private static ShaderSetting running = initialRunning();
    /** Staged on the settings page while hot apply is on; null when nothing is pending. */
    private static volatile @Nullable ShaderSetting chosen;

    public final String label;

    ShaderSetting(final String label) {
        this.label = label;
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
            CiderlightConfig.set("shaders", setting == OFF ? "off" : "on");
        }
    }

    /** Commits the staged choice: called by the Apply button once the pipelines were rebuilt. */
    static void commit() {
        ShaderSetting staged = chosen;
        chosen = null;
        if (staged != null) {
            running = staged;
            CiderlightConfig.set("shaders", staged == OFF ? "off" : "on");
        }
    }

    /** Called by MetalDevice with what the game actually started with. */
    static void started(final ShaderSettings settings) {
        running = settings.shaders() ? ON : OFF;
        chosen = null;
    }

    /** The value the game runs with before its device exists: enough for the settings page (which is hidden then). */
    private static ShaderSetting initialRunning() {
        return initialEnabled() ? ON : OFF;
    }

    /** Reads the new on/off value, or the old off/low/high one; null lets the default (on) stand. */
    private static @Nullable ShaderSetting load() {
        String saved = CiderlightConfig.saved("shaders");
        if (saved == null) {
            return null;
        }
        String value = saved.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "off", "false" -> OFF;
            case "on", "true", "low", "high" -> ON;
            default -> null;
        };
    }
}
