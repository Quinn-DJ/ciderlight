package dev.ciderlight.backend;

/**
 * Checks ShaderSettings.needsPipelineReload: settings that never reach a compiled shader source must not rebuild the
 * render pipelines. Standalone; no Minecraft classes, GPU or test framework required.
 */
public final class ShaderSettingsChecks {
    private static int checks;

    public static void main(String[] args) {
        ShaderSettings defaults = ShaderSettings.defaults();
        check(defaults.equals(new ShaderSettings(false, false, true, true, true)), "defaults are shaders off, High, every effect on");
        check(!defaults.needsPipelineReload(defaults), "the same snapshot needs no reload");
        check(!new ShaderSettings(false, false, false, false, false).needsPipelineReload(defaults),
            "quality and effect toggles do not matter while the shaders are off");

        ShaderSettings on = new ShaderSettings(true, false, true, true, true);
        check(on.needsPipelineReload(on) == false, "the same enabled snapshot needs no reload");
        check(defaults.needsPipelineReload(on), "turning the shaders on reloads");
        check(on.needsPipelineReload(defaults), "turning the shaders off reloads");
        check(on.needsPipelineReload(new ShaderSettings(true, true, true, true, true)), "low to high recompiles the shaders");
        check(!new ShaderSettings(true, false, true, true, true).needsPipelineReload(on), "an unchanged enabled snapshot needs no reload");
        check(on.needsPipelineReload(new ShaderSettings(true, false, false, true, true)), "turning waving off recompiles the terrain shaders");
        check(on.needsPipelineReload(new ShaderSettings(true, false, true, false, true)), "turning water reflections off recompiles the water shaders");
        check(on.needsPipelineReload(new ShaderSettings(true, false, true, true, false)), "turning water waves off recompiles the water shaders");
        check(!new ShaderSettings(true, false, false, false, false).needsPipelineReload(
            new ShaderSettings(true, false, false, false, false)), "the same disabled-effects snapshot needs no reload");
        check(new ShaderSettings(true, false, false, false, false).needsPipelineReload(
            new ShaderSettings(true, false, true, false, false)), "enabling waving alone recompiles the terrain shaders");
        System.out.println("Shader settings checks passed (" + checks + " checks).");
    }

    private static void check(boolean value, String what) {
        checks++;
        if (!value) {
            throw new AssertionError(what);
        }
    }
}
