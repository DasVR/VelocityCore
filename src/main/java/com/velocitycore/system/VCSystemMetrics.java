package com.velocitycore.system;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * Centralized runtime counters for /velocitycore observability commands.
 */
public final class VCSystemMetrics {

    private static final long ONE_MINUTE_TICKS = 20L * 60L;

    private static final Map<String, LongAdder> minuteCounters = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> totalCounters = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> lastRunTick = new ConcurrentHashMap<>();
    private static volatile long minuteWindowStartTick = -1L;
    private static volatile boolean debugEnabled = false;

    public static void markSystemRun(String system, long gameTick) {
        rotateMinuteWindowIfNeeded(gameTick);
        lastRunTick.computeIfAbsent(system, k -> new AtomicLong(-1L)).set(gameTick);
        increment(system + ".runs", 1L, gameTick);
    }

    public static void increment(String key, long delta, long gameTick) {
        if (delta <= 0L) return;
        rotateMinuteWindowIfNeeded(gameTick);
        minuteCounters.computeIfAbsent(key, k -> new LongAdder()).add(delta);
        totalCounters.computeIfAbsent(key, k -> new LongAdder()).add(delta);
    }

    public static long getLastRunTick(String system) {
        AtomicLong v = lastRunTick.get(system);
        return v == null ? -1L : v.get();
    }

    public static String countersReport() {
        String minute = minuteCounters.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> e.getKey() + "=" + e.getValue().sum())
            .collect(Collectors.joining(", "));
        String total = totalCounters.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> e.getKey() + "=" + e.getValue().sum())
            .collect(Collectors.joining(", "));
        return "minute={" + minute + "} total={" + total + "}";
    }

    public static long getTotalCounter(String key) {
        LongAdder v = totalCounters.get(key);
        return v == null ? 0L : v.sum();
    }

    public static void resetCounters(long gameTick) {
        minuteCounters.clear();
        totalCounters.clear();
        minuteWindowStartTick = gameTick;
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    private static void rotateMinuteWindowIfNeeded(long gameTick) {
        long start = minuteWindowStartTick;
        if (start < 0L) {
            minuteWindowStartTick = gameTick;
            return;
        }
        if ((gameTick - start) >= ONE_MINUTE_TICKS) {
            minuteCounters.clear();
            minuteWindowStartTick = gameTick;
        }
    }

    private VCSystemMetrics() {}
}
