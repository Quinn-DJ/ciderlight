package dev.ciderlight.mixin;

import dev.ciderlight.ShaderKeybind;
import dev.ciderlight.backend.ShaderQuality;
import dev.ciderlight.backend.ShaderReload;
import dev.ciderlight.backend.ShaderSetting;
import dev.ciderlight.backend.ShaderToggle;
import java.util.Arrays;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds Ciderlight's "Toggle Shaders" key to the game's key mappings before Options reads the saved ones, so it appears
 * under Controls > Key Binds > Ciderlight, can be rebound and is saved in options.txt like the vanilla keys. Also makes
 * Video Settings show its "restart required" notice when a Ciderlight setting differs from what is running, unless hot
 * apply is on (then the settings page applies them itself).
 */
@Mixin(Options.class)
public class OptionsMixin {
    @Shadow
    @Final
    @Mutable
    private KeyMapping[] keyMappings;

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;load()V"))
    private void ciderlight$addToggleKey(final CallbackInfo ci) {
        KeyMapping toggle = ShaderKeybind.toggle();
        KeyMapping[] mappings = Arrays.copyOf(this.keyMappings, this.keyMappings.length + 1);
        mappings[mappings.length - 1] = toggle;
        this.keyMappings = mappings;
    }

    @Inject(method = "isRestartRequiredToApplyVideoSettings", at = @At("RETURN"), cancellable = true)
    private void ciderlight$shadersNeedRestart(final CallbackInfoReturnable<Boolean> cir) {
        if (!ShaderReload.HOT_ENABLED && (ShaderSetting.restartRequired() || ShaderQuality.restartRequired()
            || ShaderToggle.anyRestartRequired())) {
            cir.setReturnValue(true);
        }
    }
}
