package com.velocitycore.system;

import com.velocitycore.config.VCConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

/**
 * S12 — PathfindingThrottle
 *
 * Caps the number of nodes the A* pathfinder evaluates per tick for each mob, based on
 * distance from the nearest player and current TPS. Uses ThreadLocal to pass the budget
 * into MixinPathFinder without modifying the PathFinder method signature.
 *
 * Node budgets:
 *   NEAR    (0–32 blocks):  unlimited
 *   MEDIUM  (32–64 blocks): 64 nodes (healthy) / 32 nodes (stressed)
 *   FAR     (>64 blocks):   24 nodes (healthy) / 12 nodes (stressed)
 *
 * See .cursor/prompts/07_mobcapguard.md for full implementation brief.
 */
public final class PathfindingThrottle {

    /** Per-call node budget for the current pathfinding call. */
    private static final ThreadLocal<Integer> nodeBudget = ThreadLocal.withInitial(() -> Integer.MAX_VALUE);

    /** Per-call node evaluation counter. Reset to 0 by prepareBudget(). */
    private static final ThreadLocal<Integer> nodeCount = ThreadLocal.withInitial(() -> 0);

    /**
     * Sets up the per-call node budget for a mob about to pathfind.
     * Called from MixinPathFinder at the start of PathFinder.findPath().
     *
     * @param mob the mob initiating pathfinding (may be null — defaults to MAX_VALUE budget)
     */
    public static void prepareBudget(Mob mob) {
        nodeCount.set(0);
        if (!VCConfig.ENABLE_PATHFINDING_THROTTLE.get() || mob == null) {
            nodeBudget.set(Integer.MAX_VALUE);
            return;
        }
        double distSq = nearestPlayerDistanceSq(mob);
        nodeBudget.set(budgetForDistance(distSq, ChunkGenThrottle.getSmoothedTps()));
    }

    /**
     * Increments the node counter and returns true if the budget is exhausted.
     * Called from MixinPathFinder on each node evaluation step.
     *
     * @return true if pathfinding should be halted (budget exceeded)
     */
    public static boolean exceedsBudget() {
        int count = nodeCount.get() + 1;
        nodeCount.set(count);
        return count > nodeBudget.get();
    }

    /**
     * Computes budget based on squared distance and TPS.
     *
     * @param distSq squared distance to nearest player
     * @param tps    smoothed TPS
     * @return node budget
     */
    private static int budgetForDistance(double distSq, double tps) {
        boolean stressed = tps < 15.0;
        if (distSq <= 32.0 * 32.0) return Integer.MAX_VALUE;
        if (distSq <= 64.0 * 64.0) return stressed ? 32 : 64;
        return stressed ? 12 : 24;
    }

    /**
     * Returns the squared distance to the nearest player for the given mob.
     * Returns 0 (treat as near) if the mob is not in a ServerLevel or no players are present.
     *
     * @param mob the mob
     * @return squared distance to nearest player
     */
    private static double nearestPlayerDistanceSq(Mob mob) {
        if (!(mob.level() instanceof ServerLevel sl)) return 0;
        Player nearest = sl.getNearestPlayer(mob, -1);
        return nearest == null ? 0 : mob.distanceToSqr(nearest);
    }

    private PathfindingThrottle() {}
}
