package com.velocitycore.system;

import com.velocitycore.config.VCConfig;

/**
 * S1 — ChunkGenThrottle
 *
 * Tracks a nanosecond budget per tick using an exponential moving average (EMA, alpha=0.1) on
 * measured tick durations to derive smoothed TPS. Budget tiers scale dynamically:
 *   TPS >= 19.5  →  6 ms
 *   TPS >= 17.0  →  3 ms
 *   TPS >= 15.0  →  1 ms
 *   TPS  < 15.0  →  0.2 ms
 *
 * All downstream systems share this single budget object. hasBudget() is the universal gate.
 *
 * See .cursor/prompts/02_chunkgenthrottle.md for full implementation brief.
 */
public final class ChunkGenThrottle {

    private static final double EMA_ALPHA = 0.1;

    private static long tickStartNanos = 0L;
    private static long consumedNanos = 0L;
    private static double smoothedTickNanos = 50_000_000.0;
    private static volatile double smoothedTps = 20.0;

    /**
     * Called at ServerTickEvent.Phase.START by ServerTickHandler.
     * Records tick start time, resets consumed budget, updates EMA from previous tick.
     */
    public static void beginTick() {
        long now = System.nanoTime();
        if (tickStartNanos != 0L && VCConfig.ENABLE_TPS_TRACKING.get()) {
            long elapsed = now - tickStartNanos;
            smoothedTickNanos = (EMA_ALPHA * elapsed) + ((1.0 - EMA_ALPHA) * smoothedTickNanos);
            double calculated = 1_000_000_000.0 / smoothedTickNanos;
            smoothedTps = Math.min(20.0, calculated);
        } else if (!VCConfig.ENABLE_TPS_TRACKING.get()) {
            smoothedTps = 20.0;
        }
        tickStartNanos = now;
        consumedNanos = 0L;
    }

    /**
     * Returns true if there is remaining budget for generation work this tick.
     *
     * @return true if (consumedNanos < budgetNanos())
     */
    public static boolean hasBudget() {
        if (!VCConfig.ENABLE_TPS_TRACKING.get() || !VCConfig.ENABLE_GEN_THROTTLE.get()) return true;
        return consumedNanos < budgetNanos();
    }

    /**
     * Charges nanoseconds against the current tick's budget.
     *
     * @param nanos nanoseconds consumed by the work unit
     */
    public static void charge(long nanos) {
        if (nanos <= 0L) return;
        consumedNanos += nanos;
    }

    /**
     * Returns the current smoothed TPS as computed by the EMA.
     *
     * @return smoothed TPS in range approximately [0, 20]
     */
    public static double getSmoothedTps() {
        return smoothedTps;
    }

    /**
     * Returns a human-readable status string for the /velocitycore status command.
     * Format: "TPS=18.4 budget=3ms consumed=1.2ms tier=MEDIUM"
     *
     * @return status string
     */
    public static String getStatusString() {
        long budget = budgetNanos();
        String tier;
        if (smoothedTps >= 19.5) tier = "HIGH";
        else if (smoothedTps >= 17.0) tier = "MEDIUM";
        else if (smoothedTps >= 15.0) tier = "LOW";
        else tier = "CRITICAL";
        return String.format(
            "TPS=%.1f budget=%.1fms consumed=%.1fms tier=%s",
            getSmoothedTps(),
            budget / 1_000_000.0,
            consumedNanos / 1_000_000.0,
            tier
        );
    }

    private static long budgetNanos() {
        if (!VCConfig.ENABLE_TPS_TRACKING.get() || !VCConfig.ENABLE_GEN_THROTTLE.get()) {
            return Long.MAX_VALUE;
        }
        if (smoothedTps >= 19.5) return 6_000_000L;
        if (smoothedTps >= 17.0) return 3_000_000L;
        if (smoothedTps >= 15.0) return 1_000_000L;
        return 200_000L;
    }

    private ChunkGenThrottle() {}
}
