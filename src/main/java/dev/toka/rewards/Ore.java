package dev.toka.rewards;

import cn.nukkit.Player;
import cn.nukkit.block.BlockID;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.item.enchantment.Enchantment;
import cn.nukkit.plugin.Plugin;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A chance of money for mining ore in the regions that are set. What each ore gives is a line of the configuration (a chance out of
 * 300, and a smallest and largest amount); mining with Silk Touch gives nothing. What is won is added up for a few seconds and paid
 * in one go, so mining does not write a line of the ledger for every block.
 */
final class Ore implements Listener {
    record Rule(int chance, int min, int max) {}

    private final Main plugin;
    private final Map<String, Rule> rules = new HashMap<>();
    private final Map<UUID, Long> owed = new ConcurrentHashMap<>();
    private List<String> regions = List.of();
    private String currency = "money";
    private boolean cancelWhenAway = true;

    Ore(Main plugin) { this.plugin = plugin; }

    boolean enabled() { return plugin.getConfig().getBoolean("ore.enabled", false); }

    /** Reads the configuration again. */
    void load() {
        var config = plugin.getConfig();
        currency = config.getString("ore.currency", "money");
        regions = config.getStringList("ore.regions");
        cancelWhenAway = config.getBoolean("ore.away-cancels", true);
        rules.clear();
        var table = config.getSection("ore.table");
        for (String key : table.getKeys(false)) {
            int chance = table.getInt(key + ".chance"), min = table.getInt(key + ".min"), max = table.getInt(key + ".max");
            if (chance > 0 && min >= 0 && max >= min) rules.put(key, new Rule(chance, min, max));
        }
    }

    static String kind(int id) {
        if (id == BlockID.STONE) return "stone";
        if (id == BlockID.COAL_ORE) return "coal";
        if (id == BlockID.IRON_ORE) return "iron";
        if (id == BlockID.GOLD_ORE) return "gold";
        if (id == BlockID.REDSTONE_ORE || id == BlockID.LIT_REDSTONE_ORE) return "redstone";
        if (id == BlockID.QUARTZ_ORE) return "quartz";
        if (id == BlockID.DIAMOND_ORE) return "diamond";
        if (id == BlockID.LAPIS_ORE) return "lapis";
        if (id == BlockID.EMERALD_ORE) return "emerald";
        return null;
    }

    private Method method(String pluginName, String type, String name, Class<?>... parameters) {
        Plugin found = plugin.getServer().getPluginManager().getPlugin(pluginName);
        if (found == null || !found.isEnabled()) return null;
        try { return Class.forName(type, true, found.getClass().getClassLoader()).getMethod(name, parameters); }
        catch (ReflectiveOperationException | LinkageError missing) { return null; }
    }

    private boolean away(Player player) {
        Method asks = method("tokaAnchor", "dev.toka.anchor.Main", "isAfk", UUID.class);
        try { return asks != null && Boolean.TRUE.equals(asks.invoke(null, player.getUniqueId())); }
        catch (ReflectiveOperationException | LinkageError unavailable) { return false; }
    }

    private String regionAt(Player player) {
        Method asks = method("tokaRegions", "dev.toka.regions.Main", "regionCodeAt", String.class, int.class, int.class, int.class);
        try { return asks == null ? null : (String) asks.invoke(null, player.getLevel().getFolderName(), player.getFloorX(), player.getFloorY(), player.getFloorZ()); }
        catch (ReflectiveOperationException | LinkageError unavailable) { return null; }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void onBreak(BlockBreakEvent event) {
        if (!enabled()) return;
        String kind = kind(event.getBlock().getId());
        if (kind == null) return;
        Player player = event.getPlayer();
        if (cancelWhenAway && away(player)) { event.setCancelled(); return; }
        Rule rule = rules.get(kind);
        if (rule == null || (event.getItem() != null && event.getItem().getEnchantment(Enchantment.ID_SILK_TOUCH) != null)) return;
        String region = regionAt(player);
        if (region == null || regions.stream().noneMatch(region::equalsIgnoreCase)) return;
        if (ThreadLocalRandom.current().nextInt(301) >= rule.chance()) return;
        long won = rule.min() + ThreadLocalRandom.current().nextInt(rule.max() - rule.min() + 1);
        if (won < 1) return;
        owed.merge(player.getUniqueId(), won, Long::sum);
        var faces = plugin.messages().lines("ore.faces");
        tell(player, plugin.messages().text("ore.found", won, faces.isEmpty() ? "" : faces.get(ThreadLocalRandom.current().nextInt(faces.size()))));
    }

    /** The message goes under the base information of tokaDisplay for two seconds; without it, into the chat. */
    private void tell(Player player, String text) {
        Method flash = method("tokaDisplay", "dev.toka.display.Main", "flash", Player.class, String.class, int.class, float.class, String.class);
        try { if (flash != null) { flash.invoke(null, player, text, 2, -1f, null); return; } }
        catch (ReflectiveOperationException | LinkageError unavailable) { /* chat below */ }
        player.sendMessage(text);
    }

    /** Pays what has been won since the last time. */
    void pay() {
        if (owed.isEmpty()) return;
        Method deposit = null;
        Object cause = null;
        try {
            Class<?> economy = Class.forName("dev.toka.economy.Economy"), why = Class.forName("dev.toka.economy.Cause");
            deposit = economy.getMethod("deposit", UUID.class, String.class, long.class, why);
            cause = why.getConstructor(String.class, String.class).newInstance("reward", "tokaRewards");
        } catch (ReflectiveOperationException | LinkageError noEconomy) { return; }
        for (UUID player : List.copyOf(owed.keySet())) {
            Long amount = owed.remove(player);
            if (amount == null || amount < 1) continue;
            try { deposit.invoke(null, player, currency, amount, cause); }
            catch (ReflectiveOperationException | LinkageError failed) { owed.merge(player, amount, Long::sum); }
        }
    }
}
