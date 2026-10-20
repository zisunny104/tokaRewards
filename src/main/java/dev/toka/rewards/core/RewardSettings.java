package dev.toka.rewards.core;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Map;

public record RewardSettings(ZoneId zone, String currency, long base, long consecutive,
                             long voteThreshold, BigDecimal voteMultiplier, int cacheSize, int maxPending,
                             boolean dailyEnabled, boolean showOnJoin, int batchSize) {
    public static RewardSettings parse(Map<?, ?> root) {
        Map<?, ?> daily = map(root, "daily"), storage = map(root, "storage"), votes = map(root, "vote");
        String currency = String.valueOf(daily.get("currency"));
        if (!currency.matches("[a-z0-9]{2,16}")) throw new IllegalArgumentException("daily.currency");
        BigDecimal multiplier = new BigDecimal(String.valueOf(votes.get("bonus-multiplier")));
        if (multiplier.signum() < 0 || multiplier.compareTo(BigDecimal.valueOf(100)) > 0) throw new IllegalArgumentException("vote.bonus-multiplier");
        return new RewardSettings(ZoneId.of(String.valueOf(daily.get("timezone"))), currency,
                number(daily, "base-amount", 1, Long.MAX_VALUE), number(daily, "consecutive-amount", 1, Long.MAX_VALUE),
                number(votes, "bonus-threshold", 0, Long.MAX_VALUE), multiplier,
                (int) number(storage, "cache-entries", 1, 100000), (int) number(storage, "max-pending", 1, 100000),
                Boolean.TRUE.equals(daily.get("enabled")), Boolean.TRUE.equals(daily.get("show-on-join")),
                (int) number(storage, "batch-size", 1, 1000));
    }
    private static Map<?, ?> map(Map<?, ?> root, String name) {
        if (root.get(name) instanceof Map<?, ?> value) return value;
        throw new IllegalArgumentException(name);
    }
    private static long number(Map<?, ?> map, String key, long min, long max) {
        long value = new BigDecimal(String.valueOf(map.get(key))).longValueExact();
        if (value < min || value > max) throw new IllegalArgumentException(key);
        return value;
    }
}
