package dev.ciderlight.gui;

import com.mojang.serialization.Codec;
import dev.ciderlight.backend.LightSetting;
import dev.ciderlight.backend.RenderScaleSetting;
import dev.ciderlight.backend.ShaderQuality;
import dev.ciderlight.backend.ShaderReload;
import dev.ciderlight.backend.ShaderSetting;
import dev.ciderlight.backend.ShaderToggle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

/**
 * Ciderlight's settings, opened from the Ciderlight button in Video Settings (VideoSettingsScreenMixin): the shaders
 * on/off switch (ShaderSetting), their quality (ShaderQuality), the effects that can be turned off one by one
 * (ShaderToggle) and the world's render scale. The switch and the quality are separate, so turning the shaders off
 * keeps the Low/High choice (and the toggle keybind, CiderlightKeybinds, relies on that). Everything here is saved to
 * config/ciderlight.properties.
 *
 * <p>Shadows, Ambient Occlusion and the render scale apply at once. With hot apply (ShaderReload) the shader switch,
 * the quality and the compiled effects (Waving Plants, Water Reflections, Water Waves) are staged until the Apply
 * button commits them and reloads the pipelines; a line above the options says what is staged and the button shows
 * the progress while the world is hidden. Done (or Esc) applies staged changes first instead of dropping them.
 * Without hot apply (-Dciderlight.hotReload=false) those options keep the old restart-to-apply behaviour, their
 * tooltips say so and the Apply button is not there. An option set by its -Dciderlight.* override is greyed out, as
 * the override wins.
 */
public class CiderlightSettingsScreen extends OptionsSubScreen {
    private static final Component TITLE = Component.translatable("ciderlight.settings.title");
    private static final Component RESTART = Component.translatable("ciderlight.note.restart").withStyle(ChatFormatting.RED);
    private static final Component APPLY = Component.translatable("ciderlight.note.apply").withStyle(ChatFormatting.YELLOW);
    private static final Component SHADERS_TOOLTIP = Component.translatable("ciderlight.tooltip.shaders");
    private static final Component QUALITY_TOOLTIP = Component.translatable("ciderlight.tooltip.quality");
    private static final Component RENDER_SCALE_TOOLTIP = Component.translatable("ciderlight.tooltip.render_scale");

    /** The options that only mean something with shaders on: greyed out while Shaders is Off. */
    private final List<OptionInstance<?>> effects = new ArrayList<>();
    /** The effects set by a -Dciderlight.* override: always greyed out. */
    private final Set<OptionInstance<?>> forced = new HashSet<>();
    private @Nullable OptionInstance<ShaderSetting> shadersOption;
    private @Nullable OptionInstance<ShaderQuality> qualityOption;
    private @Nullable StringWidget status;
    private @Nullable Button applyButton;
    private @Nullable Button doneButton;
    /** Whether the last Apply attempt failed, until the player changes something else. */
    private boolean lastApplyFailed;

    public CiderlightSettingsScreen(final Screen lastScreen, final Options options) {
        super(lastScreen, options, TITLE);
    }

