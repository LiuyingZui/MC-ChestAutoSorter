package com.chestautosorter.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StackAggregatorTest {

    private static StackEntry e(String item, String data, long count, int max) {
        return new StackEntry(TestKey.of(item, data), count, max, Category.MISC);
    }

    @Test
    void mergesSameIdentity() {
        List<StackEntry> in = List.of(
                e("a", "", 100, 512),
                e("a", "", 50, 512));
        List<StackEntry> out = StackAggregator.aggregate(in);
        assertEquals(1, out.size());
        assertEquals(150, out.get(0).count());
    }

    @Test
    void keepsDifferentComponentsApart() {
        List<StackEntry> in = List.of(
                e("a", "{d:1}", 10, 64),
                e("a", "{d:2}", 20, 64));
        List<StackEntry> out = StackAggregator.aggregate(in);
        assertEquals(2, out.size());
    }

    @Test
    void deterministicOrderByIdentity() {
        List<StackEntry> in = List.of(e("b", "", 1, 64), e("a", "", 1, 64), e("c", "", 1, 64));
        List<StackEntry> out = StackAggregator.aggregate(in);
        assertEquals("a", out.get(0).key().itemId());
        assertEquals("b", out.get(1).key().itemId());
        assertEquals("c", out.get(2).key().itemId());
    }
}
