package dev.toka.rewards;

import cn.nukkit.Player;
import cn.nukkit.plugin.Plugin;
import dev.toka.rewards.core.RewardsService;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Gives the reward for donations. A donation is an entry of a player's note in tokaProfiles ({@code donation amount=500 id=...}); the
 * reward is paid once for each id, and an entry that says {@code paid=...} (money given by the old server) is left alone. The sum
 * is the amount times {@code sponsor.per-unit}, in {@code sponsor.currency}.
 */
final class Sponsors {
    static final String NAMESPACE = "sponsor";
    private final Main plugin;
    private final RewardsService service;
    private final Set<String> done = new HashSet<>();

    Sponsors(Main plugin, RewardsService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @SuppressWarnings("unchecked")
    void check() {
        Plugin profiles = plugin.getServer().getPluginManager().getPlugin("tokaProfiles");
        if (profiles == null || !profiles.isEnabled()) return;
        var config = plugin.getConfig();
        String currency = config.getString("sponsor.currency", "gold");
        double perUnit = config.getDouble("sponsor.per-unit", 1);
        try {
            Method noted = profiles.getClass().getMethod("notedPlayers", String.class);
            Method entries = profiles.getClass().getMethod("noteEntries", UUID.class, String.class);
            for (UUID player : (List<UUID>) noted.invoke(null, "donation")) {
                for (Map<String, String> entry : (List<Map<String, String>>) entries.invoke(null, player, "donation")) pay(player, entry, currency, perUnit);
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
            plugin.getLogger().warning(plugin.messages().text("sponsor.unavailable", unavailable.getClass().getSimpleName()));
        }
    }

    private void pay(UUID player, Map<String, String> entry, String currency, double perUnit) {
        String id = entry.get("id");
        if (id == null || entry.containsKey("paid") || !id.matches("[A-Za-z0-9._-]{1,60}")) return;
        String key = player + "/" + id;
        if (done.contains(key)) return;
        long amount;
        try { amount = (long) Math.floor(Long.parseLong(entry.getOrDefault("amount", "0")) * perUnit); } catch (NumberFormatException bad) { return; }
        if (amount < 1) return;
        service.grant(player, NAMESPACE, id, currency, amount).whenComplete((result, failure) -> {
            if (failure != null) return; // tried again at the next check, with the same id
            done.add(key);
            if ("PAID".equals(result.status())) plugin.sponsorPaid(player, amount);
        });
    }
}
