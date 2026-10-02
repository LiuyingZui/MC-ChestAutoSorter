package com.chestautosorter.server;

import com.chestautosorter.core.McStackKey;
import com.chestautosorter.core.SortPlanner;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies a planner layout to real containers with verification and rollback.
 *
 * Commit protocol:
 *  1. re-read all target slots and compare against the snapshot (structure/inventory identity)
 *  2. write the planned layout slot by slot
 *  3. re-read all slots and verify they match the plan exactly
 *  4. on any mismatch, restore the original snapshot and verify the restore
 *
 * All of this runs on the server thread. No item is ever dropped or created.
 */
public final class SafeCommitter {

    private SafeCommitter() {}

    public static final class Prepared {
        public final List<Container> containers;
        public final List<BlockPosKey> keys;
        /** Original items per container, deep copies, for rollback. */
        public final List<List<ItemStack>> original;

        Prepared(List<Container> containers, List<BlockPosKey> keys, List<List<ItemStack>> original) {
            this.containers = containers;
            this.keys = keys;
            this.original = original;
        }
    }

    public record BlockPosKey(int x, int y, int z) {}

    /**
     * Seam for controlled failure injection in tests. Never installed by production code: the
     * default is a no-op singleton, and nothing in the game ever calls {@link #setHooks}.
     */
    public interface Hooks {
        Hooks NONE = new Hooks() {};

        /** Called immediately before slot {@code slot} of container {@code container} is written. */
        default void beforeWrite(Container container, int slot, ItemStack incoming) {}

        /** Called immediately after the write of the first batch, before verification. */
        default void afterWrites() {}

        /** Called immediately before a slot is restored during rollback. */
        default void beforeRestore(Container container, int slot) {}
    }

    private static final ThreadLocal<Hooks> HOOKS = ThreadLocal.withInitial(() -> Hooks.NONE);

    /** Test-only. Not called from any gameplay path. */
    public static void setHooks(Hooks hooks) {
        HOOKS.set(hooks == null ? Hooks.NONE : hooks);
    }

    /** Test-only. */
    public static void clearHooks() {
        HOOKS.remove();
    }

    public static final class Outcome {
        public final boolean success;
        public final String message;
        public final boolean rolledBack;
        public final boolean rollbackVerified;

        Outcome(boolean success, String message, boolean rolledBack, boolean rollbackVerified) {
            this.success = success;
            this.message = message;
            this.rolledBack = rolledBack;
            this.rollbackVerified = rollbackVerified;
        }
    }

    /** Captures deep copies of every slot of the given containers. */
    public static Prepared prepare(List<Container> containers, List<BlockPosKey> keys) {
        List<List<ItemStack>> original = new ArrayList<>(containers.size());
        for (Container c : containers) {
            List<ItemStack> copy = new ArrayList<>(c.getContainerSize());
            for (int i = 0; i < c.getContainerSize(); i++) {
                copy.add(c.getItem(i).copy());
            }
            original.add(copy);
        }
        return new Prepared(containers, keys, original);
    }

    /**
     * @param layout planner output in the same flat order used to build the plan
     *               (container-major, then slot)
     */
    public static Outcome commit(Prepared prepared, List<SortPlanner.SlotPlan> layout) {
        // 1. Verify the world still matches what we snapshotted.
        if (!matchesOriginal(prepared.containers, prepared.original)) {
            return new Outcome(false, "提交前检测到库存/结构变化，已取消（未写入）。", false, true);
        }

        // 2. Write.
        Hooks hooks = HOOKS.get();
        try {
            for (SortPlanner.SlotPlan p : layout) {
                Container c = prepared.containers.get(p.container);
                ItemStack toWrite = p.isEmpty() ? ItemStack.EMPTY : plannedStack(p);
                hooks.beforeWrite(c, p.slot, toWrite);
                c.setItem(p.slot, toWrite);
            }
            for (Container c : prepared.containers) {
                c.setChanged();
            }
            hooks.afterWrites();
        } catch (Throwable t) {
            boolean ok = restore(prepared);
            return new Outcome(false, "写入异常：" + t + "；已回滚。", true, ok);
        }

        // 3. Verify.
        if (!matchesLayout(prepared.containers, layout)) {
            boolean ok = restore(prepared);
            return new Outcome(false, "写入后回读不一致；已回滚。", true, ok);
        }

        // 4. Also verify global conservation.
        if (!conserves(prepared.original, prepared.containers)) {
            boolean ok = restore(prepared);
            return new Outcome(false, "提交后物品守恒校验失败；已回滚。", true, ok);
        }

        return new Outcome(true, "提交成功并通过回读与守恒校验。", false, true);
    }

    /** Restores the original snapshot and verifies the restore. */
    public static boolean restore(Prepared prepared) {
        Hooks hooks = HOOKS.get();
        try {
            for (int ci = 0; ci < prepared.containers.size(); ci++) {
                Container c = prepared.containers.get(ci);
                List<ItemStack> orig = prepared.original.get(ci);
                for (int i = 0; i < orig.size(); i++) {
                    hooks.beforeRestore(c, i);
                    c.setItem(i, orig.get(i).copy());
                }
                c.setChanged();
            }
        } catch (Throwable t) {
            return false;
        }
        return matchesOriginal(prepared.containers, prepared.original);
    }

    private static ItemStack plannedStack(SortPlanner.SlotPlan p) {
        // The layout carries counts only; the concrete stack data comes from the snapshot entry.
        // We rebuild via the entry's key by looking it up in the snapshot-backed map supplied by caller.
        return p.entry == null ? ItemStack.EMPTY : PlannedStacks.materialize(p.entry);
    }

    private static boolean matchesOriginal(List<Container> containers, List<List<ItemStack>> original) {
        for (int ci = 0; ci < containers.size(); ci++) {
            Container c = containers.get(ci);
            List<ItemStack> orig = original.get(ci);
            if (c.getContainerSize() != orig.size()) {
                return false;
            }
            for (int i = 0; i < orig.size(); i++) {
                if (!ItemStack.matches(c.getItem(i), orig.get(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean matchesLayout(List<Container> containers, List<SortPlanner.SlotPlan> layout) {
        for (SortPlanner.SlotPlan p : layout) {
            Container c = containers.get(p.container);
            ItemStack actual = c.getItem(p.slot);
            if (p.isEmpty()) {
                if (!actual.isEmpty()) {
                    return false;
                }
            } else {
                if (actual.isEmpty() || actual.getCount() != p.entry.count()) {
                    return false;
                }
                if (!com.chestautosorter.core.McStackKey.of(actual).sameAs(p.entry.key())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean conserves(List<List<ItemStack>> before, List<Container> after) {
        java.util.Map<String, Long> b = tally(before);
        java.util.Map<String, Long> a = new java.util.HashMap<>();
        for (Container c : after) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty()) {
                    a.merge(com.chestautosorter.core.McStackKey.of(s).identity(), (long) s.getCount(), Long::sum);
                }
            }
        }
        return b.equals(a);
    }

    private static java.util.Map<String, Long> tally(List<List<ItemStack>> lists) {
        java.util.Map<String, Long> m = new java.util.HashMap<>();
        for (List<ItemStack> l : lists) {
            for (ItemStack s : l) {
                if (!s.isEmpty()) {
                    m.merge(com.chestautosorter.core.McStackKey.of(s).identity(), (long) s.getCount(), Long::sum);
                }
            }
        }
        return m;
    }
}
