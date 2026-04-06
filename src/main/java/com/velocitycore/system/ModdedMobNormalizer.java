package com.velocitycore.system;

import com.velocitycore.config.VCConfig;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * S10 — ModdedMobNormalizer
 *
 * Runs once at server startup after all mods have completed registration. Iterates every biome's
 * MobSpawnSettings and normalizes each MobCategory spawn list so modded mobs don't overwhelm
 * the vanilla pool or each other.
 *
 * Formula: weight = clamp(weight * (vanillaBaseline / actualTotal), MIN_WEIGHT, MAX_WEIGHT)
 *
 * Only normalizes if actualTotal > vanillaBaseline * 1.5 (pool is inflated).
 * Does NOT normalize vanilla namespace ("minecraft") entries.
 * Per-mod exemption list in VCConfig.MOB_NORMALIZER_EXEMPTIONS.
 *
 * Logs a full adjustment report to the server log on completion.
 *
 * See .cursor/prompts/06_moddedmobnormalizer.md for full implementation brief.
 */
public final class ModdedMobNormalizer {

    private static final Logger LOGGER = LogManager.getLogger("VelocityCore/MobNormalizer");

    private static boolean hasRun = false;

    /** Approximate vanilla total pool weights per MobCategory for a typical overworld biome. */
    private static final Map<MobCategory, Integer> VANILLA_BASELINES = Map.of(
        MobCategory.MONSTER,         615,
        MobCategory.CREATURE,         60,
        MobCategory.AMBIENT,          10,
        MobCategory.WATER_CREATURE,   10,
        MobCategory.WATER_AMBIENT,    25,
        MobCategory.MISC,              5
    );

    /** Cached reflection Field for MobSpawnSettings.SpawnerData.weight. Set once, reused for all entries. */
    private static Field spawnerDataWeightField = null;

    /**
     * Scans and normalizes all biome spawn pools.
     * Called from ServerTickHandler.onServerAboutToStart() after mod registration completes.
     * Does nothing if ENABLE_MOB_NORMALIZER is false or hasRun() is true.
     *
     * @param server the MinecraftServer (used to access the biome registry)
     */
    public static void normalize(MinecraftServer server) {
        if (!VCConfig.ENABLE_MOB_NORMALIZER.get()) return;
        if (hasRun) return;
        hasRun = true;

        LOGGER.info("Starting spawn weight normalization...");
        // TODO: implement — see prompts/06_moddedmobnormalizer.md
        LOGGER.info("Normalization complete.");
    }

    /**
     * Returns true if the given EntityType belongs to an exempted mod.
     * Checks the ResourceLocation namespace against VCConfig.MOB_NORMALIZER_EXEMPTIONS.
     *
     * @param type the EntityType to check
     * @return true if this type's mod is exempted
     */
    private static boolean isExempted(EntityType<?> type) {
        var key = ForgeRegistries.ENTITY_TYPES.getKey(type);
        if (key == null) return false;
        return VCConfig.MOB_NORMALIZER_EXEMPTIONS.get().contains(key.getNamespace());
    }

    /**
     * Returns whether normalization has already been performed this server session.
     *
     * @return true if normalize() has completed
     */
    public static boolean hasRun() {
        return hasRun;
    }

    private ModdedMobNormalizer() {}
}
