package com.chestautosorter.core;

import com.chestautosorter.network.Msg;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure layout planner. Given container sizes and the aggregated inventory, it produces a complete
 * target layout (slot -> StackEntry or empty), or reports why it cannot.
 *
 * Guarantees enforced here (unit-tested):
 *  - total item count per identity is conserved (input == output)
 *  - no slot is assigned more than the entry's maxStack
 *  - output slot count equals input slot count
 *  - deterministic: same input -> same output
 */
public final class SortPlanner {

    /** Ordering of categories for segment layout. */
    private static final List<Category> ORDER = List.of(Category.values());

    private SortPlanner() {}

    public enum Status {
        OK_DEDICATED,
        /** More categories than chests: each chest still gets its own contiguous group. */
        OK_SPREAD,
        OK_MIXED,
        CANCELLED_NO_ROOM
    }

    public static final class SlotPlan {
        public final int container;
        public final int slot;
        public final StackEntry entry;

        public SlotPlan(int container, int slot, StackEntry entry) {
            this.container = container;
            this.slot = slot;
            this.entry = entry;
        }

        public boolean isEmpty() {
            return entry == null;
        }
    }

    public static final class Result {
        public final Status status;
        public final List<SlotPlan> layout;
        public final List<String> messages;
        public final List<Category> mixedCategories;

        Result(Status status, List<SlotPlan> layout, List<String> messages, List<Category> mixedCategories) {
            this.status = status;
            this.layout = layout;
            this.messages = messages;
            this.mixedCategories = mixedCategories;
        }

        public boolean isCancelled() {
            return status == Status.CANCELLED_NO_ROOM;
        }
    }

    /**
     * @param containerSizes slot count of each logical container, in canonical order
     * @param aggregated     aggregated non-empty entries (see {@link StackAggregator})
     * @param allowMixed     whether compact mixed-category packing is allowed as a fallback
     */
    public static Result plan(List<Integer> containerSizes, List<StackEntry> aggregated, boolean allowMixed) {
        List<String> messages = new ArrayList<>();

        int totalSlots = 0;
        for (int s : containerSizes) {
            if (s < 0) {
                throw new IllegalArgumentException("negative container size");
            }
            totalSlots = Math.addExact(totalSlots, s);
        }

        // Total slots required for every identity at its per-slot limit.
        long requiredSlots = 0;
        for (StackEntry e : aggregated) {
            requiredSlots = Math.addExact(requiredSlots, stackSlots(e.count(), e.maxStack()));
        }

        if (requiredSlots > totalSlots) {
            messages.add(Msg.key("chestautosorter.plan.no_room", requiredSlots, totalSlots));
            return new Result(Status.CANCELLED_NO_ROOM, List.of(), messages, List.of());
        }

        // --- Attempt dedicated per-category packing first ---
        List<SlotPlan> dedicated = tryPlan(containerSizes, aggregated, false);
        if (dedicated != null) {
            messages.add(Msg.key("chestautosorter.plan.dedicated_ok"));
            return new Result(Status.OK_DEDICATED, dedicated, messages, List.of());
        }

        if (!allowMixed) {
            messages.add(Msg.key("chestautosorter.plan.mixed_forbidden"));
            return new Result(Status.CANCELLED_NO_ROOM, List.of(), messages, List.of());
        }

        // --- More categories than chests: merge the lightest neighbours so every chest still gets its
        // own contiguous group. Without this step the tight packing below would pile the whole
        // inventory into the first chest and leave the rest empty, which reads as "nothing was sorted".
        Map<Category, List<StackEntry>> byCat = grouped(aggregated);
        List<Category> present = nonEmpty(byCat);
        if (present.size() > containerSizes.size()) {
            List<SlotPlan> spread = placeUnits(flatten(containerSizes), byCat,
                    mergeToGroups(present, byCat, containerSizes.size()));
            if (spread != null) {
                return new Result(Status.OK_SPREAD, spread, messages, present);
            }
        }

        // --- Fallback: compact packing, categories kept as contiguous segments when possible ---
        List<SlotPlan> compact = tryPlan(containerSizes, aggregated, true);
        if (compact == null) {
            messages.add(Msg.key("chestautosorter.plan.mixed_failed"));
            return new Result(Status.CANCELLED_NO_ROOM, List.of(), messages, List.of());
        }
        List<Category> mixed = categoriesIn(compact);
        messages.add(Msg.key("chestautosorter.plan.fallback_mixed", mixed.size()));
        return new Result(Status.OK_MIXED, compact, messages, mixed);
    }

