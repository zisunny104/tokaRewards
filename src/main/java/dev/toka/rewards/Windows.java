package dev.toka.rewards;

import cn.nukkit.Player;
import cn.nukkit.event.player.PlayerFormRespondedEvent;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.element.ElementButtonImageData;
import cn.nukkit.form.element.ElementDivider;
import cn.nukkit.form.element.ElementDropdown;
import cn.nukkit.form.element.ElementHeader;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseModal;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowModal;
import cn.nukkit.form.window.FormWindowSimple;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** The windows of /reward: the check-in window, the vote window and the admin window. They only collect input; the work stays in Main and the commands. */
final class Windows {
    private static final String ICON_CLAIM = "textures/items/gold_ingot";
    private static final String ICON_VOTE = "textures/items/book_written";
    private static final String ICON_SPONSOR = "textures/items/emerald";
    private static final String ICON_GRANT = "textures/items/diamond";
    private static final String ICON_RETRY = "textures/items/compass_item";
    private static final String ICON_RELOAD = "textures/items/redstone_dust";
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");

    /** Position of the auto check-in switch in the vote form (headers, labels and dividers count). */
    private static final int TOGGLE_AT = 9;

    private record Pending(int id, Consumer<PlayerFormRespondedEvent> handler) {
    }

    private final Main plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();

    Windows(Main plugin) {
        this.plugin = plugin;
    }

    private String t(String key, Object... args) {
        return plugin.messages().text(key, args);
    }

    // ---- form plumbing -------------------------------------------------------------------------------------------

    private void show(Player player, FormWindow window, Consumer<PlayerFormRespondedEvent> handler) {
        pending.put(player.getUniqueId(), new Pending(player.showFormWindow(window), handler));
    }

    /** Called for every answered form; true when it was one of ours. */
    boolean handle(PlayerFormRespondedEvent event) {
        Pending form = pending.get(event.getPlayer().getUniqueId());
        if (form == null || form.id() != event.getFormID()) {
            return false;
        }
        pending.remove(event.getPlayer().getUniqueId());
        form.handler().accept(event);
        return true;
    }

    void forget(UUID id) {
        pending.remove(id);
    }

    void clear() {
        pending.clear();
    }

    private void menu(Player player, String title, String content, List<String> labels, List<String> icons, Consumer<Integer> chosen) {
        FormWindowSimple form = new FormWindowSimple(title, content);
        for (int i = 0; i < labels.size(); i++) {
            String icon = icons.get(i);
            form.addButton(icon == null ? new ElementButton(labels.get(i))
                    : new ElementButton(labels.get(i), new ElementButtonImageData(ElementButtonImageData.IMAGE_DATA_TYPE_PATH, icon)));
        }
        show(player, form, event -> {
            if (!event.wasClosed() && event.getResponse() instanceof FormResponseSimple response) {
                chosen.accept(response.getClickedButtonId());
            }
        });
    }

    private void custom(Player player, FormWindowCustom form, Consumer<FormResponseCustom> submit, Consumer<Player> closed) {
        show(player, form, event -> {
            if (event.wasClosed() || !(event.getResponse() instanceof FormResponseCustom response)) {
                closed.accept(player);
            } else {
                submit.accept(response);
            }
        });
    }

    private void confirm(Player player, String title, String content, String accept, Consumer<Player> yes, Consumer<Player> no) {
        show(player, new FormWindowModal(title, content, accept, t("ui.cancel")), event -> {
            if (!event.wasClosed() && event.getResponse() instanceof FormResponseModal response && response.getClickedButtonId() == 0) {
                yes.accept(player);
            } else {
                no.accept(player);
            }
        });
    }

    private static String input(FormResponseCustom response, int element) {
        String value = response.getInputResponse(element);
        return value == null ? "" : value.strip();
    }

    // ---- player ----------------------------------------------------------------------------------------------------

    /**
     * The check-in window is a question with two answers. Check in is always the first. The second is a way to vote while today's vote is
     * still missing, and a plain cancel once it is done (or when the vote count cannot be asked). Closing the window does nothing.
     */
    void open(Player player, JsonObject profile) {
        plugin.lookupVote(player, snapshot -> {
            boolean toVote = plugin.voteEnabled() && snapshot.available() && !snapshot.voted();
            show(player, new FormWindowModal(t("ui.title"), t("ui.body", profile.get("lastDay").getAsString(), profile.get("streak").getAsInt(), profile.get("total").getAsLong()),
                    t("ui.claim"), t(toVote ? "ui.voteButton" : "ui.cancel")), event -> {
                if (event.wasClosed() || !(event.getResponse() instanceof FormResponseModal response)) {
                    return;
                }
                if (response.getClickedButtonId() == 0) {
                    if (player.hasPermission("tokaRewards.use")) {
                        plugin.claimFor(player);
                    }
                } else if (toVote) {
                    vote(player, profile, snapshot);
                }
            });
        });
    }

