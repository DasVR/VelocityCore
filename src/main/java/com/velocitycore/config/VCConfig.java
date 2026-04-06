package com.velocitycore.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

import java.util.List;

/**
 * Central configuration holder for VelocityCore.
 * Produces two configs: velocitycore-server.toml and velocitycore-client.toml.
 * Every system reads its toggle and parameters from static fields here.
 *
 * See .cursor/prompts/01_vcconfig.md for full implementation brief.
 */
public final class VCConfig {

    // -------------------------------------------------------------------------
    // SERVER CONFIG — velocitycore-server.toml
    // -------------------------------------------------------------------------

    public static final ForgeConfigSpec SERVER_SPEC;

    // general
    public static ForgeConfigSpec.BooleanValue ENABLE_TPS_TRACKING;

    // chunk_systems
    public static ForgeConfigSpec.BooleanValue ENABLE_GEN_THROTTLE;
    public static ForgeConfigSpec.BooleanValue ENABLE_NOISE_CACHE;
    public static ForgeConfigSpec.IntValue     NOISE_CACHE_SIZE;
    public static ForgeConfigSpec.BooleanValue ENABLE_DEFERRED_DECORATOR;
    public static ForgeConfigSpec.IntValue     DECORATION_PER_TICK;
    public static ForgeConfigSpec.BooleanValue ENABLE_PRELOAD_RING;
    public static ForgeConfigSpec.BooleanValue ENABLE_SMART_EVICTION;
    public static ForgeConfigSpec.IntValue     HOT_CHUNK_MAX;
    public static ForgeConfigSpec.BooleanValue ENABLE_FAST_PATH;
    public static ForgeConfigSpec.BooleanValue ENABLE_REGION_BUFFER;

    // mob_systems
    public static ForgeConfigSpec.BooleanValue ENABLE_AI_THROTTLE;
    public static ForgeConfigSpec.BooleanValue ENABLE_SPAWN_LIMITER;
    public static ForgeConfigSpec.BooleanValue ENABLE_MOB_NORMALIZER;
    public static ForgeConfigSpec.IntValue     MOB_NORMALIZER_MAX_WEIGHT;
    public static ForgeConfigSpec.IntValue     MOB_NORMALIZER_MIN_WEIGHT;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> MOB_NORMALIZER_EXEMPTIONS;
    public static ForgeConfigSpec.BooleanValue ENABLE_MOB_CAP_GUARD;
    public static ForgeConfigSpec.BooleanValue ENABLE_PATHFINDING_THROTTLE;

    // -------------------------------------------------------------------------
    // CLIENT CONFIG — velocitycore-client.toml
    // -------------------------------------------------------------------------

    public static final ForgeConfigSpec CLIENT_SPEC;

    // client_systems
    public static ForgeConfigSpec.BooleanValue ENABLE_CHUNK_PRIORITIZER;
    public static ForgeConfigSpec.BooleanValue ENABLE_VELOCITY_HINT;
    public static ForgeConfigSpec.BooleanValue ENABLE_ENTITY_CULLER;
    public static ForgeConfigSpec.IntValue     ENTITY_CULL_RANGE;

    static {
        // Build server spec
        ForgeConfigSpec.Builder serverBuilder = new ForgeConfigSpec.Builder();
        buildServerConfig(serverBuilder);
        SERVER_SPEC = serverBuilder.build();

        // Build client spec
        ForgeConfigSpec.Builder clientBuilder = new ForgeConfigSpec.Builder();
        buildClientConfig(clientBuilder);
        CLIENT_SPEC = clientBuilder.build();
    }