    @Override
    protected void addOptions() {
        this.status = new StringWidget(Component.empty(), this.font);
        this.list.addBig(this.status);

        OptionInstance<ShaderSetting> shaders = new OptionInstance<>(
            "ciderlight.option.shaders",
            value -> tooltip(SHADERS_TOOLTIP, shadersNote(value)),
            (caption, value) -> Component.translatable(value.labelKey),
            new OptionInstance.Enum<>(List.of(ShaderSetting.values()), Codec.STRING.xmap(ShaderSetting::valueOf, ShaderSetting::name)),
            ShaderSetting.current(),
            value -> {
                this.lastApplyFailed = false;
                ShaderSetting.choose(value);
                this.updateEffects();
                this.refreshStatus();
            }
        );
        this.shadersOption = shaders;
        this.list.addBig(shaders);

        OptionInstance<ShaderQuality> quality = new OptionInstance<>(
            "ciderlight.option.quality",
            value -> tooltip(QUALITY_TOOLTIP, qualityNote(value)),
            (caption, value) -> Component.translatable(value.labelKey),
            new OptionInstance.Enum<>(List.of(ShaderQuality.values()), Codec.STRING.xmap(ShaderQuality::valueOf, ShaderQuality::name)),
            ShaderQuality.current(),
            value -> {
                this.lastApplyFailed = false;
                ShaderQuality.choose(value);
                this.refreshStatus();
            }
        );
        this.qualityOption = quality;
        this.list.addBig(quality);
        this.effects.clear();
        this.forced.clear();
        for (ShaderToggle toggle : ShaderToggle.values()) {
            Component description = Component.translatable(toggle.descriptionKey);
            OptionInstance<Boolean> option = OptionInstance.createBoolean(
                toggle.labelKey, value -> tooltip(description, toggleNote(toggle, value)), toggle.current(), value -> {
                    this.lastApplyFailed = false;
                    toggle.choose(value);
                    this.refreshStatus();
                }
            );
            this.effects.add(option);
            if (toggle.forced()) {
                this.forced.add(option);
            }
        }
        OptionInstance<RenderScaleSetting> renderScale = new OptionInstance<>(
            "ciderlight.option.render_scale",
            value -> tooltip(RENDER_SCALE_TOOLTIP, RenderScaleSetting.forced() ? forcedNote("renderScale") : null),
            (caption, value) -> Component.translatable(value.labelKey),
            new OptionInstance.Enum<>(List.of(RenderScaleSetting.values()), Codec.STRING.xmap(RenderScaleSetting::valueOf, RenderScaleSetting::name)),
            RenderScaleSetting.current(),
            RenderScaleSetting::choose
        );
        this.effects.add(renderScale);
        if (RenderScaleSetting.forced()) {
            this.forced.add(renderScale);
        }
        this.list.addSmall(this.effects.toArray(new OptionInstance<?>[0]));

        // The lighting sliders are live: MetalShaders reads the current value every frame, so they apply without the
        // Apply button and are saved to ciderlight.properties immediately.
        this.list.addHeader(Component.translatable("ciderlight.section.lighting"));
        List<OptionInstance<?>> lighting = new ArrayList<>();
        for (LightSetting setting : LightSetting.values()) {
            OptionInstance<Integer> slider = new OptionInstance<>(
                setting.labelKey,
                value -> tooltip(Component.translatable(setting.descriptionKey), setting.forced() ? forcedNote(setting.key) : null),
                (caption, value) -> Component.translatable("ciderlight.option.slider_value", caption, value),
                new OptionInstance.IntRange(setting.min, setting.max, true),
                setting.current(),
                value -> {
                    this.lastApplyFailed = false;
                    setting.choose(value);
                    this.refreshStatus();
                }
            );
            lighting.add(slider);
            this.effects.add(slider);
            if (setting.forced()) {
                this.forced.add(slider);
            }
        }
        this.list.addSmall(lighting.toArray(new OptionInstance<?>[0]));

        this.updateEffects();
        this.refreshStatus();
    }