    /** How to vote, what today's vote is worth, the check-in numbers and the auto check-in switch, then a fun fact. */
    private void vote(Player player, JsonObject profile, VoteClient.Snapshot snapshot) {
        var settings = plugin.service().settings();
        String url = plugin.voteUrl();
        boolean automatic = profile.get("automatic").getAsBoolean();
        FormWindowCustom form = new FormWindowCustom(t("ui.vote.title"));
        form.addElement(new ElementHeader(t("ui.vote.how")));
        form.addElement(new ElementLabel(url.isBlank() ? t("ui.vote.noUrl") : t("ui.vote.url", url)));
        form.addElement(new ElementDivider());
        form.addElement(new ElementHeader(t("ui.vote.todayHeader")));
        form.addElement(new ElementLabel(t(VoteText.todayKey(plugin.voteEnabled(), snapshot))
                + (snapshot.available() ? "\n" + t("ui.vote.month", String.format("%,d", snapshot.votes())) : "")));
        form.addElement(new ElementLabel(settings.voteMultiplier().signum() > 0
                ? t("ui.vote.bonus", String.format("%,d", settings.voteThreshold()), settings.voteMultiplier().stripTrailingZeros().toPlainString())
                : t("ui.vote.noBonus")));
        form.addElement(new ElementDivider());
        form.addElement(new ElementHeader(t("ui.vote.checkinHeader")));
        form.addElement(new ElementLabel(t("ui.vote.checkin", profile.get("streak").getAsInt(), String.format("%,d", profile.get("total").getAsLong()))));
        form.addElement(new ElementToggle(t("ui.vote.auto"), automatic));
        form.addElement(new ElementDivider());
        form.addElement(new ElementLabel(t("ui.vote.fact", VoteText.pick(plugin.messages().lines("fun.facts"), ThreadLocalRandom.current().nextLong()))));
        custom(player, form, response -> {
            boolean next = Boolean.TRUE.equals(response.getToggleResponse(TOGGLE_AT));
            if (next != automatic) {
                plugin.setAutomatic(player, next);
            }
        }, p -> {
        });
    }

    // ---- admin -----------------------------------------------------------------------------------------------------

    void admin(Player player) {
        if (!player.hasPermission("tokaRewards.admin")) {
            player.sendMessage(t("err.permission"));
            return;
        }
        menu(player, t("ui.admin.title"), "",
                List.of(t("ui.admin.btnSponsor"), t("ui.admin.btnGrant"), t("ui.admin.btnRetry"), t("ui.admin.btnReload"), t("ui.close")),
                Arrays.asList(ICON_SPONSOR, ICON_GRANT, ICON_RETRY, ICON_RELOAD, null), index -> {
                    switch (index) {
                        case 0 -> pick(player, "", null, this::sponsorForm);
                        case 1 -> pick(player, "", null, this::grantForm);
                        case 2 -> confirm(player, t("ui.admin.btnRetry"), t("ui.admin.retryAsk"), t("ui.admin.accept"),
                                p -> plugin.getServer().dispatchCommand(p, "reward retry"), this::cancelled);
                        case 3 -> confirm(player, t("ui.admin.btnReload"), t("ui.admin.reloadAsk"), t("ui.admin.accept"),
                                p -> plugin.getServer().dispatchCommand(p, "reward reload"), this::cancelled);
                        default -> {
                        }
                    }
                });
    }

    private void cancelled(Player player) {
        player.sendMessage(t("msg.cancelled"));
    }

