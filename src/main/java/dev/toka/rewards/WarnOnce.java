package dev.toka.rewards;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Lets a repeating task log one problem once instead of once per run; cleared on reload so a still-broken thing is said again. */
final class WarnOnce {
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    /** True the first time this key is asked, false after. */
    boolean first(String key) { return seen.add(key); }

    void clear() { seen.clear(); }
}
