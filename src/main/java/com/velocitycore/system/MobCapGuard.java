package com.velocitycore.system;

import com.velocitycore.config.VCConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypeTest;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;

import java.util.Map;

/**
 * S11 — MobCapGuard
 *
 * Adds a per-category soft mob cap that scales with TPS. When the server is healthy, caps are
 * loose. Under stress (TPS < 15), all caps are halved. Soft caps only restrict new natural
 * spawning — existing entities are never removed.
 *
 * Default base caps:
 *   MONSTER=70, CREATURE=10, AMBIENT=15, WATER_CREATURE=5, WATER_AMBIENT=20, MISC=5
 *
 * Injected via MixinNaturalSpawner.vc_mobCapGuardCheck().
 *
 * See .cursor/prompts/07_mobcapguard.md for full implementation brief.
 */
public final class MobCapGuard {

    private static final Map<MobCategory, Integer> BASE_CAPS = Map.of(
        MobCategory.MONSTER,         70,
        MobCategory.CREATURE,        10,
        MobCategory.AMBIENT,         15,
        MobCategory.WATER_CREATURE,   5,
        MobCategory.WATER_AMBIENT,   20,
        MobCategory.MISC,             5
    );

    /**
     * Returns true if the given category has met or exceeded its soft cap in the given level.
     * Called from MixinNaturalSpawner.vc_mobCapGuardCheck().
     *
     * If VCConfig.ENABLE_MOB_CAP_GUARD is false, always returns false.
     *
     * @param category the MobCategory being evaluated
     * @param level    the ServerLevel to count entities in
     * @return true if spawning for this category should be blocked
     */
    public static boolean isCategoryOver(MobCategory category, ServerLevel level) {
        if (!VCConfig.ENABLE_MOB_CAP_GUARD.get()) return false;
        int softCap = effectiveCap(category);
        int current = level.getEntities(EntityTypeTest.forClass(Entity.class),
            e -> e instanceof Mob m && m.getType().getCategory() == category).size();
        return current >= softCap;
    }

    /**
     * Computes the effective soft cap for the given category based on current TPS.
     *
     * @param category the MobCategory
     * @return effective soft cap
     */
    private static int effectiveCap(MobCategory category) {
        int base = BASE_CAPS.getOrDefault(category, 10);
        double tps = ChunkGenThrottle.getSmoothedTps();
        if (tps < 15.0) return Math.max(2, base / 2);
        return base;
    }

    private MobCapGuard() {}
}
