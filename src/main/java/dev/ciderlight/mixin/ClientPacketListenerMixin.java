package dev.ciderlight.mixin;

import dev.ciderlight.backend.ExplosionLights;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Queues a short light burst for every explosion the client hears about: TNT, creepers, end crystals, beds and so on
 * all arrive as this packet, so the shaders can light the world at the blast centre. A burning fuse itself only blinks
 * white and casts no light.
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
    @Inject(method = "handleExplosion", at = @At("HEAD"))
    private void ciderlight$explosionLight(final ClientboundExplodePacket packet, final CallbackInfo ci) {
        ExplosionLights.add(packet.center(), packet.radius());
    }
}
