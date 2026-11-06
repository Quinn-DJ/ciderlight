package dev.ciderlight.backend;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import dev.cidercore.api.MetalDeviceApi;
import dev.cidercore.api.PipelineFlags;
import dev.cidercore.api.PipelinePlan;
import dev.cidercore.api.PipelineTransformer;
import dev.cidercore.api.ShaderPatch;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.cidercore.backend.MetalRenderPipeline;

/**
 * Ciderlight's classifier and patcher for the render pipelines the Metal backend compiles.
 *
 * <p>Pre-split this logic lived in {@code MetalRenderPipeline}: its constructor classified every pipeline from
 * {@link MetalShaders} and its compiler patched the translated MSL for clouds, lightning and the held-item shine. The
 * backend is core now, so it asks this transformer instead: {@link #plan} reproduces the classification and compiles
 * the terrain/particle replacement libraries, {@link #patchShader} applies the MSL patches, and
 * {@link #onPipelineBuilt} starts the shadow/block-light prebuilds.</p>
 */
final class CiderlightPipelineTransformer implements PipelineTransformer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Ciderlight");

    private final CiderlightFrameExtension extension;

    CiderlightPipelineTransformer(final CiderlightFrameExtension extension) {
        this.extension = extension;
    }

    /** The shader generation whose pipelines this transformer classifies and patches. */
    private MetalShaders shaders() {
        return this.extension.shaders();
    }

    @Override
    public @Nullable PipelinePlan plan(final MetalDeviceApi device, final BackendRenderPipeline.CreateInfo info) {
        int terrainKind = MetalShaders.terrainKind(info.name());
        boolean litParticle = MetalShaders.isLitParticle(info.name());
        boolean emissiveLayer = MetalShaders.isEmissiveLayer(info.name());
        boolean blobShadow = MetalShaders.enabled() && info.name().equals("minecraft:pipeline/entity_shadow");
        int alphaMode = terrainKind == PipelineFlags.KIND_TRANSLUCENT ? PipelineFlags.ALPHA_FROM_SHADER
            : isOpaqueCloud(info.name()) ? PipelineFlags.ALPHA_ZERO : PipelineFlags.ALPHA_VANILLA;
        int[] base = MetalRenderPipeline.describe(info, alphaMode);
        int[] skyUniforms = MetalShaders.enabled() && info.name().equals("minecraft:pipeline/sky")
            ? MetalShaders.indicesFor(info.uniforms(), "DynamicTransforms", "Projection") : null;
        int[] terrainUniforms = terrainKind != PipelineFlags.KIND_NONE ? MetalShaders.indicesFor(info.uniforms()) : null;
        boolean entity = MetalShaders.castsEntityShadow(info.name()) || litParticle || emissiveLayer;
        int[] shadowDescriptor = terrainKind != PipelineFlags.KIND_NONE || entity ? MetalShaders.shadowDescriptor(base) : null;
        int[] entityUniforms = entity ? MetalShaders.indicesFor(info.uniforms(), "DynamicTransforms", "Sampler0") : null;
        int[] cloudUniforms = isOpaqueCloud(info.name())
            ? MetalShaders.indicesFor(info.uniforms(), "DynamicTransforms", "CloudInfo", "CloudFaces") : null;
        long terrainLibrary = 0L;
        long particleLibrary = 0L;
        String vertexEntry = null;
        String fragmentEntry = null;
        MetalShaders shaders = this.shaders();
        if (terrainKind != PipelineFlags.KIND_NONE) {
            // Ciderlight shader replacement: one library holds both entry points.
            terrainLibrary = shaders.compileTerrainLibrary(info.uniforms(), terrainKind, MetalShaders.vertexLayoutDefines(info));
            vertexEntry = "terrain_vertex";
            fragmentEntry = "terrain_fragment";
        }
        if (litParticle) {
            // Ciderlight shader replacement: particles lit like terrain (particle.metal).
            try {
                particleLibrary = shaders.compileParticleLibrary(info.uniforms());
                vertexEntry = "particle_vertex";
                fragmentEntry = "particle_fragment";
            } catch (IllegalStateException e) {
                LOGGER.warn("Ciderlight: particles keep vanilla lighting, the particle shader could not be used for {}: {}", info.name(), e.getMessage());
            }
        }
        return new PipelinePlan(
            terrainKind, terrainLibrary, particleLibrary, vertexEntry, fragmentEntry, alphaMode, blobShadow, skyUniforms, terrainUniforms,
            shadowDescriptor, entityUniforms, cloudUniforms, litParticle, emissiveLayer
        );
    }

    @Override
    public @Nullable ShaderPatch patchShader(
        final MetalDeviceApi device, final String pipelineName, final ShaderType stage, final String source, final String entryPoint
    ) {
        if (stage == ShaderType.VERTEX) {
            String patched = shadedClouds(pipelineName, source);
            patched = sunlitClouds(pipelineName, patched);
            return patched.equals(source) ? null : new ShaderPatch(patched, entryPoint, 0L);
        }
        // The hand shine is computed on the shader as translated, before the cloud/lightning patches, as it was pre-split.
        String handSource = handShine(pipelineName, source);
        long handLibrary = 0L;
        if (handSource != null) {
            try {
                handLibrary = device.createLibrary(handSource);
                MetalRenderPipeline.dumpShader(pipelineName, "hand.frag", handSource);
            } catch (IllegalStateException e) {
                LOGGER.warn("Ciderlight: held items will not shine, Metal rejected the patched {} shader: {}", pipelineName, e.getMessage());
            }
        }
        String patched = opaqueClouds(pipelineName, source);
        patched = brightLightning(pipelineName, patched);
        if (handLibrary == 0L && patched.equals(source)) {
            return null;
        }
        return new ShaderPatch(patched, entryPoint, handLibrary);
    }

    @Override
    public void onPipelineBuilt(final MetalDeviceApi device, final BackendRenderPipeline pipeline) {
        this.shaders().prebuildFor((MetalRenderPipeline)pipeline);
    }

    private static boolean isOpaqueCloud(final String pipeline) {
        return MetalShaders.enabled() && (pipeline.equals("minecraft:pipeline/clouds") || pipeline.equals("minecraft:pipeline/flat_clouds"));
    }

    /**
     * Vanilla draws clouds at 80% opacity and fades that linearly to zero over the whole cloud range, so stars and
     * the moon show through almost every cloud. With Ciderlight shaders on, clouds are opaque out to about two thirds
     * of their range and only fade beyond that (alpha = 0.8 * (1 - fog), so 0.8 * 0.35 maps to 1).
     */
    private static String opaqueClouds(final String pipeline, final String source) {
        if (!isOpaqueCloud(pipeline)) {
            return source;
        }
        if (!source.contains("out.fragColor") || !source.contains("return out;")) {
            LOGGER.warn("Ciderlight: unexpected cloud shader layout in {}, leaving clouds translucent", pipeline);
            return source;
        }
        return source.replace("return out;", "out.fragColor.w = saturate(out.fragColor.w * 3.5);\n    return out;");
    }

    /** Vanilla's cloud face brightness (bottom, top, north, south, west, east), as SPIR-V Cross writes the array. */
    private static final java.util.regex.Pattern CLOUD_FACES = java.util.regex.Pattern.compile(
        "(constant spvUnsafeArray<float4, 6> _\\d+ = spvUnsafeArray<float4, 6>\\(\\{ )float4\\(0\\.69999998[^;]*?(\\}\\);)");

    /**
     * Vanilla shades clouds in flat greys (undersides 0.7, walls 0.8 and 0.9). With Ciderlight shaders on they are
     * shaded like the sky lights them: sunlit white tops, walls a little cooler and the undersides a blue grey, so
     * they read as volumes against the pale sky instead of flat cut-outs.
     */
    private static String shadedClouds(final String pipeline, final String source) {
        if (!isOpaqueCloud(pipeline)) {
            return source;
        }
        java.util.regex.Matcher m = CLOUD_FACES.matcher(source);
        if (!m.find()) {
            LOGGER.warn("Ciderlight: unexpected cloud shader layout in {}, leaving cloud shading as it is", pipeline);
            return source;
        }
        String faces = "float4(0.76, 0.76, 0.83, 1.0), float4(1.0, 0.99, 0.97, 1.0), float4(0.86, 0.865, 0.90, 1.0), "
            + "float4(0.86, 0.865, 0.90, 1.0), float4(0.93, 0.93, 0.95, 1.0), float4(0.93, 0.93, 0.95, 1.0) ";
        return source.substring(0, m.start()) + m.group(1) + faces + m.group(2) + source.substring(m.end());
    }

    /** Added to the cloud vertex shader; MetalShaders.bindCloudLight supplies the buffer. */
    private static final String CLOUD_LIGHT_MSL = """
        struct McCloudLight
        {
            float4 sun;   // xyz: direction to the sun (or moon), w: how strongly it lights the clouds
            float4 warm;  // rgb: tint of the faces turned to a low sun, w: golden hour (1 with the sun at the horizon)
            float4 cool;  // rgb: tint of the faces turned away from it
        };

        // Clouds scatter most of their light, so at noon the walls facing the sun are only a little brighter than
        // those facing away. As the sun drops it lights them side-on: the sun-facing walls (and, grazed, the tops)
        // glow warm while the far side falls into a slightly cooler shade. The whole cloud takes on some of the low
        // sun's colour, the faces turned to it most. Looking toward a low sun, light scattered
        // forward through the clouds makes the whole cloud glow warm. `direction` is vanilla's face index (down, up,
        // north, south, west, east), `face` the base shade of that face, `pos` the vertex relative to the camera.
        static float3 mc_cloud_light(constant McCloudLight& light, int direction, bool top, float3 face, float3 pos)
        {
            const float3 normals[6] = { float3(0, -1, 0), float3(0, 1, 0), float3(0, 0, -1), float3(0, 0, 1), float3(-1, 0, 0), float3(1, 0, 0) };
            float3 n = top ? float3(0, 1, 0) : normals[clamp(direction, 0, 5)];
            float ndl = dot(n, light.sun.xyz);
            float k = light.sun.w;
            float golden = light.warm.w;
            float lit = saturate(ndl);
            float away = saturate(-ndl);
            float side = 1.0 + k * (0.08 + 0.18 * golden) * lit - k * (0.05 + 0.04 * golden) * away;
            float forward = golden * pow(saturate(dot(normalize(pos), light.sun.xyz)), 3.0);
            float3 warm = mix(float3(1.0), light.warm.xyz, saturate(golden * (0.5 + 0.5 * saturate(0.2 + 0.8 * ndl)) + 0.8 * forward));
            float3 cool = mix(float3(1.0), light.cool.xyz, golden * away * (1.0 - forward));
            return face * (side + 0.12 * forward) * warm * cool;
        }

        """;

    private static final java.util.regex.Pattern CLOUD_COLOR = java.util.regex.Pattern.compile("out\\.vertexColor = (_\\d+) \\* (_\\d+)\\.CloudColor;");

    /**
     * Shades each cloud face by where the sun is (see mc_cloud_light), on top of shadedClouds' fixed face greys, so a
     * low sun gives the clouds a warm lit side and a cool shaded one.
     */
    private static String sunlitClouds(final String pipeline, final String source) {
        if (!MetalShaders.enabled() || !pipeline.equals("minecraft:pipeline/clouds")) {
            return source;
        }
        java.util.regex.Matcher color = CLOUD_COLOR.matcher(source);
        String entry = "vertex main0_out main0(";
        int entryAt = source.indexOf(entry);
        int entryEnd = entryAt < 0 ? -1 : source.indexOf(")\n{", entryAt);
        if (entryEnd < 0 || !color.find() || !source.contains("int direction =") || !source.contains("bool useTopColor =") || !source.contains("float3 pos =")) {
            LOGGER.warn("Ciderlight: unexpected cloud shader layout in {}, clouds will not be lit by the sun", pipeline);
            return source;
        }
        String patched = source.substring(0, color.start())
            + "out.vertexColor = float4(mc_cloud_light(mcCloud, direction, useTopColor, " + color.group(1) + ".xyz, pos), " + color.group(1) + ".w) * "
            + color.group(2) + ".CloudColor;" + source.substring(color.end());
        patched = patched.substring(0, entryAt) + CLOUD_LIGHT_MSL + patched.substring(entryAt, entryEnd)
            + ", constant McCloudLight& mcCloud [[buffer(14)]]" + patched.substring(entryEnd);
        return patched;
    }

    /**
     * Vanilla's lightning bolt is a pale, mostly transparent grey. With Ciderlight shaders on it is drawn several
     * times brighter, so its additive layers build up to a white-hot core with a wide glow.
     */
    private static String brightLightning(final String pipeline, final String source) {
        if (!MetalShaders.enabled() || !pipeline.equals("minecraft:pipeline/lightning")) {
            return source;
        }
        if (!source.contains("out.fragColor") || !source.contains("return out;")) {
            LOGGER.warn("Ciderlight: unexpected lightning shader layout in {}, leaving the bolt as it is", pipeline);
            return source;
        }
        return source.replace("return out;", "out.fragColor.xyz *= float3(3.2, 3.4, 4.0);\n    return out;");
    }

    /** Added to an item fragment shader for the first-person hand pass; MetalShaders.bindHand supplies the two buffers. */
    private static final String HAND_SHINE_MSL = """
        struct McHandShine
        {
            float4 light;  // x: where the highlight band lies across the screen, z: its strength
            float4 color;  // rgb: the light's colour, a: a steady sheen under any light
            float4 screen; // xy: 1 / the target's size in pixels
        };

        // Metal tools, weapons and armour catch the light: a bright band slides across them as the view turns or the
        // item swings, over a faint steady sheen. `map` marks the metal sprites of the item atlas (the sprite-map layout
        // of foliage.metal); brighter texels shine more, so a wooden handle stays dull.
        static float3 mc_hand_shine(constant McHandShine& shine, const device uchar* map, float2 uv, float4 fragCoord, float3 lit)
        {
            float2 scale = *(const device float2*)map;
            uint2 grid = *(const device uint2*)(map + 8);
            int2 cell = int2(floor(uv * scale));
            if (cell.x < 0 || cell.y < 0 || uint(cell.x) >= grid.x || uint(cell.y) >= grid.y || map[16 + uint(cell.y) * grid.x + uint(cell.x)] == 0)
            {
                return float3(0.0);
            }
            float2 p = fragCoord.xy * shine.screen.xy;
            float across = p.x + (1.0 - p.y) * 0.6;
            float offset = (across - shine.light.x) / 0.09;
            float band = exp(-offset * offset);
            float brightness = dot(lit, float3(0.3, 0.6, 0.1));
            return shine.color.xyz * ((band * shine.light.z + shine.color.w) * smoothstep(0.08, 0.6, brightness) * (0.4 + brightness));
        }

        """;

    /**
     * The fragment shader of an item pipeline with the metal shine added, for the first-person hand pass; null for
     * other pipelines, or when the translated shader does not look as expected.
     */
    private static @Nullable String handShine(final String pipeline, final String source) {
        if (!MetalShaders.enabled()
            || !(pipeline.equals("minecraft:pipeline/item_cutout") || pipeline.equals("minecraft:pipeline/item_translucent")
                || pipeline.equals("minecraft:pipeline/item_translucent_glint"))) {
            return null;
        }
        String inputs = "struct main0_in\n{\n";
        String entry = "fragment main0_out main0(";
        int entryAt = source.indexOf(entry);
        int entryEnd = entryAt < 0 ? -1 : source.indexOf(")\n{", entryAt);
        if (!source.contains(inputs) || entryEnd < 0 || !source.contains("in.texCoord0") || !source.contains("out.fragColor")
            || !source.contains("return out;") || source.contains("gl_FragCoord")) {
            LOGGER.warn("Ciderlight: unexpected item shader layout in {}, held items will not shine", pipeline);
            return null;
        }
        String patched = source.substring(0, entryAt) + HAND_SHINE_MSL + source.substring(entryAt, entryEnd)
            + ", constant McHandShine& mcShine [[buffer(14)]], const device uchar* mcSprites [[buffer(13)]]" + source.substring(entryEnd);
        patched = patched.replace(inputs, inputs + "    float4 gl_FragCoord [[position]];\n");
        return patched.replace("return out;",
            "out.fragColor.xyz += mc_hand_shine(mcShine, mcSprites, in.texCoord0, in.gl_FragCoord, out.fragColor.xyz);\n    return out;");
    }
}