    /** Player choice: search narrows the online dropdown, an offline name wins when typed. */
    private void pick(Player player, String search, String error, BiConsumer<Player, String> next) {
        String needle = search.strip().toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        options.add(t("ui.admin.pick.none"));
        for (Player other : plugin.getServer().getOnlinePlayers().values()) {
            if (other.isOnline() && other.getName().toLowerCase(Locale.ROOT).contains(needle)) {
                options.add(other.getName());
            }
        }
        FormWindowCustom form = new FormWindowCustom(t("ui.admin.pick.title"));
        form.addElement(new ElementLabel(error == null ? t("ui.admin.pick.hint") : error + "\n" + t("ui.admin.pick.hint")));
        form.addElement(new ElementInput(t("ui.admin.pick.search"), t("ui.admin.pick.searchHint"), search));
        form.addElement(new ElementDropdown(t("ui.admin.pick.online"), options, options.size() == 2 ? 1 : 0));
        form.addElement(new ElementInput(t("ui.admin.pick.offline"), t("ui.admin.pick.offlineHint"), ""));
        custom(player, form, response -> {
            String typedSearch = input(response, 1);
            String offline = input(response, 3);
            int index = response.getDropdownResponse(2) == null ? 0 : response.getDropdownResponse(2).getElementID();
            if (!offline.isEmpty()) {
                next.accept(player, offline);
            } else if (index > 0 && index < options.size()) {
                next.accept(player, options.get(index));
            } else if (!typedSearch.isEmpty() && !typedSearch.equalsIgnoreCase(search.strip())) {
                pick(player, typedSearch, null, next);
            } else {
                pick(player, typedSearch, t("ui.admin.pick.missing"), next);
            }
        }, this::admin);
    }

    private void sponsorForm(Player player, String name) {
        sponsorForm(player, name, null, "", "");
    }

    private void sponsorForm(Player player, String name, String error, String amount, String note) {
        FormWindowCustom form = new FormWindowCustom(t("ui.admin.sponsor.title", name));
        form.addElement(new ElementLabel(error == null ? t("ui.admin.sponsor.label", name) : error));
        form.addElement(new ElementInput(t("ui.admin.amount"), t("ui.admin.amountHint"), amount));
        form.addElement(new ElementInput(t("ui.admin.note"), t("ui.admin.noteHint"), note));
        custom(player, form, response -> {
            String typed = input(response, 1);
            String said = input(response, 2);
            long value = digits(typed);
            if (value < 1 || value > 100_000_000L) {
                sponsorForm(player, name, t("ui.admin.amountBad"), typed, said);
                return;
            }
            confirm(player, t("ui.admin.confirm"), t("ui.admin.sponsor.content", name, String.format("%,d", value), said.isEmpty() ? "-" : said),
                    t("ui.admin.accept"), p -> plugin.getServer().dispatchCommand(p, "reward sponsor " + name + " " + value + (said.isEmpty() ? "" : " " + said)),
                    this::cancelled);
        }, this::admin);
    }

    private void grantForm(Player player, String name) {
        UUID id = plugin.uuidOf(name);
        if (id == null) {
            pick(player, "", t("ui.admin.pick.unknown", name), this::grantForm);
            return;
        }
        grantForm(player, name, id, null, "", "", "", 0);
    }

    private void grantForm(Player player, String name, UUID id, String error, String space, String operation, String amount, int currency) {
        List<String> codes = plugin.currencies();
        FormWindowCustom form = new FormWindowCustom(t("ui.admin.grant.title", name));
        form.addElement(new ElementLabel(error == null ? t("ui.admin.grant.label", name) : error));
        form.addElement(new ElementInput(t("ui.admin.space"), t("ui.admin.spaceHint"), space));
        form.addElement(new ElementInput(t("ui.admin.operation"), t("ui.admin.operationHint"), operation));
        if (codes.isEmpty()) {
            form.addElement(new ElementInput(t("ui.admin.currency"), t("ui.admin.currencyHint"), ""));
        } else {
            form.addElement(new ElementDropdown(t("ui.admin.currency"), codes, Math.min(currency, codes.size() - 1)));
        }
        form.addElement(new ElementInput(t("ui.admin.amount"), t("ui.admin.amountHint"), amount));
        custom(player, form, response -> {
            String s = input(response, 1);
            String o = input(response, 2);
            int c = codes.isEmpty() || response.getDropdownResponse(3) == null ? 0 : response.getDropdownResponse(3).getElementID();
            String code = codes.isEmpty() ? input(response, 3) : codes.get(Math.max(0, Math.min(codes.size() - 1, c)));
            String typed = input(response, 4);
            long value = digits(typed);
            if (!WORD.matcher(s).matches() || !WORD.matcher(o).matches() || !WORD.matcher(code).matches() || value < 1) {
                grantForm(player, name, id, t("ui.admin.grant.bad"), s, o, typed, c);
                return;
            }
            confirm(player, t("ui.admin.confirm"), t("ui.admin.grant.content", name, s, o, code, String.format("%,d", value)),
                    t("ui.admin.accept"), p -> plugin.getServer().dispatchCommand(p, "reward grant " + id + " " + s + " " + o + " " + code + " " + value),
                    this::cancelled);
        }, this::admin);
    }

    private static long digits(String text) {
        String clean = text.replace(",", "");
        return clean.matches("[0-9]{1,18}") ? Long.parseLong(clean) : -1;
    }
}
