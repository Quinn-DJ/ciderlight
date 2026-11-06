package dev.ciderlight.backend;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.cidercore.api.FrameExtension;
import dev.cidercore.api.MetalDeviceApi;
import dev.cidercore.api.PipelineTransformer;
import dev.cidercore.api.frame.CloudDraw;
import dev.cidercore.api.frame.EntityDraw;
import dev.cidercore.api.frame.TerrainDraw;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.jspecify.annotations.Nullable;
import dev.cidercore.backend.MetalTexture;

/**
 * Ciderlight's shader pipeline as a {@link FrameExtension}: the core backend talks to this interface instead of to
 * {@link MetalShaders} directly. Created by the {@code cidercore:frame_extension} entrypoint
 * ({@link CiderlightFrameExtensionFactory}) when the Metal device is created.
 *
 * <p>Owns the current {@link MetalShaders} generation (hot apply swaps it through {@link ShaderReload}) and the
 * classifier/patcher for the pipelines it compiles. The methods mirror the pre-split backend call sites one to one,
 * so the rendering behaviour is unchanged.</p>
 */
public final class CiderlightFrameExtension implements FrameExtension {
    private final MetalDeviceApi device;
    private Quality quality;
    /** The shader generation this frame renders with; hot apply swaps it (ShaderReload). */
    private volatile MetalShaders shaders;
    /** The pipeline classifier/patcher for the shaders generation in use; rebuilt with it. */
    private volatile CiderlightPipelineTransformer transformer;

    CiderlightFrameExtension(final MetalDeviceApi device) {
        this.device = device;
        Quality quality = Quality.of(device.deviceName());
        ShaderSettings settings = new ShaderSettings(
            ShaderSetting.initialEnabled(),
            quality == Quality.LOW,
            ShaderToggle.WAVING.enabled(),
            ShaderToggle.WATER_REFLECTIONS.enabled(),
            ShaderToggle.WATER_WAVES.enabled()
        );
        ShaderSetting.started(settings);
        ShaderQuality.started(quality == Quality.LOW);
        MetalShaders.commitSettings(settings);
        this.quality = quality;
        this.shaders = new MetalShaders(device, quality, settings);
        this.transformer = new CiderlightPipelineTransformer(this);
    }

    /** The current shader generation (ShaderReload reads and replaces it). */
    MetalShaders shaders() {
        return this.shaders;
    }

    /** The settings the current shader generation was built for. */
    ShaderSettings settings() {
        return this.shaders.settings();
    }

    /** The quality the current shader generation was built for. */
    Quality quality() {
        return this.quality;
    }

    /** The device this extension belongs to, for the pipeline transformer. */
    MetalDeviceApi device() {
        return this.device;
    }

    /**
     * Swaps in another shader generation: hot apply does this before rebuilding the pipelines. The transformer is
     * rebuilt too, so the pipelines compiled from now on are classified and patched for the new generation.
     */
    void replaceShaders(final MetalShaders shaders) {
        this.shaders = shaders;
        this.quality = shaders.quality();
        this.transformer = new CiderlightPipelineTransformer(this);
    }

    @Override
    public boolean isEnabled() {
        return MetalShaders.enabled();
    }

    @Override
    public boolean isMainPass(final RenderPassDescriptor descriptor) {
        return MetalShaders.enabled() && descriptor.depthAttachment() != null && descriptor.colorAttachments().size() == 1
            && "Main".equals(descriptor.label().get());
    }

    @Override
    public boolean isHandPass(final RenderPassDescriptor descriptor) {
        return MetalShaders.enabled() && "Item in hand".equals(descriptor.label().get());
    }

    // ---- main-pass lifecycle ----

    @Override
    public void prepareMain(final long frame, final long colorTexture, final long depthTexture, final int width, final int height) {
        this.shaders.prepareMain(frame, colorTexture, depthTexture, width, height);
    }

    @Override
    public void bindFrame(final long encoder) {
        this.shaders.bindFrame(encoder);
    }

    @Override
    public void bindHand(final long encoder, final int width, final int height) {
        this.shaders.bindHand(encoder, width, height);
    }

    @Override
    public void snapshotOpaque(final long frame) {
        this.shaders.snapshotOpaque(frame);
    }

    @Override
    public void finishMain(final long encoder) {
        this.shaders.finishMain(encoder);
    }

    @Override
    public void endMain(final long frame) {
        this.shaders.endMain(frame);
    }

    // ---- draw hooks ----

    @Override
    public boolean drawSky(final long encoder, final long depthState, final boolean hasDepth, final int[] indices, final Object[] uniforms) {
        return this.shaders.drawSky(encoder, depthState, hasDepth, indices, uniforms);
    }

    @Override
    public void bindCloudLight(final long encoder) {
        this.shaders.bindCloudLight(encoder);
    }

    @Override
    public void onTerrainDraw(final TerrainDraw draw) {
        this.shaders.capture(draw);
    }

    @Override
    public void onEntityDraw(final EntityDraw draw) {
        this.shaders.capture(draw);
    }

    @Override
    public void onCloudDraw(final CloudDraw draw) {
        this.shaders.capture(draw);
    }

    @Override
    public boolean isParticleAtlas(final Object texture) {
        return this.shaders.isParticleAtlas(texture);
    }

    @Override
    public boolean usesAtmosphere() {
        return MetalShaders.usesAtmosphere();
    }

    @Override
    public @Nullable LongOpenHashSet casterOnlySections() {
        return MetalShaders.casterOnlySections();
    }

    @Override
    public long sectionKey(final int x, final int y, final int z) {
        return MetalShaders.sectionKey(x, y, z);
    }

    // ---- render scale / hot apply ----

    @Override
    public float worldScale() {
        return this.shaders.renderScale();
    }

    @Override
    public void upscale(final GpuTexture source, final GpuTexture target, final int width, final int height) {
        this.shaders.upscale(this.device.currentFrame(), ((MetalTexture)source).handle(), ((MetalTexture)target).handle(), width, height);
    }

    @Override
    public boolean suppressWorldRender() {
        return ShaderReload.applying();
    }

    @Override
    public boolean classicTransparency() {
        return MetalShaders.enabled();
    }

    // ---- pipeline compile hooks ----

    @Override
    public void onPipelineCompiled() {
        ShaderReload.notePipelineCompiled();
    }

    @Override
    public @Nullable PipelineTransformer pipelineTransformer() {
        return this.transformer;
    }

    @Override
    public void onResourceReload() {
        // B3 hooks Ciderlight's resource reload here; nothing to do in B1.
    }

    @Override
    public void close() {
        this.shaders.destroy();
        MetalShaders.resetSharedState();
    }
}
