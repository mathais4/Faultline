package io.faultline.core;

/**
 * Deterministic pseudo-randomness keyed by identity instead of by call order.
 *
 * Fault decisions and retry jitter are derived from (seed, name, order id, attempt) rather than from a shared
 * Random. Thread scheduling therefore cannot change WHICH requests get hurt: the same seed always injects the
 * same faults into the same orders, which is what makes an experiment re-runnable.
 */
public final class Hashing {
    private Hashing() {}

    /** SplitMix64 finalizer. */
    public static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public static long hash(long seed, String... parts) {
        long h = mix(seed);
        for (String p : parts) {
            h = mix(h ^ (p == null ? 0 : p.hashCode()));
        }
        return h;
    }

    /** Uniform value in [0, 1) determined entirely by the inputs. */
    public static double unit(long seed, String... parts) {
        return (hash(seed, parts) >>> 11) * 0x1.0p-53;
    }
}