    @Override
    protected void addFooter() {
        if (!ShaderReload.HOT_ENABLED) {
            super.addFooter();
            return;
        }
        this.applyButton = Button.builder(Component.translatable("ciderlight.button.apply"), button -> this.apply(null)).width(100).build();
        this.doneButton = Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).width(100).build();
        LinearLayout row = LinearLayout.horizontal().spacing(8);
        row.addChild(this.applyButton);
        row.addChild(this.doneButton);
        this.layout.addToFooter(row);
        this.refreshStatus();
    }

    /** Applies staged changes, then closes the screen; from Done/Esc, or null after a plain Apply. */
    private void apply(final @Nullable Runnable after) {
        if (ShaderReload.applying()) {
            return;
        }
        this.lastApplyFailed = false;
        ShaderReload.apply(() -> {
            this.refreshStatus();
            if (after != null) {
                after.run();
            }
        }, throwable -> {
            this.lastApplyFailed = true;
            this.refreshStatus();
        });
        this.refreshStatus();
    }

    @Override
    public void onClose() {
        if (ShaderReload.applying()) {
            return; // a reload is in flight: leaving now would drop the player mid-apply
        }
        if (ShaderReload.HOT_ENABLED && ShaderReload.pendingChanges()) {
            this.apply(this::closeToLast);
        } else {
            super.onClose();
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !ShaderReload.applying();
    }

    @Override
    public void tick() {
        if (ShaderReload.applying()) {
            this.refreshStatus();
        }
    }

    private void closeToLast() {
        super.onClose();
    }

    private void updateEffects() {
        boolean on = ShaderSetting.current() == ShaderSetting.ON;
        boolean idle = !ShaderReload.applying();
        if (this.shadersOption != null) {
            AbstractWidget widget = this.list.findOption(this.shadersOption);
            if (widget != null) {
                widget.active = !ShaderSetting.forced() && idle;
            }
        }
        if (this.qualityOption != null) {
            AbstractWidget widget = this.list.findOption(this.qualityOption);
            if (widget != null) {
                widget.active = on && idle && !ShaderQuality.forced();
            }
        }
        for (OptionInstance<?> option : this.effects) {
            AbstractWidget widget = this.list.findOption(option);
            if (widget != null) {
                widget.active = on && idle && !this.forced.contains(option);
            }
        }
    }

    private void refreshStatus() {
        boolean applying = ShaderReload.applying();
        if (this.applyButton != null) {
            this.applyButton.active = ShaderReload.HOT_ENABLED && !applying && ShaderReload.pendingChanges();
            this.applyButton.setMessage(applying ? Component.translatable("ciderlight.button.applying")
                : Component.translatable("ciderlight.button.apply"));
        }
        if (this.doneButton != null) {
            this.doneButton.active = !applying;
        }
        this.updateEffects();
        if (this.status != null) {
            this.status.setMessage(this.statusMessage());
        }
    }

    private Component statusMessage() {
        if (ShaderReload.applying()) {
            int compiled = Math.min(ShaderReload.compiled(), ShaderReload.total());
            int total = ShaderReload.total();
            MutableComponent progress;
            if (total <= 0) {
                progress = Component.translatable("ciderlight.status.applying.generic");
            } else if (ShaderReload.slow()) {
                progress = Component.translatable("ciderlight.status.applying.slow", compiled, total);
            } else {
                progress = Component.translatable("ciderlight.status.applying", compiled, total);
            }
            return progress.withStyle(ChatFormatting.WHITE);
        }
        if (this.lastApplyFailed) {
            return Component.translatable("ciderlight.status.failed").withStyle(ChatFormatting.RED);
        }
        if (ShaderReload.HOT_ENABLED) {
            if (ShaderReload.pendingChanges()) {
                return Component.translatable("ciderlight.status.staged").withStyle(ChatFormatting.YELLOW);
            }
            return Component.translatable("ciderlight.status.applied").withStyle(ChatFormatting.GREEN);
        }
        if (ShaderSetting.restartRequired() || ShaderToggle.anyRestartRequired()) {
            return Component.translatable("ciderlight.status.restart").withStyle(ChatFormatting.RED);
        }
        return Component.translatable("ciderlight.status.matching").withStyle(ChatFormatting.GREEN);
    }

    private @Nullable Component shadersNote(final ShaderSetting value) {
        if (ShaderSetting.forced()) {
            return forcedNote("shaders");
        }
        if (ShaderReload.HOT_ENABLED) {
            return value != ShaderSetting.running() ? APPLY : null;
        }
        return value != ShaderSetting.running() ? RESTART : null;
    }

    private @Nullable Component qualityNote(final ShaderQuality value) {
        if (ShaderQuality.forced()) {
            return forcedNote("quality");
        }
        if (ShaderReload.HOT_ENABLED) {
            return value != ShaderQuality.running() ? APPLY : null;
        }
        return value != ShaderQuality.running() ? RESTART : null;
    }

    private @Nullable Component toggleNote(final ShaderToggle toggle, final boolean value) {
        if (toggle.forced()) {
            return forcedNote(toggle.key);
        }
        if (toggle.live) {
            return null;
        }
        if (ShaderReload.HOT_ENABLED) {
            return value != toggle.enabled() ? APPLY : null;
        }
        return value != toggle.enabled() ? RESTART : null;
    }

    private static Component forcedNote(final String key) {
        return Component.translatable("ciderlight.note.forced", "ciderlight." + key).withStyle(ChatFormatting.YELLOW);
    }

    /** The description, under a note (how a change applies, or the override that fixes it) if there is one. */
    private static Tooltip tooltip(final Component description, final @Nullable Component note) {
        List<Component> lines = new ArrayList<>();
        if (note != null) {
            lines.add(note);
            lines.add(CommonComponents.EMPTY);
        }
        lines.add(description);
        return Tooltip.create(CommonComponents.joinLines(lines));
    }
}
