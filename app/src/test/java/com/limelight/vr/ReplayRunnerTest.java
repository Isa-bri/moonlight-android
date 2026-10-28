package com.limelight.vr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Tests the replay runner itself: a violated rule has to FAIL, and nothing checked cannot pass.
public class ReplayRunnerTest {
    private static final long T0 = 1_000_000;

    // AR trace (ar_cm=10, keys): `still` and then walking forward
    private static List<String> trace(boolean walksWhileStill) {
        List<String> l = new ArrayList<>();
        l.add("cfg," + T0 + ",ar=true;ar_cm=10;ar_hold=false;analog=false");
        l.add("m," + T0 + ",still");
        double z = 0;
        for (int i = 0; i < 180; i++) { // 6 s at 30 fps
            long tc = T0 + i * 33L;
            if (walksWhileStill) z -= 0.7 / 30;
            l.add("ar," + tc + "," + tc * 1_000_000 + ",0.0," + z + ",0.0,-1.0,1");
        }
        return l;
    }

    private static final List<String> STILL_RULE = Arrays.asList("margin 1.0", "rule still max_keys_fraction <= 0.02");

    @Test
    public void stillPassesTheStillRule() {
        ReplayRunner.Result r = ReplayRunner.run("still", trace(false), STILL_RULE);
        assertTrue(String.join("\n", r.lines), r.passed());
        assertEquals(1, r.checked);
    }

    @Test
    public void walkingWhileStillFailsTheRule() {
        ReplayRunner.Result r = ReplayRunner.run("walking", trace(true), STILL_RULE);
        assertFalse("the violated rule has to fail: " + String.join("\n", r.lines), r.passed());
        assertEquals(1, r.failed);
    }

    @Test
    public void ruleWithoutMatchingSegmentFails() {
        ReplayRunner.Result r = ReplayRunner.run("x", trace(false),
                Arrays.asList("rule does_not_exist max_keys_fraction <= 0.02"));
        assertFalse(r.passed());
        assertTrue(r.lines.get(0).contains("not checked"));
    }

    @Test
    public void noRulesDoesNotPass() {
        ReplayRunner.Result r = ReplayRunner.run("x", trace(false), Arrays.asList("# empty"));
        assertFalse(r.passed());
    }

    @Test
    public void keyMetricMeasuresTheTimeFraction() {
        ReplayRunner.Result r = ReplayRunner.run("walking", trace(true),
                Arrays.asList("margin 1.0", "rule still key_W_fraction >= 0.9", "rule still key_S_fraction <= 0.01"));
        assertTrue(String.join("\n", r.lines), r.passed());
    }
}
