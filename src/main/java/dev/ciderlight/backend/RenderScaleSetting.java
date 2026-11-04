package dev.ciderlight.backend;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The Render Scale choice on the Ciderlight settings page (CiderlightSettingsScreen): the world's resolution as a
 * fraction of the window's (RenderScale), or Auto, the quality's own (two thirds on Low, full on High). A change
 * applies immediately: GameRendererMixin sizes the world target from RenderScale every frame. Saved to
 * config/ciderlight.properties as "renderScale" (auto, or the fraction); -Dciderlight.renderScale still overrides it.
 */
public enum RenderScaleSetting {
    AUTO(0.0F, "Auto"),
    HALF(0.5F, "50%"),
    TWO_THIRDS(0.67F, "67%"),
    THREE_QUARTERS(0.75F, "75%"),
    MOST(0.85F, "85%"),
    FULL(1.0F, "100%");

    private static final RenderScaleSetting SAVED = load();
    /** The -Dciderlight.renderScale override, clamped, or null when there is none. */
    private static final @Nullable Float FORCED = System.getProperty("ciderlight.renderScale") != null
        ? Math.clamp(Float.parseFloat(System.getProperty("ciderlight.renderScale")), 0.25F, 1.0F) : null;
    /** What this session runs with: the saved choice, updated as soon as the player picks another. */
    private static RenderScaleSetting running = SAVED;

    /** The fraction in each direction, or 0 for the quality's own. */
    final float scale;
    public final String label;

    RenderScaleSetting(final float scale, final String label) {
        this.scale = scale;
        this.label = label;
    }

    /** What this session runs with. */
    public static RenderScaleSetting running() {
        return running;
    }

    /** The value the settings page shows: the choice already applies, so there is nothing pending. */
    public static RenderScaleSetting current() {
        return running;
    }

    /** Whether -Dciderlight.renderScale sets it, so a choice here changes nothing until that is removed. */
    public static boolean forced() {
        return FORCED != null;
    }

    /**
     * The world's resolution as a fraction of the window's: the -D override, the player's choice, or `auto` (the
     * quality's own fraction) for Auto. Read every frame by RenderScale.
     */
    static float effective(final float auto) {
        if (FORCED != null) {
            return FORCED;
        }
        return running.scale == 0.0F ? auto : Math.clamp(running.scale, 0.25F, 1.0F);
    }

    public static void choose(final RenderScaleSetting setting) {
        if (forced() || setting == running) {
            return;
        }
        running = setting;
        CiderlightConfig.set("renderScale", setting == AUTO ? "auto" : Float.toString(setting.scale));
    }

    private static RenderScaleSetting load() {
        String saved = CiderlightConfig.saved("renderScale");
        if (saved == null || saved.trim().toLowerCase(Locale.ROOT).equals("auto")) {
            return AUTO;
        }
        try {
            float value = Float.parseFloat(saved.trim());
            for (RenderScaleSetting setting : values()) {
                if (setting != AUTO && Math.abs(setting.scale - value) < 0.005F) {
                    return setting;
                }
            }
        } catch (NumberFormatException ignored) {
        }
        return AUTO;
    }
}
