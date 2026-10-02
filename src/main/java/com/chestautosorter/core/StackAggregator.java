package com.chestautosorter.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Merges stack entries by full identity using overflow-checked long counts. */
public final class StackAggregator {

    private StackAggregator() {}

    public static final class Aggregated {
        public final StackEntry entry;
        public final List<Integer> sourceSlots;

        Aggregated(StackEntry entry, List<Integer> sourceSlots) {
            this.entry = entry;
            this.sourceSlots = sourceSlots;
        }
    }

    /**
     * @param slots a list of (key, count, maxStack, category) for every non-empty slot, in slot order.
     */
    public static List<StackEntry> aggregate(List<StackEntry> slots) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, StackEntry> proto = new LinkedHashMap<>();
        for (StackEntry e : slots) {
            String id = e.key().identity();
            long[] acc = counts.get(id);
            if (acc == null) {
                acc = new long[]{0L};
                counts.put(id, acc);
                proto.put(id, e);
            }
            acc[0] = Math.addExact(acc[0], e.count());
        }
        List<StackEntry> out = new ArrayList<>(counts.size());
        for (Map.Entry<String, long[]> en : counts.entrySet()) {
            StackEntry p = proto.get(en.getKey());
            out.add(new StackEntry(p.key(), en.getValue()[0], p.maxStack(), p.category()));
        }
        out.sort(Comparator.comparing(a -> a.key(), StackKey::compare));
        return out;
    }
}
