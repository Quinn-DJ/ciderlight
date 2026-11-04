package dev.ciderlight;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import dev.ciderlight.backend.ShaderReload;
import dev.ciderlight.backend.ShaderSetting;
import dev.ciderlight.gui.CiderlightSettingsScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Ciderlight's keys, listed under Controls > Key Binds > Ciderlight (the mappings are registered from OptionsMixin,
 * so they show up there, can be rebound and are saved in options.txt):
 *
 * <ul>
 *   <li>"Toggle Shaders" (default K): switches Ciderlight's shaders off and back on at the quality kept on the
 *       Ciderlight settings page (the on/off switch and the quality are separate settings). With hot apply the change
 *       happens at once; without it, it waits for the next start.</li>
 *   <li>"Ciderlight Settings" (default O): opens the Ciderlight page from the game.</li>
 * </ul>
 */
public final class CiderlightKeybinds {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** The category the mappings appear under in Controls > Key Binds. */
    private static final Identifier CATEGORY_ID = Identifier.fromNamespaceAndPath("ciderlight", "controls");
    private static KeyMapping.@Nullable Category category;
    private static @Nullable KeyMapping toggleShaders;
    private static @Nullable KeyMapping openSettings;

    private CiderlightKeybinds() {
    }

    /** The mappings; created before Options.load runs (OptionsMixin), so keys saved in options.txt are restored. */
    public static KeyMapping[] mappings() {
        return new KeyMapping[]{toggleShaders(), openSettings()};
    }

    public static KeyMapping toggleShaders() {
        KeyMapping mapping = toggleShaders;
        if (mapping == null) {
            mapping = new KeyMapping("key.ciderlight.toggle_shaders", InputConstants.Type.KEYBOARD, InputConstants.KEY_K, category());
            toggleShaders = mapping;
        }
        return mapping;
    }

    public static KeyMapping openSettings() {
        KeyMapping mapping = openSettings;
        if (mapping == null) {
            mapping = new KeyMapping("key.ciderlight.open_settings", InputConstants.Type.KEYBOARD, InputConstants.KEY_O, category());
            openSettings = mapping;
        }
        return mapping;
    }

    private static KeyMapping.Category category() {
        KeyMapping.Category created = category;
        if (created == null) {
            created = KeyMapping.Category.register(CATEGORY_ID);
            category = created;
        }
        return created;
    }

    /** Called once per client tick: handles the keys pressed in game. */
    public static void tick(final Minecraft minecraft) {
        KeyMapping toggle = toggleShaders;
        KeyMapping open = openSettings;
        if (toggle == null || open == null) {
            return;
        }
        if (ShaderReload.applying()) {
            drain(toggle);
            drain(open);
            return;
        }
        while (toggle.consumeClick()) {
            if (minecraft.gui.screen() == null) {
                toggleShaders(minecraft);
            }
        }
        while (open.consumeClick()) {
            if (minecraft.gui.screen() == null && minecraft.level != null && ShaderSetting.available()) {
                minecraft.setScreenAndShow(new CiderlightSettingsScreen(null, minecraft.options));
            }
        }
    }

    private static void drain(final KeyMapping mapping) {
        while (mapping.consumeClick()) {
            // presses during a reload are dropped rather than queued into another action
        }
    }

    private static void toggleShaders(final Minecraft minecraft) {
        if (!ShaderSetting.available()) {
            return;
        }
        boolean turnOn = ShaderSetting.current() == ShaderSetting.OFF;
        ShaderSetting.choose(turnOn ? ShaderSetting.ON : ShaderSetting.OFF);
        if (!ShaderReload.HOT_ENABLED) {
            LOGGER.info("Ciderlight: shaders will change after the next start");
            return;
        }
        if (!ShaderSetting.pending()) {
            return; // -Dciderlight.shaders fixes the switch
        }
        ShaderReload.apply(
            () -> LOGGER.info("Ciderlight: shaders turned {} by the toggle key", turnOn ? "on" : "off"),
            throwable -> LOGGER.warn("Ciderlight: the toggle-shaders key could not apply the change", throwable)
        );
    }
}
