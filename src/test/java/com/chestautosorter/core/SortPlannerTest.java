package com.chestautosorter.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SortPlannerTest {

    private static StackEntry e(String item, long count, int max, Category cat) {
        return new StackEntry(TestKey.of(item), count, max, cat);
    }

    private static StackEntry e(String item, String data, long count, int max, Category cat) {
        return new StackEntry(TestKey.of(item, data), count, max, cat);
    }

    /** Sum of counts per identity in a layout. */
    private static Map<String, Long> totals(SortPlanner.Result r) {
        Map<String, Long> m = new HashMap<>();
        for (SortPlanner.SlotPlan p : r.layout) {
            if (!p.isEmpty()) {
                m.merge(p.entry.key().identity(), p.entry.count(), Long::sum);
            }
        }
        return m;
    }

    private static Map<String, Long> inputTotals(List<StackEntry> in) {
        Map<String, Long> m = new HashMap<>();
        for (StackEntry s : in) {
            m.merge(s.key().identity(), s.count(), Long::sum);
        }
        return m;
    }

    private static void assertSlotLimit(SortPlanner.Result r) {
        for (SortPlanner.SlotPlan p : r.layout) {
            if (!p.isEmpty()) {
                assertTrue(p.entry.count() <= p.entry.maxStack(),
                        "slot exceeded max: " + p.entry.count() + " > " + p.entry.maxStack());
                assertTrue(p.entry.count() > 0, "non-positive stack in output");
            }
        }
    }

    @Test
    void conservationSingleCategory() {
        List<StackEntry> in = List.of(e("minecraft:oak_log", 200, 64, Category.WOOD));
        SortPlanner.Result r = SortPlanner.plan(List.of(27), in, true);
        assertFalse(r.isCancelled());
        assertEquals(inputTotals(in), totals(r));
        assertSlotLimit(r);
        assertEquals(27, r.layout.size());
    }

    @Test
    void conservationMultipleCategories() {
        List<StackEntry> in = List.of(
                e("minecraft:oak_log", 100, 64, Category.WOOD),
                e("minecraft:iron_ingot", 500, 64, Category.ORE),
                e("minecraft:wheat", 30, 64, Category.CROP_FOOD),
                e("minecraft:bone", 7, 64, Category.MOB_DROP));
        SortPlanner.Result r = SortPlanner.plan(List.of(27, 27, 54), in, true);
        assertFalse(r.isCancelled());
        assertEquals(inputTotals(in), totals(r));
        assertSlotLimit(r);
    }

    @Test
    void doesNotMergeDifferentComponents() {
        List<StackEntry> in = List.of(
                e("minecraft:diamond_sword", "{ench:a}", 1, 1, Category.TOOL_EQUIPMENT),
                e("minecraft:diamond_sword", "{ench:b}", 1, 1, Category.TOOL_EQUIPMENT));
        SortPlanner.Result r = SortPlanner.plan(List.of(27), in, true);
        assertEquals(inputTotals(in), totals(r));
        assertEquals(2, totals(r).size());
    }

    @Test
    void largeCountsDoNotOverflow() {
        long big = 5_000_000_000L; // > int range
        List<StackEntry> in = List.of(e("minecraft:cobblestone", big, 512, Category.STONE_BUILDING));
        // need ceil(5e9/512) = 9765625 slots -> too many, must cancel without overflow/crash
        SortPlanner.Result r = SortPlanner.plan(List.of(27), in, true);
        assertTrue(r.isCancelled());
    }

    @Test
    void largeCountsArithmeticIsOverflowSafe() {
        // Ceil division must not overflow near Long.MAX_VALUE.
        assertEquals(Long.MAX_VALUE / 512 + 1, SortPlanner.stackSlots(Long.MAX_VALUE, 512));
        assertEquals(1, SortPlanner.stackSlots(1, 1));
        assertEquals(0, SortPlanner.stackSlots(0, 64));
        // 5e9 / 512 = 9_765_625 exactly.
        assertEquals(9_765_625L, SortPlanner.stackSlots(5_000_000_000L, 512));
    }

    @Test
    void largeCountsFitInBiggerContainers() {
        long big = 5_000_000_000L; // > int range
        List<StackEntry> in = List.of(e("minecraft:cobblestone", big, 512, Category.STONE_BUILDING));
        // Keep the materialised layout modest but still multi-thousand slots.
        List<Integer> sizes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            sizes.add(54);
        }
        // 216 slots cannot hold 9_765_625 stacks -> must cancel cleanly, no overflow.
        SortPlanner.Result r = SortPlanner.plan(sizes, in, true);
        assertTrue(r.isCancelled());
    }

    @Test
    void capacityShortageCancelsAndChangesNothing() {
        List<StackEntry> in = List.of(
                e("minecraft:a", 64 * 27, 64, Category.MISC),
                e("minecraft:b", 64 * 27, 64, Category.MISC));
        SortPlanner.Result r = SortPlanner.plan(List.of(27), in, true);
        assertTrue(r.isCancelled());
        assertTrue(r.layout.isEmpty(), "cancelled plan must not produce a layout");
    }

    @Test
    void categoriesExceedBoxesFallsBackToMixed() {
        // Force dedicated packing to fail: make one category need more than any single box,
        // but the total still fits overall. 2 boxes x 27 = 54 slots.
        // Category A needs 27 slots (fits exactly one box); category B needs 27 slots.
        // With only 2 boxes that fits dedicated; instead make A need 40 slots (> 1 box) and B 14.
        List<StackEntry> in = List.of(
                e("minecraft:a", 40, 1, Category.WOOD),   // 40 slots, cannot fit in one 27-box
                e("minecraft:b", 14, 1, Category.ORE));   // 14 slots
        SortPlanner.Result r = SortPlanner.plan(List.of(27, 27), in, true);
        assertFalse(r.isCancelled());
        assertEquals(SortPlanner.Status.OK_MIXED, r.status);
        assertEquals(inputTotals(in), totals(r));
        assertSlotLimit(r);
    }

    @Test
    void moreCategoriesThanChestsSpreadInsteadOfPilingIntoOne() {
        // The reported defect: 5 chests with 6 categories ran the tight packer, which filled chest 0
        // with every category and left 4 chests empty.
        List<StackEntry> in = List.of(
                e("minecraft:oak_log", 100, 64, Category.WOOD),               // 2 slots
                e("minecraft:cobblestone", 100, 64, Category.STONE_BUILDING), // 2 slots
                e("minecraft:diamond", 1, 64, Category.ORE),                  // 1 slot
                e("minecraft:redstone", 1, 64, Category.REDSTONE),            // 1 slot
                e("minecraft:iron_sword", 64, 64, Category.TOOL_EQUIPMENT),   // 1 slot
                e("minecraft:bone", 64, 64, Category.MISC));                  // 1 slot
        SortPlanner.Result r = SortPlanner.plan(List.of(27, 27, 27, 27, 27), in, true);
        assertEquals(SortPlanner.Status.OK_SPREAD, r.status);
        assertEquals(inputTotals(in), totals(r));
        assertSlotLimit(r);

        Map<Integer, Set<Category>> perChest = new HashMap<>();
        for (SortPlanner.SlotPlan p : r.layout) {
            if (!p.isEmpty()) {
                perChest.computeIfAbsent(p.container, k -> new HashSet<>()).add(p.entry.category());
            }
        }
        assertEquals(5, perChest.size(), "every chest must be used, got " + perChest);
        assertTrue(perChest.values().stream().allMatch(s -> s.size() <= 2),
                "at most one pair may share, got " + perChest);
        assertTrue(perChest.values().stream().anyMatch(s -> s.equals(Set.of(Category.ORE, Category.REDSTONE))),
                "the lightest adjacent pair is the one that shares a chest, got " + perChest);
    }

    @Test
    void dedicatedWhenEnoughBoxes() {
        List<StackEntry> in = List.of(
                e("minecraft:oak_log", 64, 64, Category.WOOD),
                e("minecraft:iron_ingot", 64, 64, Category.ORE));
        List<Integer> sizes = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            sizes.add(27);
        }
        SortPlanner.Result r = SortPlanner.plan(sizes, in, true);
        assertEquals(SortPlanner.Status.OK_DEDICATED, r.status);
        assertEquals(inputTotals(in), totals(r));
    }

    @Test
    void mixedDisallowedCancelsWhenDedicatedFails() {
        List<StackEntry> in = new ArrayList<>();
        for (Category c : Category.values()) {
            in.add(e("t:" + c.name(), 1, 64, c));
        }
        SortPlanner.Result r = SortPlanner.plan(List.of(27, 27), in, false);
        assertTrue(r.isCancelled());
    }

    @Test
    void deterministicRepeatedRuns() {
        List<StackEntry> in = List.of(
                e("minecraft:b", 100, 64, Category.ORE),
                e("minecraft:a", 100, 64, Category.WOOD),
                e("minecraft:c", 50, 64, Category.MISC));
        SortPlanner.Result r1 = SortPlanner.plan(List.of(27, 27, 27), in, true);
        SortPlanner.Result r2 = SortPlanner.plan(List.of(27, 27, 27), in, true);
        assertEquals(r1.status, r2.status);
        assertEquals(r1.layout.size(), r2.layout.size());
        for (int i = 0; i < r1.layout.size(); i++) {
            SortPlanner.SlotPlan a = r1.layout.get(i);
            SortPlanner.SlotPlan b = r2.layout.get(i);
            assertEquals(a.isEmpty(), b.isEmpty());
            if (!a.isEmpty()) {
                assertEquals(a.entry.key().identity(), b.entry.key().identity());
                assertEquals(a.entry.count(), b.entry.count());
            }
        }
    }

    @Test
    void propertyRandomizedConservationAndLimits() {
        Random rnd = new Random(20260930L);
        for (int iter = 0; iter < 200; iter++) {
            int nEntries = 1 + rnd.nextInt(12);
            List<StackEntry> in = new ArrayList<>();
            for (int i = 0; i < nEntries; i++) {
                String item = "t:item" + rnd.nextInt(6);
                String data = rnd.nextInt(3) == 0 ? "{d:" + rnd.nextInt(3) + "}" : "";
                int max = switch (rnd.nextInt(3)) {
                    case 0 -> 1;
                    case 1 -> 64;
                    default -> 512;
                };
                long count = 1 + rnd.nextInt(max * 3);
                Category cat = Category.values()[rnd.nextInt(Category.values().length)];
                in.add(new StackEntry(TestKey.of(item, data), count, max, cat));
            }
            int boxes = 1 + rnd.nextInt(6);
            List<Integer> sizes = new ArrayList<>();
            for (int b = 0; b < boxes; b++) {
                sizes.add(rnd.nextBoolean() ? 27 : 54);
            }
            SortPlanner.Result r = SortPlanner.plan(sizes, in, true);
            if (!r.isCancelled()) {
                assertEquals(inputTotals(in), totals(r), "conservation violated at iter " + iter);
                assertSlotLimit(r);
                assertEquals(sizes.stream().mapToInt(Integer::intValue).sum(), r.layout.size());
            }
        }
    }

    @Test
    void emptyInventoryProducesEmptyLayoutWithNoCancel() {
        SortPlanner.Result r = SortPlanner.plan(List.of(27), List.of(), true);
        assertFalse(r.isCancelled());
        assertEquals(27, r.layout.size());
        for (SortPlanner.SlotPlan p : r.layout) {
            assertTrue(p.isEmpty());
        }
    }
}
