package dev.ciderlight.backend;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.world.phys.Vec3;

/**
 * Short-lived light bursts from explosions, fed by the client's explosion packet ({@code ClientPacketListener} mixin).
 *
 * <p>An explosion lights the world for a moment even after its entity is gone, so this is kept apart from the fuse's
 * white flash: a burning TNT blinks white but does not light the surroundings; the blast does. Queued from the network
 * thread, drained on the render thread by {@link #active(long)}, and written into the frame's burst-light slots by
 * MetalShaders.</p>
 */
public final class ExplosionLights {
    /** How long a burst stays in the list; nearly all of its light is gone by then. */
    private static final long LIFETIME_NANOS = 700_000_000L;
    /** A cap so a wall of explosions while the shaders are off cannot grow the queue without bound. */
    private static final int MAX_PENDING = 64;
    private static final Queue<Burst> PENDING = new ConcurrentLinkedQueue<>();
    private static final List<Burst> ACTIVE = new ArrayList<>();

    private ExplosionLights() {
    }

    /** Called from the network thread when an explosion packet arrives. */
    public static void add(final Vec3 pos, final float power) {
        if (PENDING.size() >= MAX_PENDING) {
            return;
        }
        PENDING.add(new Burst(pos, Math.clamp(power, 1.0F, 8.0F), System.nanoTime()));
    }

    /** Render thread, once per frame: drains new bursts, drops the expired ones and returns a snapshot. */
    public static List<Burst> active(final long now) {
        Burst burst;
        while ((burst = PENDING.poll()) != null) {
            ACTIVE.add(burst);
        }
        ACTIVE.removeIf(b -> now - b.startNanos() > LIFETIME_NANOS);
        return List.copyOf(ACTIVE);
    }

    /** Drops every burst, e.g. when the level changes. */
    public static void clear() {
        PENDING.clear();
        ACTIVE.clear();
    }

    /** One explosion. {@code power} is the packet's explosion radius (4 for TNT, 3 for a creeper, 6 when charged). */
    public record Burst(Vec3 pos, float power, long startNanos) {
        /** Full on at the blast, decaying within a few frames; power 4 (TNT) starts around 1.9. */
        public float intensity(final long now) {
            float age = (now - this.startNanos) / 1.0e9F;
            return (0.5F + this.power * 0.35F) * (float)Math.exp(-age / 0.28F);
        }
    }
}
