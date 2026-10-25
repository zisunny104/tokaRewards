package dev.toka.rewards;

import org.junit.Test;
import static org.junit.Assert.*;

public class WarnOnceTest {
    @Test public void firstTimeOnlyPerKey(){
        var w=new WarnOnce();
        assertTrue(w.first("a"));
        assertFalse(w.first("a"));
        assertTrue(w.first("b"));
    }
    @Test public void clearAllowsTheKeyAgain(){
        var w=new WarnOnce();
        w.first("a");w.clear();
        assertTrue(w.first("a"));
    }
}
