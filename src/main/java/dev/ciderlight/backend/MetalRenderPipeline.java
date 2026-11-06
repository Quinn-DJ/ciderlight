package dev.ciderlight.backend;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.util.ShaderCompileException;
import dev.cidercore.api.PipelineFlags;
import dev.cidercore.api.PipelinePlan;
import dev.cidercore.api.PipelineTransformer;
import dev.cidercore.api.ShaderPatch;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public final class MetalRenderPipeline implements BackendRenderPipeline, Destroyable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DEPTH_FORMAT = MetalConst.format(GpuFormat.D32_FLOAT);

    private final MetalDevice device;
    private final String name;
    private final boolean blobShadow;
    private final long vertexLibrary;
    private final String vertexEntry;
    private final long fragmentLibrary;
    private final String fragmentEntry;
    private final int[] baseDescriptor;
    private final List<BindGroupLayout.UniformDescription> uniforms;
    private final int[] uniformStages;
    private final int pushConstantStages;
    private final long depthState;
    private final boolean cull;
    private final boolean wireframe;
    private final float depthBias;
    private final float depthSlopeScale;
    private final int primitive;
    private final boolean fan;
    private final int terrainKind;
    private final int @org.jspecify.annotations.Nullable [] skyUniforms;
    private final int @org.jspecify.annotations.Nullable [] terrainUniforms;
    private final int @org.jspecify.annotations.Nullable [] shadowDescriptor;
    private final int @org.jspecify.annotations.Nullable [] entityUniforms;
    private final int @org.jspecify.annotations.Nullable [] cloudUniforms;
    private final boolean litParticle;
    private final boolean emissiveLayer;
    /** Terrain pipelines: stride of the per-section instance data and where the section's block position is in it (-1: unknown). */
    private final int sectionStride;
    private final int sectionPosOffset;
    /** Pipeline states by variant (variant()): with or without a depth attachment, for the world or the hand. */
    private final long[] states = new long[4];
    /** Variants started in the background when the pipeline was compiled (prebuildVariants), taken by stateFor. */
    @SuppressWarnings("unchecked")
    private final java.util.concurrent.CompletableFuture<Long>[] variantBuilds = new java.util.concurrent.CompletableFuture[4];
    /** Fragment library with the metal shine for items held in first person (0: this pipeline has none). */
    private long handFragmentLibrary;
    private boolean closed;

    private MetalRenderPipeline(
        final MetalDevice device, final @Nullable PipelinePlan plan, final BackendRenderPipeline.CreateInfo info, final long vertexLibrary,
        final String vertexEntry, final long fragmentLibrary, final String fragmentEntry, final int[] uniformStages, final int pushConstantStages
    ) {
        this.device = device;
        this.name = info.name();
        this.blobShadow = plan != null && plan.blobShadow();
        this.vertexLibrary = vertexLibrary;
        this.vertexEntry = vertexEntry;
        this.fragmentLibrary = fragmentLibrary;
        this.fragmentEntry = fragmentEntry;
        this.uniforms = info.uniforms();
        this.uniformStages = uniformStages;
        this.pushConstantStages = pushConstantStages;
        int alphaMode = plan != null ? plan.alphaMode() : PipelineFlags.ALPHA_VANILLA;
        this.baseDescriptor = describe(info, alphaMode);
        DepthStencilState depth = info.depthStencilState();
        this.depthState = depth != null ? device.depthState(depth.depthTest().ordinal(), depth.writeDepth()) : device.depthState(0, false);
        this.depthBias = depth != null ? depth.depthBiasConstant() : 0.0F;
        this.depthSlopeScale = depth != null ? depth.depthBiasScaleFactor() : 0.0F;
        this.cull = info.cull();
        this.wireframe = info.polygonMode() == PolygonMode.WIREFRAME;
        this.primitive = MetalConst.primitive(info.primitiveTopology());
        this.fan = info.primitiveTopology() == PrimitiveTopology.TRIANGLE_FAN;
        this.skyUniforms = plan == null ? null : plan.skyUniforms();
        this.terrainKind = plan == null ? PipelineFlags.KIND_NONE : plan.terrainKind();
        this.terrainUniforms = plan == null ? null : plan.terrainUniforms();
        this.litParticle = plan != null && plan.litParticle();
        int sectionStride = -1;
        int sectionPosOffset = -1;
        if (this.terrainKind != PipelineFlags.KIND_NONE) {
            for (BackendRenderPipeline.CreateInfo.VertexBuffer vb : info.vertexBuffers()) {
                if (vb.bufferSlot() == 1 && vb.stepRate() == 1) {
                    sectionStride = vb.stride();
                }
            }
            for (BackendRenderPipeline.CreateInfo.AttribBinding attrib : info.attribBindings()) {
                // ChunkPosition (terrain.metal, attribute 4): three 32-bit ints.
                if (attrib.location() == 4 && attrib.bufferSlot() == 1 && attrib.format() == GpuFormat.RGB32_SINT) {
                    sectionPosOffset = attrib.offset();
                }
            }
            if (sectionStride <= 0 || sectionPosOffset < 0) {
                LOGGER.warn("Ciderlight: unexpected chunk section layout in {}, its shadow casters are not culled", info.name());
                sectionStride = -1;
            }
        }
        this.sectionStride = sectionStride;
        this.sectionPosOffset = sectionPosOffset;
        this.emissiveLayer = plan != null && plan.emissiveLayer();
        this.shadowDescriptor = plan == null ? null : plan.shadowDescriptor();
        this.entityUniforms = plan == null ? null : plan.entityUniforms();
        this.cloudUniforms = plan == null ? null : plan.cloudUniforms();
    }

    /** -Dciderlight.dumpShaders=<directory>: write every translated Metal shader there, for writing patches like CiderlightPipelineTransformer's. */
    static void dumpShader(final String pipeline, final String stage, final String source) {
        String directory = System.getProperty("ciderlight.dumpShaders");
        if (directory == null) {
            return;
        }
        try {
            java.nio.file.Path path = java.nio.file.Path.of(directory, pipeline.replaceAll("[^A-Za-z0-9_]+", "_") + "." + stage + ".metal");
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.writeString(path, source);
        } catch (java.io.IOException e) {
            LOGGER.warn("Ciderlight: could not dump shader for {}", pipeline, e);
        }
    }

    public static MetalRenderPipeline compile(
        final MetalDevice device, final BackendRenderPipeline.CreateInfo info, final @Nullable PipelineTransformer transformer
    ) {
        int uniformCount = info.uniforms().size();
        if (uniformCount > MetalShaderCompiler.PUSH_CONSTANT_BUFFER) {
            throw new IllegalStateException("Pipeline " + info.name() + " has more uniforms than the Metal backend supports");
        }
        PipelinePlan plan = transformer == null ? null : transformer.plan(device, info);
        int[] uniformStages = new int[uniformCount];
        int pushStages = 0;
        if (plan != null && plan.terrainLibrary() != 0L) {
            // Ciderlight shader replacement: one library holds both entry points.
            java.util.Arrays.fill(uniformStages, MetalConst.STAGE_VERTEX | MetalConst.STAGE_FRAGMENT);
            MetalRenderPipeline pipeline = new MetalRenderPipeline(
                device, plan, info, plan.terrainLibrary(), plan.vertexEntry(), plan.terrainLibrary(), plan.fragmentEntry(), uniformStages, 0
            );
            pipeline.stateFor(true);
            pipeline.prebuildVariants();
            transformer.onPipelineBuilt(device, pipeline);
            return pipeline;
        }
        if (plan != null && plan.particleLibrary() != 0L) {
            // Ciderlight shader replacement: particles lit like terrain (particle.metal).
            try {
                java.util.Arrays.fill(uniformStages, MetalConst.STAGE_VERTEX | MetalConst.STAGE_FRAGMENT);
                MetalRenderPipeline pipeline = new MetalRenderPipeline(
                    device, plan, info, plan.particleLibrary(), plan.vertexEntry(), plan.particleLibrary(), plan.fragmentEntry(), uniformStages, 0
                );
                pipeline.stateFor(info.depthStencilState() != null);
                pipeline.prebuildVariants();
                transformer.onPipelineBuilt(device, pipeline);
                return pipeline;
            } catch (IllegalStateException e) {
                LOGGER.warn("Ciderlight: particles keep vanilla lighting, the particle shader could not be used for {}: {}", info.name(), e.getMessage());
            }
        }
        long vertLib = 0L;
        long fragLib = 0L;
        long handLib = 0L;
        String vertEntry = "";
        String fragEntry = "";
        try {
            for (BackendRenderPipeline.CreateInfo.Shader shader : info.shaders()) {
                SpvModule module = shader.module();
                int stage = module.type() == ShaderType.VERTEX ? MetalConst.STAGE_VERTEX : MetalConst.STAGE_FRAGMENT;
                SpvModule.Reflection reflection;
                try {
                    reflection = module.reflect();
                } catch (ShaderCompileException e) {
                    throw new IllegalStateException("Couldn't reflect " + shader.name(), e);
                }
                for (SpvModule.Reflection.Descriptor descriptor : reflection.descriptors()) {
                    if (descriptor.binding() < uniformCount) {
                        uniformStages[descriptor.binding()] |= stage;
                    }
                }
                if (!reflection.pushConstants().isEmpty()) {
                    pushStages |= stage;
                }
                MetalShaderCompiler.Result msl = MetalShaderCompiler.translate(shader, uniformCount, info.primitiveTopology() == PrimitiveTopology.POINTS);
                dumpShader(info.name(), module.type() == ShaderType.VERTEX ? "vert" : "frag", msl.source());
                // The shader module patches the translated MSL (clouds lightning hand shine); null means untouched.
                ShaderPatch patch = transformer == null ? null
                    : transformer.patchShader(device, info.name(), module.type(), msl.source(), msl.entryPoint());
                String source = patch != null ? patch.source() : msl.source();
                String entry = patch != null ? patch.entryPoint() : msl.entryPoint();
                long lib;
                try {
                    lib = MetalNative.libraryCreate(device.context(), source);
                } catch (IllegalStateException e) {
                    LOGGER.error("Metal rejected translated {} shader {} for pipeline {}:\n{}", module.type().getName(), shader.name(), info.name(), source);
                    throw e;
                }
                if (module.type() == ShaderType.VERTEX) {
                    vertLib = lib;
                    vertEntry = entry;
                } else {
                    fragLib = lib;
                    fragEntry = entry;
                    if (patch != null) {
                        handLib = patch.extraLibrary();
                    }
                }
            }
            if (vertLib == 0L) {
                throw new IllegalStateException("Pipeline " + info.name() + " has no vertex shader");
            }
            MetalRenderPipeline pipeline = new MetalRenderPipeline(device, plan, info, vertLib, vertEntry, fragLib, fragEntry, uniformStages, pushStages);
            pipeline.handFragmentLibrary = handLib;
            // Build the variant the pipeline will most likely be used with now, the other on demand.
            pipeline.stateFor(info.depthStencilState() != null);
            pipeline.prebuildVariants();
            if (transformer != null) {
                transformer.onPipelineBuilt(device, pipeline);
            }
            return pipeline;
        } catch (RuntimeException e) {
            if (handLib != 0L) {
                MetalNative.release(handLib);
            }
            if (vertLib != 0L) {
                MetalNative.release(vertLib);
            }
            if (fragLib != 0L) {
                MetalNative.release(fragLib);
            }
            throw e;
        }
    }

    /** Encodes vertex layout and color targets in the int layout mc_pipeline_create expects (minus the depth format). */
    static int[] describe(final BackendRenderPipeline.CreateInfo info, final int alphaMode) {
        boolean markerAlpha = alphaMode != PipelineFlags.ALPHA_VANILLA;
        IntArrayList d = new IntArrayList();
        d.add(info.vertexBuffers().size());
        for (BackendRenderPipeline.CreateInfo.VertexBuffer vb : info.vertexBuffers()) {
            d.add(vb.bufferSlot());
            d.add(vb.stride());
            d.add(vb.stepRate());
        }
        d.add(info.attribBindings().size());
        for (BackendRenderPipeline.CreateInfo.AttribBinding attrib : info.attribBindings()) {
            d.add(attrib.location());
            d.add(attrib.bufferSlot());
            d.add(attrib.offset());
            d.add(MetalConst.format(attrib.format()));
        }
        List<ColorTargetState> targets = info.colorTargetStates();
        d.add(targets.size());
        for (ColorTargetState target : targets) {
            if (target == null) {
                d.addElements(d.size(), new int[]{-1, 0, 0, 0, 0, 0, 0, 0, 0});
                continue;
            }
            d.add(MetalConst.format(target.format()));
            d.add(target.writeMask());
            if (target.blendFunction().isPresent() && alphaMode != PipelineFlags.ALPHA_FROM_SHADER) {
                BlendFunction blend = target.blendFunction().get();
                d.add(1);
                d.add(blend.color().sourceFactor().ordinal());
                d.add(blend.color().destFactor().ordinal());
                d.add(blend.color().op().ordinal());
                // With marker alpha the written alpha is the shader's own output, not a blend of it.
                d.add(markerAlpha ? (alphaMode == PipelineFlags.ALPHA_ZERO ? BlendFactor.ZERO : BlendFactor.ONE).ordinal() : blend.alpha().sourceFactor().ordinal());
                d.add(markerAlpha ? BlendFactor.ZERO.ordinal() : blend.alpha().destFactor().ordinal());
                d.add(markerAlpha ? BlendOp.ADD.ordinal() : blend.alpha().op().ordinal());
            } else {
                d.addElements(d.size(), new int[]{0, 0, 0, 0, 0, 0, 0});
            }
        }
        d.add(-1); // depth format placeholder, filled per variant
        d.add(MetalConst.primitive(info.primitiveTopology()));
        return d.toIntArray();
    }

    /** Pipeline state matching a pass with or without a depth attachment. */
    long stateFor(final boolean hasDepth) {
        return this.stateFor(hasDepth, false);
    }

    /** The state for a pass with or without depth, for the first-person hand pass with the metal shine where this pipeline has it. */
    long stateFor(final boolean hasDepth, final boolean hand) {
        boolean shine = hand && this.handFragmentLibrary != 0L;
        int variant = variant(hasDepth, shine);
        long state = this.states[variant];
        if (state == 0L) {
            java.util.concurrent.CompletableFuture<Long> build = this.variantBuilds[variant];
            state = build != null ? Prebuild.take(build, this.name) : this.createState(hasDepth, shine);
            this.states[variant] = state;
        }
        return state;
    }

    private static int variant(final boolean hasDepth, final boolean hand) {
        return (hand ? 2 : 0) + (hasDepth ? 0 : 1);
    }

    /**
     * Starts building the variants nobody has asked for yet in the background: the other depth variant and the hand
     * ones. Pipelines are not always drawn into the kind of pass their depth state suggests (the sky, the vignette, the
     * crosshair), and building that variant on the render thread when it first came up took a frame's time with a cold
     * shader cache. Called while the pipeline is compiled, before anything draws with it.
     */
    private void prebuildVariants() {
        for (boolean hand : new boolean[]{false, true}) {
            if (hand && this.handFragmentLibrary == 0L) {
                continue;
            }
            for (boolean hasDepth : new boolean[]{true, false}) {
                int variant = variant(hasDepth, hand);
                if (this.states[variant] == 0L) {
                    this.variantBuilds[variant] = Prebuild.start(() -> this.createState(hasDepth, hand));
                }
            }
        }
    }

    private long createState(final boolean hasDepth, final boolean hand) {
        int[] desc = this.baseDescriptor.clone();
        desc[desc.length - 2] = hasDepth ? DEPTH_FORMAT : -1;
        return MetalNative.pipelineCreate(
            this.device.context(), this.vertexLibrary, this.vertexEntry, hand ? this.handFragmentLibrary : this.fragmentLibrary, this.fragmentEntry, desc,
            hand ? this.name + " (hand)" : this.name
        );
    }

    int @org.jspecify.annotations.Nullable [] skyUniforms() { return this.skyUniforms; }

    boolean isSunrise() { return this.name.equals("minecraft:pipeline/sunrise_sunset"); }

    /** Stride of the per-section instance data, or -1 when the layout is not the expected one. */
    int sectionStride() {
        return this.sectionStride;
    }

    int sectionPosOffset() {
        return this.sectionPosOffset;
    }

    int terrainKind() {
        return this.terrainKind;
    }

    int @org.jspecify.annotations.Nullable [] terrainUniforms() {
        return this.terrainUniforms;
    }

    /** Opaque particles: captured like entities for the block-light marker, but cast no shadow. */
    boolean isLitParticle() {
        return this.litParticle;
    }

    /** Glowing eyes: captured only to be marked as light sources (MetalShaders.markEntityLight), never as shadow casters. */
    boolean isEmissiveLayer() {
        return this.emissiveLayer;
    }

    /** Slots of DynamicTransforms and Sampler0 for shadow-casting entity pipelines and lit particles, else null. */
    int @org.jspecify.annotations.Nullable [] entityUniforms() {
        return this.entityUniforms;
    }

    /** Slots of DynamicTransforms, CloudInfo and CloudFaces for the cloud pipelines (they cast shadows), else null. */
    int @org.jspecify.annotations.Nullable [] cloudUniforms() {
        return this.cloudUniforms;
    }

    int @org.jspecify.annotations.Nullable [] shadowDescriptor() {
        return this.shadowDescriptor;
    }

    /** Vanilla's round blob shadow under entities; with the shader's real shadows on it is redundant and skipped. */
    boolean isBlobShadow() {
        return this.blobShadow;
    }

    String name() {
        return this.name;
    }

    long depthState() {
        return this.depthState;
    }

    boolean cull() {
        return this.cull;
    }

    boolean wireframe() {
        return this.wireframe;
    }

    float depthBias() {
        return this.depthBias;
    }

    float depthSlopeScale() {
        return this.depthSlopeScale;
    }

    int primitive() {
        return this.primitive;
    }

    boolean isFan() {
        return this.fan;
    }

    int pushConstantStages() {
        return this.pushConstantStages;
    }

    int uniformCount() {
        return this.uniforms.size();
    }

    int uniformStages(final int index) {
        return this.uniformStages[index];
    }

    String uniformName(final int index) {
        return this.uniforms.get(index).name();
    }

    UniformType uniformType(final int index) {
        return this.uniforms.get(index).type();
    }

    @Nullable
    GpuFormat uniformFormat(final int index) {
        return this.uniforms.get(index).gpuFormat();
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            this.device.encoder().queueForDestroy(this);
        }
    }

    @Override
    public void destroy() {
        long fragment = this.fragmentLibrary == this.vertexLibrary ? 0L : this.fragmentLibrary;
        for (int variant = 0; variant < this.states.length; variant++) {
            if (this.variantBuilds[variant] != null) {
                Prebuild.releaseWhenDone(this.variantBuilds[variant], this.states[variant]);
            }
        }
        for (long handle : new long[]{this.states[0], this.states[1], this.states[2], this.states[3], this.handFragmentLibrary, this.vertexLibrary, fragment}) {
            if (handle != 0L) {
                MetalNative.release(handle);
            }
        }
    }
}
