package dev.toka.rewards;

import java.util.List;

/** Small pure helpers of the vote window, kept apart from the server so they can be tested. */
final class VoteText {
    private VoteText() {}

    /** The language key that says what today's vote looks like. */
    static String todayKey(boolean enabled, VoteClient.Snapshot snapshot) {
        if (!enabled) return "ui.vote.today.off";
        if (!snapshot.available()) return "ui.vote.today.unknown";
        if (!snapshot.voted()) return "ui.vote.today.none";
        return snapshot.unclaimed() ? "ui.vote.today.unclaimed" : "ui.vote.today.claimed";
    }

    /** One line of the list, chosen by the seed; empty when the list is empty. */
    static String pick(List<String> facts, long seed) {
        if (facts.isEmpty()) return "";
        return facts.get((int) Math.floorMod(seed, (long) facts.size()));
    }
}