    /**
     * Greedy layout. When {@code compact} is false, each category must fit entirely within its own
     * contiguous run of containers. When true, categories may share containers but are emitted in
     * category order.
     *
     * @return layout, or null if it does not fit
     */
    private static List<SlotPlan> tryPlan(List<Integer> containerSizes, List<StackEntry> aggregated, boolean compact) {
        Map<Category, List<StackEntry>> byCat = grouped(aggregated);
        List<int[]> slotIndex = flatten(containerSizes);

        if (!compact) {
            List<List<Category>> oneUnitPerCategory = new ArrayList<>();
            for (Category cat : nonEmpty(byCat)) {
                oneUnitPerCategory.add(List.of(cat));
            }
            return placeUnits(slotIndex, byCat, oneUnitPerCategory);
        }

        // Compact: place categories in order, packing tightly, allowed to share containers.
        StackEntry[] layout = new StackEntry[slotIndex.size()];
        int cursor = 0;
        for (Category cat : nonEmpty(byCat)) {
            List<StackEntry> entries = byCat.get(cat);
            int placed = placeInto(entries, layout, cursor, layout.length);
            if (placed < 0) {
                return null;
            }
            cursor = placed;
        }
        return toLayout(layout, slotIndex);
    }

    /** Entries grouped by category, each group sorted by stack key so the layout is deterministic. */
    private static Map<Category, List<StackEntry>> grouped(List<StackEntry> aggregated) {
        Map<Category, List<StackEntry>> byCat = new LinkedHashMap<>();
        for (Category cat : ORDER) {
            byCat.put(cat, new ArrayList<>());
        }
        for (StackEntry e : aggregated) {
            byCat.get(e.category()).add(e);
        }
        for (List<StackEntry> list : byCat.values()) {
            list.sort(Comparator.comparing(a -> a.key(), StackKey::compare));
        }
        return byCat;
    }

    private static List<Category> nonEmpty(Map<Category, List<StackEntry>> byCat) {
        List<Category> out = new ArrayList<>();
        for (Category cat : ORDER) {
            if (!byCat.get(cat).isEmpty()) {
                out.add(cat);
            }
        }
        return out;
    }

    /** All container slots in canonical container/slot order, as {@code {container, slot}} pairs. */
    private static List<int[]> flatten(List<Integer> containerSizes) {
        List<int[]> slotIndex = new ArrayList<>();
        for (int c = 0; c < containerSizes.size(); c++) {
            int size = containerSizes.get(c);
            if (size < 0) {
                throw new IllegalArgumentException("negative container size");
            }
            for (int s = 0; s < size; s++) {
                slotIndex.add(new int[]{c, s});
            }
        }
        return slotIndex;
    }

    private static long needOf(List<StackEntry> entries) {
        long need = 0;
        for (StackEntry e : entries) {
            need += stackSlots(e.count(), e.maxStack());
        }
        return need;
    }

    /**
     * Places each unit (a contiguous run of categories that must not share a chest with another unit)
     * starting at a container boundary, in order.
     *
     * @return layout, or null when a unit does not fit before the next unit's chest
     */
    private static List<SlotPlan> placeUnits(List<int[]> slotIndex, Map<Category, List<StackEntry>> byCat,
                                             List<List<Category>> units) {
        StackEntry[] layout = new StackEntry[slotIndex.size()];
        int cursor = 0;
        for (List<Category> unit : units) {
            List<StackEntry> entries = new ArrayList<>();
            for (Category cat : unit) {
                entries.addAll(byCat.get(cat));
            }
            long need = needOf(entries);
            if (need == 0) {
                continue;
            }
            int startContainer = containerOf(slotIndex, cursor);
            int startSlot = boundaryStart(slotIndex, startContainer);
            int placed = placeInto(entries, layout, startSlot, startSlot + (int) need);
            if (placed < 0) {
                return null; // does not fit without sharing a chest with the next unit
            }
            cursor = nextContainerStart(slotIndex, startSlot, placed);
        }
        return toLayout(layout, slotIndex);
    }

