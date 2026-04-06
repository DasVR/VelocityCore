package com.velocitycore.system;

import com.velocitycore.config.VCConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * S4 — EntityActivationManager
 *
 * Dynamically adjusts mob tick frequency based on distance to the nearest player and TPS.
 * Mobs near players always tick at full rate. Distant mobs tick less frequently under stress.
 * Load is distributed evenly using the mob's entity ID as a tick-offset stagger.
 *
 * Zones and intervals:
 *   NEAR    (0–32 blocks):   always tick
 *   MEDIUM  (32–64 blocks):  interval 2 (healthy) / 4 (stressed)
 *   FAR     (64–96 blocks):  interval 4 (healthy) / 8 (stressed)
 *   DISTANT (>96 blocks):    interval 6 (healthy) / 12 (stressed)
 *
 * Stagger: (gameTick % interval) == (Math.abs(mob.getId()) % interval)
 *
 * See .cursor/prompts/05_entityactivation.md for full implementation brief.
 */
public final class EntityActivationManager {

    /** Synced from ServerTickHandler.gameTick each Phase.END. */
    private static volatile long gameTick = 0L;

    /**
     * Called by ServerTickHandler each Phase.END to update the game tick counter.
     *
     * @param tick the current game tick
     */
    public static void setGameTick(long tick) {
        gameTick = tick;
    }

    /**
     * Returns the current game tick. Used by MixinNaturalSpawner and SpawnRateLimiter.
     *
     * @return current game tick
     */
    public static long getGameTick() {
        return gameTick;
    }

    /**
     * Determines whether the given mob should tick this game tick.
     * Called from MixinMob at the HEAD of Mob.tick().
     *
     * @param mob the mob being evaluated
     * @return true if the mob should tick normally, false if its tick should be skipped
     */
    public static boolean shouldTick(Mob mob) {
        if (!VCConfig.ENABLE_AI_THROTTLE.get()) return true;

        Level level = mob.level();
        if (!(level instanceof ServerLevel serverLevel)) return true;

        double nearestDistSq = nearestPlayerDistanceSq(serverLevel, mob);
        if (nearestDistSq < 0) return true;

        int interval = intervalForDistance(nearestDistSq, ChunkGenThrottle.getSmoothedTps());
        if (interval <= 1) return true;

        return (gameTick % interval) == (Math.abs(mob.getId()) % interval);
    }

    /**
     * Returns the squared distance to the nearest player, or -1 if no players are present.
     *
     * @param level the server level
     * @param mob   the mob to measure from
     * @return squared distance or -1
     */
    private static double nearestPlayerDistanceSq(ServerLevel level, Mob mob) {
        Player nearest = level.getNearestPlayer(mob, -1);
        if (nearest == null) return -1;
        return mob.distanceToSqr(nearest);
    }

    /**
     * Maps squared distance and TPS to the correct tick interval.
     *
     * @param distSq squared distance to nearest player in blocks
     * @param tps    smoothed TPS
     * @return tick interval (1 = always tick, 12 = max throttle)
     */
    private static int intervalForDistance(double distSq, double tps) {
        boolean stressed = tps < 15.0;
        if (distSq <= 32.0 * 32.0) return 1;
        if (distSq <= 64.0 * 64.0) return stressed ? 4 : 2;
        if (distSq <= 96.0 * 96.0) return stressed ? 8 : 4;
        return stressed ? 12 : 6;
    }

    private EntityActivationManager() {}
}
