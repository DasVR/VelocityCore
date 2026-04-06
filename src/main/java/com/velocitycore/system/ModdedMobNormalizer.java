package com.velocitycore.system;

import com.velocitycore.config.VCConfig;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        tryResolveWeightField();

        Registry<Biome> biomeRegistry = server.registryAccess().registryOrThrow(Registries.BIOME);
        int scanned = 0;
        int adjustedBiomes = 0;
        int changedEntries = 0;
        int minWeight = VCConfig.MOB_NORMALIZER_MIN_WEIGHT.get();
        int maxWeight = VCConfig.MOB_NORMALIZER_MAX_WEIGHT.get();

        Set<String> exemptions = new HashSet<>();
        for (String id : VCConfig.MOB_NORMALIZER_EXEMPTIONS.get()) {
            exemptions.add(id.toLowerCase());
        }

        for (Map.Entry<ResourceKey<Biome>, Biome> biomeEntry : biomeRegistry.entrySet()) {
            scanned++;
            ResourceLocation biomeId = biomeEntry.getKey().location();
            Biome biome = biomeEntry.getValue();
            MobSpawnSettings settings = biome.getMobSettings();
            boolean biomeAdjusted = false;

            for (MobCategory category : MobCategory.values()) {
                List<MobSpawnSettings.SpawnerData> list = extractSpawnerDataList(settings, category);
                if (list.isEmpty()) continue;

                int baseline = VANILLA_BASELINES.getOrDefault(category, 100);
                int totalWeight = 0;
                for (MobSpawnSettings.SpawnerData data : list) {
                    totalWeight += readWeight(data);
                }

                if (totalWeight <= (int) (baseline * 1.5)) continue;
                LOGGER.info("Biome: {} | Category: {} | Total weight: {} (baseline {}) — normalizing {} entries",
                    biomeId, category, totalWeight, baseline, list.size());

                boolean categoryAdjusted = false;
                for (MobSpawnSettings.SpawnerData data : list) {
                    EntityType<?> type = data.type;
                    ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(type);
                    if (key == null) continue;
                    String namespace = key.getNamespace();
                    if ("minecraft".equals(namespace) || exemptions.contains(namespace) || isExempted(type)) {
                        LOGGER.info("  {} -> SKIPPED (mod exempted or vanilla)", key);
                        continue;
                    }

                    int old = readWeight(data);
                    int normalized = clamp((int) Math.round(old * (baseline / (double) totalWeight)), minWeight, maxWeight);
                    if (normalized != old && writeWeight(data, normalized)) {
                        changedEntries++;
                        categoryAdjusted = true;
                        LOGGER.info("  {} {} -> {}", key, old, normalized);
                    }
                }

                if (categoryAdjusted) {
                    biomeAdjusted = true;
                }
            }

            if (biomeAdjusted) adjustedBiomes++;
        }

        LOGGER.info("Normalization complete: {} biomes scanned, {} adjusted, {} entries modified.",
            scanned, adjustedBiomes, changedEntries);
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

    private static void tryResolveWeightField() {
        if (spawnerDataWeightField != null) return;
        String[] candidates = {"weight", "f_48351_"};
        for (String name : candidates) {
            try {
                Field f = MobSpawnSettings.SpawnerData.class.getDeclaredField(name);
                f.setAccessible(true);
                spawnerDataWeightField = f;
                return;
            } catch (ReflectiveOperationException ignored) {
                // Continue searching candidates.
            }
        }
        LOGGER.warn("Unable to resolve MobSpawnSettings.SpawnerData weight field; normalization will be read-only.");
    }

    private static int readWeight(MobSpawnSettings.SpawnerData data) {
        if (spawnerDataWeightField == null) return 0;
        try {
            Object value = spawnerDataWeightField.get(data);
            if (value instanceof Integer i) return i;
            // Mojmap weight wrapper fallback.
            if (value != null) {
                try {
                    return (int) value.getClass().getMethod("asInt").invoke(value);
                } catch (ReflectiveOperationException ignored) {
                    return 0;
                }
            }
        } catch (IllegalAccessException ignored) {
            return 0;
        }
        return 0;
    }

    private static boolean writeWeight(MobSpawnSettings.SpawnerData data, int value) {
        if (spawnerDataWeightField == null) return false;
        try {
            Object old = spawnerDataWeightField.get(data);
            if (old instanceof Integer) {
                spawnerDataWeightField.setInt(data, value);
                return true;
            }
            if (old != null) {
                // Best-effort for wrapped weight types: leave unchanged if constructor is unknown.
                return false;
            }
            return false;
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("Failed to set normalized weight for {}: {}", data.type, e.getMessage());
            return false;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static List<MobSpawnSettings.SpawnerData> extractSpawnerDataList(MobSpawnSettings settings, MobCategory category) {
        List<MobSpawnSettings.SpawnerData> out = new ArrayList<>();
        Object weightedList = settings.getMobs(category);
        if (weightedList == null) return out;
        try {
            Object unwrapped = weightedList.getClass().getMethod("unwrap").invoke(weightedList);
            if (!(unwrapped instanceof Iterable<?> iterable)) return out;
            for (Object wrapper : iterable) {
                Object data;
                try {
                    data = wrapper.getClass().getMethod("getData").invoke(wrapper);
                } catch (ReflectiveOperationException e) {
                    data = wrapper.getClass().getMethod("data").invoke(wrapper);
                }
                if (data instanceof MobSpawnSettings.SpawnerData spawnerData) {
                    out.add(spawnerData);
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // Leave list empty; normalization remains conservative.
        }
        return out;
    }

    private ModdedMobNormalizer() {}
}
