package com.coke.nestspace.plugin;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RuntimeVersionMatcherTest {

    @Test
    public void exactMatcherOnlyAcceptsOneVersion() {
        RuntimeVersionMatcher matcher = RuntimeVersionMatcher.exact(74_332);
        assertTrue(matcher.matches(74_332));
        assertFalse(matcher.matches(74_333));
    }

    @Test
    public void globAndPrefixMatchersSurviveMinorUpgrades() {
        assertTrue(RuntimeVersionMatcher.glob("74*").matches(74_332));
        assertTrue(RuntimeVersionMatcher.glob("74*").matches(74_999));
        assertFalse(RuntimeVersionMatcher.glob("74*").matches(75_000));
        assertTrue(RuntimeVersionMatcher.prefix("74").matches(74_333));
    }

    @Test
    public void rangeAndAnyMatchersWork() {
        RuntimeVersionMatcher range =
            RuntimeVersionMatcher.range(74_000, 74_999);
        assertTrue(range.matches(74_500));
        assertFalse(range.matches(75_000));
        assertTrue(RuntimeVersionMatcher.anyVersion().matches(1));
    }

    @Test
    public void rejectsInvalidFuzzyPatterns() {
        assertThrows(
            IllegalArgumentException.class,
            () -> RuntimeVersionMatcher.glob("74.x")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> RuntimeVersionMatcher.range(20, 10)
        );
    }
}
