package dev.toka.rewards;

import cn.nukkit.Player;
import cn.nukkit.command.*;
import cn.nukkit.command.data.CommandParameter;
import cn.nukkit.command.data.CommandParamType;
import cn.nukkit.event.*;
import cn.nukkit.event.player.*;
import cn.nukkit.event.plugin.*;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.window.FormWindowSimple;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.Server;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.plugin.PluginBase;
import com.google.gson.*;
import dev.toka.i18n.Messages;
import dev.toka.rewards.core.*;
import dev.toka.storage.*;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class Main extends PluginBase implements Listener {
    private Messages messages;
    private RewardsService service;
    private VoteClient votes;
    private Backend backend;
    private Object plan;
    private dev.toka.rewards.plan.PlanControl planControl;
    private cn.nukkit.scheduler.TaskHandler retryTask, planTask;
    private final Map<UUID, Integer> forms = new HashMap<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        suggest("rewards",
                new CommandParameter[]{CommandParameter.newEnum("動作", new String[]{"help","claim","status","auto","reload","retry","grant","sponsor","plan"})},
                new CommandParameter[]{CommandParameter.newEnum("動作", new String[]{"sponsor"}), CommandParameter.newType("玩家", CommandParamType.STRING), CommandParameter.newType("金額", CommandParamType.INT), CommandParameter.newType("說明", true, CommandParamType.RAWTEXT)},
                new CommandParameter[]{CommandParameter.newEnum("動作", new String[]{"auto"}), CommandParameter.newEnum("自動領取", new String[]{"on","off"})},
                new CommandParameter[]{CommandParameter.newEnum("動作", new String[]{"grant"}), CommandParameter.newType("玩家UUID", CommandParamType.STRING), CommandParameter.newType("命名空間", CommandParamType.STRING), CommandParameter.newType("操作", CommandParamType.STRING), CommandParameter.newType("貨幣", CommandParamType.STRING), CommandParameter.newType("金額", CommandParamType.INT)},
                new CommandParameter[]{CommandParameter.newEnum("動作", new String[]{"plan"}), CommandParameter.newEnum("Plan動作", new String[]{"status"})},
                new CommandParameter[]{CommandParameter.newEnum("動作", new String[]{"plan"}), CommandParameter.newEnum("Plan動作", new String[]{"mode"}), CommandParameter.newEnum("模式", new String[]{"auto","enable","disable"})},
                new CommandParameter[]{CommandParameter.newEnum("動作", new String[]{"plan"}), CommandParameter.newEnum("Plan動作", new String[]{"fields"}), CommandParameter.newType("欄位", CommandParamType.RAWTEXT)});
        try {
            messages = new Messages(getClass().getClassLoader(), getDataFolder().toPath(), getConfig().getString("language"), getLogger()::warning);
            backend = Backend.valueOf(getConfig().getString("storage.backend").toUpperCase(Locale.ROOT));
            RewardSettings settings = RewardSettings.parse(getConfig().getAll());
            votes = new VoteClient(VoteClient.Settings.parse(getConfig().getAll()), Clock.systemUTC());
            service = new RewardsService(DocumentStore.open(new StoreConfig(backend,
                    getDataFolder().toPath().resolve("storage").resolve(backend.name().toLowerCase(Locale.ROOT)), 1)),
                    settings, Clock.systemUTC(), Payments::deposit, error -> getLogger().error(text("log.failed"), error));
            getServer().getPluginManager().registerEvents(this, this);
            planControl = new dev.toka.rewards.plan.PlanControl(this, () -> { closePlan(); attachPlan(); },
                    () -> Set.of("streak","total","last-day","automatic","pending"),
                    (key,args) -> text(key,args), "rewards");
            ore = new Ore(this);
            ore.load();
            getServer().getPluginManager().registerEvents(ore, this);
            scheduleTasks();
            attachPlan();
            getLogger().info(text("log.ready", backend.name().toLowerCase(Locale.ROOT)));
        } catch (Exception | LinkageError error) {
            getLogger().error(messages == null ? "獎勵系統設定或儲存載入失敗。" : text("log.failed"), error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void suggest(String name, CommandParameter[]... forms) {
        Command registered = (Command) getCommand(name);
        if (registered == null) return;
        registered.getCommandParameters().clear();
        for (int i = 0; i < forms.length; i++) registered.addCommandParameters("choice" + i, forms[i]);
    }
    private int seconds(String key) {
        int value = getConfig().getInt(key);
        if (value < 1 || value > 3600) throw new IllegalArgumentException(key); return value;
    }
    private Ore ore;
    private cn.nukkit.scheduler.TaskHandler oreTask;

    private void scheduleTasks() {
        int retry=seconds("maintenance.retry-seconds"),refresh=seconds("plan.refresh-seconds");
        if(oreTask!=null)oreTask.cancel();
        int payEvery=Math.max(2,Math.min(120,getConfig().getInt("ore.pay-seconds",10)));
        oreTask=getServer().getScheduler().scheduleDelayedRepeatingTask(this,()->ore.pay(),payEvery*20,payEvery*20);
        if(retryTask!=null)retryTask.cancel();if(planTask!=null)planTask.cancel();
        retryTask=getServer().getScheduler().scheduleDelayedRepeatingTask(this,()->service.retryPending(),retry*20,retry*20);
        planTask=getServer().getScheduler().scheduleDelayedRepeatingTask(this,this::publishPlan,refresh*20,refresh*20);
    }
    @Override public void onDisable() {
        if (ore != null) ore.pay();
        closePlan(); forms.clear();
        if (votes != null) votes.close();
        if (service != null) try { service.close(); } catch (Exception error) { getLogger().error(text("log.failed"), error); }
    }
    public RewardsService service() { return service; }
    public CompletableFuture<RewardsService.Result> grant(UUID uuid, String namespace, String operation, String currency, long amount) {
        return service.grant(uuid, namespace, operation, currency, amount);
    }
    private String text(String key, Object... args) { return messages.text(key, args); }
    public dev.toka.i18n.Messages messages() { return messages; }
    /**
     * /rewards sponsor <player> <amount> [text]: the reward for a donation, given once now. The donation is also written in the
     * player's note in tokaProfiles ({@code donation amount= id= reward=}), which is the record of who gave what.
     */
    private void sponsor(CommandSender sender, String[] args) {
        UUID uuid; long donated;
        try {
            Player online = getServer().getPlayerExact(args[1]);
            uuid = online != null ? online.getUniqueId() : args[1].matches("[0-9a-fA-F-]{36}") ? UUID.fromString(args[1]) : getServer().getOfflinePlayer(args[1]).getUniqueId();
            donated = Long.parseLong(args[2]);
            if (donated < 1 || donated > 100_000_000L) throw new NumberFormatException();
        } catch (RuntimeException bad) { send(sender, "sponsor.usage"); return; }
        String currency = getConfig().getString("sponsor.currency", "coin");
        long reward = (long) Math.floor(donated * getConfig().getDouble("sponsor.per-unit", 1));
        if (reward < 1) { send(sender, "sponsor.usage"); return; }
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        String id = donationId();
        String said = args.length > 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)) : "";
        String line = "donation amount=" + donated + " id=" + id + " reward=" + currency + ":" + reward + " date=" + now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")) + (said.isBlank() ? "" : " # " + said);
        service.grant(uuid, "sponsor", id, currency, reward).whenComplete((result, error) -> {
            if (error != null) { error(sender, error); return; }
            recordDonation(uuid, line);
            main(() -> {
                send(sender, "sponsor.done", getServer().getOfflinePlayer(uuid).getName(), donated, reward, result.status());
                Player online = getServer().getOnlinePlayers().get(uuid);
                if (online != null) online.sendMessage(text("sponsor.paid", reward));
            });
        });
    }

    /** A random key such as K7QM-2XPD-9TWA: nobody can guess one, and one is never given twice. */
    private static String donationId() {
        String letters = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        var random = new java.security.SecureRandom();
        var out = new StringBuilder();
        for (int i = 0; i < 12; i++) { if (i > 0 && i % 4 == 0) out.append('-'); out.append(letters.charAt(random.nextInt(letters.length()))); }
        return out.toString();
    }

    private void recordDonation(UUID uuid, String line) {
        Plugin profiles = getServer().getPluginManager().getPlugin("tokaProfiles");
        if (profiles == null || !profiles.isEnabled()) { getLogger().warning(text("sponsor.noRecord", line)); return; }
        try { profiles.getClass().getMethod("appendNote", UUID.class, String.class).invoke(null, uuid, line); }
        catch (ReflectiveOperationException | LinkageError unavailable) { getLogger().warning(text("sponsor.noRecord", line)); }
    }
    private void main(Runnable task) {
        if (isEnabled()) getServer().getScheduler().scheduleTask(this, () -> { if (isEnabled()) task.run(); });
    }
    private void send(CommandSender sender, String key, Object... args) { sender.sendMessage(text(key, args)); }
    private void error(CommandSender sender, Throwable error) {
        main(() -> { send(sender,"err.failed"); getLogger().error(text("log.failed"),error); });
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        service.profile(player.getUniqueId()).whenComplete((profile, error) -> main(() -> {
            if (!player.isOnline() || error != null || !service.settings().dailyEnabled()) return;
            publish(player.getUniqueId(), profile);
            // already checked in today: nothing to offer, so no window and no message
            if (profile.get("lastDay").getAsString().equals(java.time.LocalDate.now(service.settings().zone()).toString())) return;
            if (profile.get("automatic").getAsBoolean()) claim(player);
            else if (service.settings().showOnJoin()) show(player, profile);
        }));
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { forms.remove(event.getPlayer().getUniqueId()); }
    @EventHandler public void onForm(PlayerFormRespondedEvent event) {
        Player player = event.getPlayer();
        Integer form = forms.get(player.getUniqueId());
        if (form == null || form != event.getFormID()) return;
        forms.remove(player.getUniqueId());
        if (!event.wasClosed() && event.getResponse() instanceof FormResponseSimple response && response.getClickedButtonId() == 0
                && player.hasPermission("tokaRewards.use")) claim(player);
    }
    private void show(Player player, JsonObject profile) {
        FormWindowSimple form = new FormWindowSimple(text("ui.title"), text("ui.body", profile.get("lastDay").getAsString(), profile.get("streak").getAsInt(), profile.get("total").getAsLong())
                + (votes.url().isBlank() ? "" : "\n"+text("ui.vote", votes.url())));
        form.addButton(new ElementButton(text("ui.claim"))); form.addButton(new ElementButton(text("ui.back")));
        forms.put(player.getUniqueId(), player.showFormWindow(form));
    }
    private void claim(Player player) {
        if (!player.hasPermission("tokaRewards.use")) { send(player,"err.permission"); return; }
        UUID uuid = player.getUniqueId(); String name = player.getName();
        send(player,"msg.processing");
        votes.lookup(name).thenCompose(vote -> {
                    // with claiming on, only a vote that has not been claimed earns the bonus, and it is claimed once the reward is paid
                    boolean claiming = votes.claimEnabled() && service.settings().voteMultiplier().signum() > 0;
                    boolean bonus = vote.available() && (!claiming || vote.unclaimed());
                    return service.daily(uuid,name,bonus?vote.votes():-1).thenApply(result -> {
                        if (claiming && vote.available() && vote.unclaimed() && "PAID".equals(result.status())) votes.claim(name);
                        return result;
                    });
                })
                .whenComplete((result,error) -> main(() -> {
                    if (error != null) { getLogger().error(text("log.failed"),error); if(player.isOnline())send(player,"err.failed"); return; }
                    if(player.isOnline()) send(player,"msg."+result.status(), result.amount()+" "+service.settings().currency(),result.streak());
                    publishPlanPlayer(uuid);
                }));
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (service == null) { send(sender,"err.failed"); return true; }
        if(planControl!=null && planControl.command(sender,args,"tokaRewards.admin"))return true;
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission("tokaRewards.admin"), use = sender.hasPermission("tokaRewards.use");
        if (action.equals("help") && args.length <= 1) {
            send(sender,"help.title");
            if (use && sender instanceof Player) { send(sender,"help.claim"); send(sender,"help.status"); send(sender,"help.auto"); }
            if (admin) { send(sender,"help.retry"); send(sender,"help.reload"); send(sender,"help.grant"); send(sender,"help.plan"); }
            return true;
        }
        if (Set.of("reload","retry","grant","sponsor").contains(action)) {
            if (!admin) { send(sender,"err.permission"); return true; }
            if (action.equals("reload") && args.length == 1) {
                try { reloadSettings(); send(sender,"msg.reloaded"); } catch(Exception error) { send(sender,"err.failed"); getLogger().error(text("log.config"),error); }
            } else if (action.equals("retry") && args.length == 1) {
                service.retryPending().whenComplete((count,error)-> { if(error!=null)error(sender,error);else main(()->send(sender,"msg.retry",count)); });
            } else if (action.equals("sponsor") && args.length >= 3) {
                sponsor(sender, args);
            } else if (action.equals("grant") && args.length == 6) {
                try {
                    UUID uuid = UUID.fromString(args[1]); long amount = new java.math.BigDecimal(args[5]).longValueExact();
                    grant(uuid,args[2],args[3],args[4],amount).whenComplete((result,error)-> {
                        if(error!=null)error(sender,error);else main(()->send(sender,"msg.grant",result.status(),result.id(),result.amount()));
                    });
                } catch(Exception error) { send(sender,"err.syntax"); }
            } else send(sender,"err.syntax");
            return true;
        }
        if (!use) { send(sender,"err.permission"); return true; }
        if (!(sender instanceof Player player)) { send(sender,"err.player"); return true; }
        switch(action) {
            case "claim" -> { if(args.length==1)claim(player);else send(player,"err.syntax"); }
            case "status" -> {
                if(args.length!=1) { send(player,"err.syntax"); break; }
                service.profile(player.getUniqueId()).whenComplete((profile,error)->main(()-> {
                    if(error!=null)send(player,"err.failed");else { send(player,"msg.status",profile.get("lastDay").getAsString(),profile.get("streak").getAsInt(),profile.get("total").getAsLong()); show(player,profile); }
                }));
            }
            case "auto" -> {
                if(args.length!=2||!Set.of("on","off").contains(args[1])) { send(player,"err.syntax"); break; }
                service.automatic(player.getUniqueId(),args[1].equals("on")).whenComplete((ignored,error)->main(()-> {
                    if(error!=null)send(player,"err.failed");else send(player,"msg.automatic",text("status."+args[1]));
                }));
            }
            default -> send(player,"err.syntax");
        }
        return true;
    }

    /** Main-thread configuration reload; invalid input leaves live settings intact. Backend changes require migration. */
    public void reloadSettings() {
        reloadConfig();
        RewardSettings next = RewardSettings.parse(getConfig().getAll());
        seconds("maintenance.retry-seconds");seconds("plan.refresh-seconds");
        dev.toka.rewards.plan.PlanControl.parse(getConfig().getAll(),Set.of("streak","total","last-day","automatic","pending"));
        if (Backend.valueOf(getConfig().getString("storage.backend").toUpperCase(Locale.ROOT)) != backend) throw new IllegalArgumentException(text("err.backend"));
        VoteClient replacement = new VoteClient(VoteClient.Settings.parse(getConfig().getAll()),Clock.systemUTC());
        votes.close(); votes = replacement; service.settings(next);
        messages = new Messages(getClass().getClassLoader(),getDataFolder().toPath(),getConfig().getString("language"),getLogger()::warning);
        closePlan(); attachPlan();
        scheduleTasks();
    }
    @EventHandler public void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("Plan")) attachPlan();
        if (event.getPlugin().getName().equals("tokaEconomy")) service.retryPending();
    }
    @EventHandler public void onPluginDisable(PluginDisableEvent event) { if(event.getPlugin().getName().equals("Plan"))closePlan(); }
    private void attachPlan() {
        if(plan!=null||planControl==null||!planControl.shouldRegister())return;
        try {
            Class<?> settings=Class.forName("dev.toka.rewards.plan.PlanSettings");
            Object parsed=settings.getMethod("parse",Map.class).invoke(null,getConfig().getAll());
            Class<?> extension=Class.forName("dev.toka.rewards.plan.PlanExtension");
            Object candidate=extension.getConstructor(settings,Function.class).newInstance(parsed,(Function<String,String>)key->text(key));
            if(Boolean.TRUE.equals(extension.getMethod("register").invoke(candidate))) { plan=candidate; planControl.registered(true); publishPlan(); getLogger().info(text("plan.ready")); }
            else ((AutoCloseable)candidate).close();
        } catch(Exception|LinkageError error) { getLogger().warning(text("plan.unsupported")); }
    }
    private void closePlan() {
        Object previous=plan;plan=null;
        if(planControl!=null)planControl.registered(false);
        if(previous instanceof AutoCloseable closeable)try{closeable.close();}catch(Exception|LinkageError ignored){}
    }
    private void publishPlan() {
        Object target=plan;if(target==null)return;
        for(Player player:getServer().getOnlinePlayers().values())publishPlanPlayer(player.getUniqueId());
        service.pendingCount().thenAccept(count->{try{target.getClass().getMethod("publishPending",int.class).invoke(target,count);}catch(Exception ignored){}});
    }
    private void publishPlanPlayer(UUID uuid) { service.profile(uuid).thenAccept(profile->publish(uuid,profile)); }
    private void publish(UUID uuid,JsonObject profile) {
        Object target=plan;if(target==null)return;
        try{target.getClass().getMethod("publish",UUID.class,JsonObject.class).invoke(target,uuid,profile);}catch(Exception ignored){}
    }
    public String planStatusJson(){return planControl.statusJson();}
    public boolean configurePlan(String mode,String[] fields){return planControl.configure(mode,fields);}
}
