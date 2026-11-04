package dev.ciderlight;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import dev.ciderlight.backend.ShaderReload;
import dev.ciderlight.backend.ShaderSetting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The "Toggle Shaders" key (default F10, rebindable under Controls > Key Binds > Ciderlight): switches Ciderlight's
 * shaders off and back on at the quality kept on the Ciderlight settings page. The on/off switch and the quality are
 * separate settings (ShaderSetting, ShaderQuality), so the quality survives turning the shaders off. With hot apply
 * the change happens at once (the world is hidden while the pipelines are rebuilt); without it, it waits for the next
 * start like the settings page. The mapping is registered from OptionsMixin so the game lists, saves and rebinds it.
 */
public final class ShaderKeybind {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static @Nullable KeyMapping toggle;

    private ShaderKeybind() {
    }

    /** The mapping; created before Options.load runs (OptionsMixin), so a key saved in options.txt is restored. */
    public static KeyMapping toggle() {
        KeyMapping mapping = toggle;
        if (mapping == null) {
            KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("ciderlight", "shaders"));
            mapping = new KeyMapping("key.ciderlight.toggle_shaders", InputConstants.Type.KEYBOARD, InputConstants.KEY_F10, category);
            toggle = mapping;
        }
        return mapping;
    }

    /** Called once per client tick: toggles the shaders when the key was pressed in game. */
    public static void tick(final Minecraft minecraft) {
        KeyMapping mapping = toggle;
        if (mapping == null) {
            return;
        }
        if (ShaderReload.applying()) {
            while (mapping.consumeClick()) {
                // a press during a reload is dropped rather than queued into another toggle
            }
            return;
        }
        while (mapping.consumeClick()) {
            if (minecraft.gui.screen() == null) {
                toggleShaders(minecraft);
            }
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