    /**
     * Merges neighbouring categories, always the lightest adjacent pair first, until there is at most
     * one group per chest. Ties resolve to the leftmost pair, so the result is deterministic.
     */
    private static List<List<Category>> mergeToGroups(List<Category> present, Map<Category, List<StackEntry>> byCat,
                                                      int chests) {
        List<List<Category>> groups = new ArrayList<>();
        List<Long> needs = new ArrayList<>();
        for (Category cat : present) {
            groups.add(new ArrayList<>(List.of(cat)));
            needs.add(needOf(byCat.get(cat)));
        }
        while (groups.size() > chests) {
            int best = 0;
            long bestSum = Long.MAX_VALUE;
            for (int i = 0; i + 1 < groups.size(); i++) {
                long sum = needs.get(i) + needs.get(i + 1);
                if (sum < bestSum) {
                    bestSum = sum;
                    best = i;
                }
            }
            groups.get(best).addAll(groups.remove(best + 1));
            needs.remove(best + 1);
            needs.set(best, bestSum);
        }
        return groups;
    }

    /**
     * Places entries starting at flat index {@code start} and not writing at or beyond {@code bound}.
     * Returns the flat index after the last used slot, or -1 if it would exceed the bound.
     */
    private static int placeInto(List<StackEntry> entries, StackEntry[] layout, int start, int bound) {
        int flat = start;
        for (StackEntry e : entries) {
            long remaining = e.count();
            while (remaining > 0) {
                if (flat >= bound || flat >= layout.length) {
                    return -1;
                }
                if (layout[flat] != null) {
                    return -1;
                }
                long put = Math.min(remaining, e.maxStack());
                layout[flat] = new StackEntry(e.key(), put, e.maxStack(), e.category());
                remaining -= put;
                flat++;
            }
        }
        return flat;
    }

    private static int containerOf(List<int[]> slotIndex, int flat) {
        if (flat >= slotIndex.size()) {
            return slotIndex.get(slotIndex.size() - 1)[0];
        }
        return slotIndex.get(flat)[0];
    }

    private static int boundaryStart(List<int[]> slotIndex, int container) {
        for (int i = 0; i < slotIndex.size(); i++) {
            if (slotIndex.get(i)[0] == container && slotIndex.get(i)[1] == 0) {
                return i;
            }
        }
        return slotIndex.size();
    }

    private static int nextContainerStart(List<int[]> slotIndex, int startFlat, int endFlat) {
        // first flat index after endFlat that begins a new container
        for (int i = endFlat; i < slotIndex.size(); i++) {
            if (slotIndex.get(i)[1] == 0) {
                return i;
            }
        }
        return slotIndex.size();
    }

    private static List<SlotPlan> toLayout(StackEntry[] layout, List<int[]> slotIndex) {
        List<SlotPlan> out = new ArrayList<>(layout.length);
        for (int i = 0; i < layout.length; i++) {
            int[] cs = slotIndex.get(i);
            out.add(new SlotPlan(cs[0], cs[1], layout[i]));
        }
        return out;
    }

    private static List<Category> categoriesIn(List<SlotPlan> layout) {
        Map<Category, Boolean> seen = new LinkedHashMap<>();
        for (SlotPlan p : layout) {
            if (!p.isEmpty()) {
                seen.put(p.entry.category(), Boolean.TRUE);
            }
        }
        return new ArrayList<>(seen.keySet());
    }

    /** Ceil(count / maxStack) as a long, overflow-safe. */
    static long stackSlots(long count, int maxStack) {
        if (count <= 0) {
            return 0;
        }
        return (count - 1) / maxStack + 1;
    }
}
