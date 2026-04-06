package com.velocitycore.system;

import com.velocitycore.config.VCConfig;

import java.util.LinkedHashMap;

/**
 * S2 — SmartChunkCache
 *
 * Per-worldgen-thread ThreadLocal LRU cache for WWEE noise/biome samples.
 * Each thread maintains its own LinkedHashMap keyed on a packed long (x, z, samplerType).
 * Zero lock contention on the hot path.
 *
 * Cache size: VCConfig.NOISE_CACHE_SIZE entries per thread (default 4096).
 *
 * See .cursor/prompts/08_deferreddecorator.md and 10_smartchunkcache.md for implementation brief.
 */
public final class SmartChunkCache {

    private static final ThreadLocal<LinkedHashMap<Long, Object>> cache = ThreadLocal.withInitial(
        () -> new LinkedHashMap<Long, Object>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<Long, Object> eldest) {
                return size() > VCConfig.NOISE_CACHE_SIZE.get();
            }
        }
    );

    /**
     * Packs chunk sample coordinates and sampler type into a single long key.
     * Layout: bits 63-40 = x (24 bits), bits 39-8 = z (24 bits), bits 7-0 = type.
     *
     * @param x    block or chunk x coordinate
     * @param z    block or chunk z coordinate
     * @param type sampler type discriminator (0=biome/climate, 1=noise scalar)
     * @return packed long key
     */
    public static long packKey(int x, int z, int type) {
        return ((long)(x & 0xFFFFFF) << 40) | ((long)(z & 0xFFFFFF) << 8) | (type & 0xFF);
    }

    /**
     * Looks up a cached sample. Returns null on cache miss.
     *
     * @param key packed long key from packKey()
     * @return cached value or null
     */
    public static Object get(long key) {
        if (!VCConfig.ENABLE_NOISE_CACHE.get()) return null;
        return cache.get().get(key);
    }

    /**
     * Inserts a sample into the cache.
     *
     * @param key   packed long key from packKey()
     * @param value the sample value (Climate.TargetPoint or double[])
     */
    public static void put(long key, Object value) {
        if (!VCConfig.ENABLE_NOISE_CACHE.get()) return;
        cache.get().put(key, value);
    }

    private SmartChunkCache() {}
}
