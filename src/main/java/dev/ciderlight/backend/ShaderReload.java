package dev.ciderlight.backend;

/**
 * Whether the settings compiled into the shader pipelines (ShaderSetting and the compiled ShaderToggle effects)
 * apply without restarting the game. Hot apply stages a choice on the settings page and the Apply button commits it,
 * rebuilding the render pipelines; -Dciderlight.hotReload=false keeps the old restart-to-apply behaviour as a
 * fallback.
 */
public final class ShaderReload {
    /** -Dciderlight.hotReload=false restores the restart-to-apply behaviour. */
    public static final boolean HOT_ENABLED = Boolean.parseBoolean(System.getProperty("ciderlight.hotReload", "false"));

    private ShaderReload() {
    }
}
