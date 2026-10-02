package com.chestautosorter.server;

import com.chestautosorter.core.ItemClassifier;
import com.chestautosorter.core.McStackKey;
import com.chestautosorter.core.SortPlanner;
import com.chestautosorter.core.StackAggregator;
import com.chestautosorter.core.StackEntry;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The inventory transformation itself — snapshot entries -> aggregate -> plan -> commit — factored out
 * of {@link SortService} so that the network handler and in-game verification tests drive the exact
 * same orchestration.
 *
 * <p>Two phases are exposed so preview can reuse planning without ever reaching the write path:
 * <ul>
 *   <li>{@link #plan} — pure computation, touches no container state.</li>
 *   <li>{@link #commit} — writes, verifies, and rolls back on failure.</li>
 * </ul>
 */
public final class SortPipelines {

    /** Shared classifier; identical rules for the network path and tests. */
    private static final ItemClassifier CLASSIFIER = new ItemClassifier(
            ItemClassifier.defaultItemOverrides(), List.of(), ItemClassifier.defaultBuiltinRules());

    private SortPipelines() {}

    /** Result of the planning phase. Holds everything needed to preview or to commit. */
    public static final class Plan {
        public final SortPlanner.Result planner;
        public final List<Container> containers;
        public final List<ChestSnapshot> snaps;
        public final List<Integer> sizes;
        public final List<StackEntry> aggregated;

        Plan(SortPlanner.Result planner, List<Container> containers, List<ChestSnapshot> snaps,
             List<Integer> sizes, List<StackEntry> aggregated) {
            this.planner = planner;
            this.containers = containers;
            this.snaps = snaps;
            this.sizes = sizes;
            this.aggregated = aggregated;
        }

        public boolean isCancelled() {
            return planner.isCancelled();
        }

        public List<String> messages() {
            return planner.messages;
        }
    }

    /** Result of the commit phase. */
    public static final class Outcome {
        public final boolean success;
        public final boolean cancelled;
        public final String message;
        public final SortPlanner.Result plan;
        public final SafeCommitter.Outcome commit;

        Outcome(boolean success, boolean cancelled, String message, SortPlanner.Result plan, SafeCommitter.Outcome commit) {
            this.success = success;
            this.cancelled = cancelled;
            this.message = message;
            this.plan = plan;
            this.commit = commit;
        }
    }

    /** Builds planner entries for every non-empty slot, using the shared per-item conversion. */
    public static List<StackEntry> entries(List<ChestSnapshot> snaps, List<Container> containers) {
        List<StackEntry> out = new ArrayList<>();
        for (int ci = 0; ci < snaps.size(); ci++) {
            Container c = containers.get(ci);
            ChestSnapshot snap = snaps.get(ci);
            for (int i = 0; i < snap.size; i++) {
                ItemStack s = snap.slots.get(i);
                if (s.isEmpty()) {
                    continue;
                }
                out.add(ChestSnapshot.entryFor(c, s, CLASSIFIER));
            }
        }
        return out;
    }

    /**
     * Classify, aggregate and plan. Reads container state (for per-slot limits) but never writes.
     *
     * @param allowMixed whether compact mixed-category packing may be used as a fallback
     */
    public static Plan plan(List<Container> containers, List<ChestSnapshot> snaps, boolean allowMixed) {
        List<StackEntry> entries = entries(snaps, containers);
        List<Integer> sizes = new ArrayList<>();
        for (ChestSnapshot s : snaps) {
            sizes.add(s.size);
        }
        List<StackEntry> aggregated = StackAggregator.aggregate(entries);
        SortPlanner.Result result = SortPlanner.plan(sizes, aggregated, allowMixed);
        return new Plan(result, containers, snaps, sizes, aggregated);
    }

    /** Applies an already-computed plan: writes, reads back, verifies, and rolls back on failure. */
    public static SafeCommitter.Outcome commit(Plan plan) {
        List<SafeCommitter.BlockPosKey> keys = new ArrayList<>();
        for (ChestSnapshot s : plan.snaps) {
            keys.add(new SafeCommitter.BlockPosKey(s.origin.getX(), s.origin.getY(), s.origin.getZ()));
        }
        return commit(plan, keys);
    }

    /**
     * Applies an already-computed plan, publishing the pre-images of {@code keys} to the recovery log
     * immediately before the first write. A failure to persist recovery data aborts the sort.
     */
    public static SafeCommitter.Outcome commit(Plan plan, List<SafeCommitter.BlockPosKey> keys) {
        PlannedStacks.begin();
        try {
            for (int ci = 0; ci < plan.snaps.size(); ci++) {
                ChestSnapshot snap = plan.snaps.get(ci);
                for (int i = 0; i < snap.size; i++) {
                    ItemStack s = snap.slots.get(i);
                    if (!s.isEmpty()) {
                        PlannedStacks.put(McStackKey.of(s), s);
                    }
                }
            }
            SafeCommitter.Prepared prepared = SafeCommitter.prepare(plan.containers, keys);
            return SafeCommitter.commit(prepared, plan.planner.layout);
        } finally {
            PlannedStacks.end();
        }
    }

    /**
     * Convenience: plan and commit in one call. Used by in-game verification tests.
     */
    public static Outcome runProduction(List<Container> containers, List<ChestSnapshot> snaps, boolean allowMixed) {
        Plan plan = plan(containers, snaps, allowMixed);
        if (plan.isCancelled()) {
            return new Outcome(false, true, String.join("; ", plan.messages()), plan.planner, null);
        }
        SafeCommitter.Outcome outcome = commit(plan);
        return new Outcome(outcome.success, false, outcome.message, plan.planner, outcome);
    }
}
