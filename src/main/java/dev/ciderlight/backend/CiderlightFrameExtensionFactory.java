package dev.ciderlight.backend;

import dev.cidercore.api.FrameExtension;
import dev.cidercore.api.FrameExtensionFactory;
import dev.cidercore.api.MetalDeviceApi;

/**
 * The {@code cidercore:frame_extension} entrypoint: hands Ciderlight's shader pipeline to the core when the Metal
 * device is created (see {@link CiderlightFrameExtension}).
 */
public final class CiderlightFrameExtensionFactory implements FrameExtensionFactory {
    @Override
    public FrameExtension create(final MetalDeviceApi device) {
        return new CiderlightFrameExtension(device);
    }
}
