package dev.toka.rewards;

import dev.toka.i18n.Messages;
import org.junit.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.*;

public class VoteTextTest {
    @Test public void todayKeyFollowsTheVoteState() {
        assertEquals("ui.vote.today.off", VoteText.todayKey(false, VoteClient.Snapshot.unavailable()));
        assertEquals("ui.vote.today.unknown", VoteText.todayKey(true, VoteClient.Snapshot.unavailable()));
        assertEquals("ui.vote.today.none", VoteText.todayKey(true, new VoteClient.Snapshot(true, 3, false, false)));
        assertEquals("ui.vote.today.unclaimed", VoteText.todayKey(true, new VoteClient.Snapshot(true, 3, true, true)));
        assertEquals("ui.vote.today.claimed", VoteText.todayKey(true, new VoteClient.Snapshot(true, 3, true, false)));
    }

    @Test public void pickStaysInRangeForAnySeed() {
        List<String> facts = List.of("a", "b", "c");
        assertEquals("a", VoteText.pick(facts, 0));
        assertEquals("b", VoteText.pick(facts, 4));
        assertEquals("c", VoteText.pick(facts, -1));
        assertEquals("", VoteText.pick(List.of(), 7));
    }

    @Test public void languageFileHasFiftyDifferentShortFacts() {
        Messages messages = new Messages(getClass().getClassLoader(), Path.of("build", "no-overrides"), "zh-TW", line -> fail(line));
        List<String> facts = messages.lines("fun.facts");
        assertEquals(50, facts.size());
        assertEquals(50, new HashSet<>(facts).size());
        for (String fact : facts) assertTrue(fact, fact.length() >= 6 && fact.length() <= 40);
    }
}