    private static void buildServerConfig(ForgeConfigSpec.Builder b) {
        b.push("general");
        ENABLE_TPS_TRACKING = b.comment("Master toggle for the TPS measurement loop. Disabling also disables all TPS-scaled behaviour.")
            .define("enable_tps_tracking", true);
        b.pop();

        b.push("chunk_systems");
        ENABLE_GEN_THROTTLE = b.comment("S1: Enable TPS-aware chunk generation budget. Prevents gen from monopolising tick time.")
            .define("enable_gen_throttle", true);
        ENABLE_NOISE_CACHE = b.comment("S2: Enable per-thread LRU noise/biome sample cache. Eliminates redundant WWEE noise calls.")
            .define("enable_noise_cache", true);
        NOISE_CACHE_SIZE = b.comment("S2: Maximum cache entries per worldgen thread.")
            .defineInRange("noise_cache_size", 4096, 256, 65536);
        ENABLE_DEFERRED_DECORATOR = b.comment("S3: Spread WWEE feature decoration across multiple ticks instead of all at once.")
            .define("enable_deferred_decorator", true);
        DECORATION_PER_TICK = b.comment("S3: Maximum decoration tasks drained per tick.")
            .defineInRange("decoration_per_tick", 4, 1, 20);
        ENABLE_PRELOAD_RING = b.comment("S6: Velocity-based chunk read-ahead. Requires a Chunky pre-generated world.")
            .define("enable_preload_ring", true);
        ENABLE_SMART_EVICTION = b.comment("S7: Keep frequently-accessed chunks in memory using LFU eviction.")
            .define("enable_smart_eviction", true);
        HOT_CHUNK_MAX = b.comment("S7: Maximum number of hot chunks to keep in memory beyond normal view distance.")
            .defineInRange("hot_chunk_max", 512, 64, 4096);
        ENABLE_FAST_PATH = b.comment("S8: Skip redundant pipeline stages for ChunkStatus.FULL chunks.")
            .define("enable_fast_path", true);
        ENABLE_REGION_BUFFER = b.comment("S9: Sequential .mca sector read-ahead when a region file is first accessed.")
            .define("enable_region_buffer", true);
        b.pop();

        b.push("mob_systems");
        ENABLE_AI_THROTTLE = b.comment("S4: Throttle mob AI tick frequency by distance from players and server TPS.")
            .define("enable_ai_throttle", true);
        ENABLE_SPAWN_LIMITER = b.comment("S5: Per-chunk TPS-scaled spawn attempt cooldown.")
            .define("enable_spawn_limiter", true);
        ENABLE_MOB_NORMALIZER = b.comment("S10: Normalize modded spawn weight pools at server startup.")
            .define("enable_mob_normalizer", true);
        MOB_NORMALIZER_MAX_WEIGHT = b.comment("S10: Maximum spawn weight any single entity may have after normalization.")
            .defineInRange("mob_normalizer_max_weight", 80, 20, 200);
        MOB_NORMALIZER_MIN_WEIGHT = b.comment("S10: Minimum spawn weight floor after normalization.")
            .defineInRange("mob_normalizer_min_weight", 5, 1, 20);
        MOB_NORMALIZER_EXEMPTIONS = b.comment("S10: List of mod IDs to exempt from spawn weight normalization (e.g. [\"betteranimals\"]).")
            .defineListAllowEmpty("mob_normalizer_exemptions", List.of(), o -> o instanceof String);
        ENABLE_MOB_CAP_GUARD = b.comment("S11: Per-category soft mob cap that scales down under server stress.")
            .define("enable_mob_cap_guard", true);
        ENABLE_PATHFINDING_THROTTLE = b.comment("S12: Cap pathfinding node evaluations per tick based on mob distance and TPS.")
            .define("enable_pathfinding_throttle", true);
        b.pop();
    }

    private static void buildClientConfig(ForgeConfigSpec.Builder b) {
        b.push("client_systems");
        ENABLE_CHUNK_PRIORITIZER = b.comment("S13: Re-sort incoming chunk data packets nearest-first before rendering.")
            .define("enable_chunk_prioritizer", true);
        ENABLE_VELOCITY_HINT = b.comment("S14: Send accurate client velocity vector to server for improved read-ahead.")
            .define("enable_velocity_hint", true);
        ENABLE_ENTITY_CULLER = b.comment("S15: Skip rendering of occluded or distant entities.")
            .define("enable_entity_culler", true);
        ENTITY_CULL_RANGE = b.comment("S15: Entities beyond this distance in blocks are always culled from rendering.")
            .defineInRange("entity_cull_range", 64, 16, 256);
        b.pop();
    }

    /**
     * Registers both server and client configs with the Forge config system.
     * Call this from VelocityCoreMod constructor before any other setup.
     *
     * @param modContainer the active ModContainer from FMLJavaModLoadingContext
     */
    public static void register(ModContainer modContainer) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SERVER_SPEC, "velocitycore-server.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC, "velocitycore-client.toml");
    }

    private VCConfig() {}
}
