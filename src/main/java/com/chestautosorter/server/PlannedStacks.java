package com.chestautosorter.server;

import com.chestautosorter.core.StackEntry;
import com.chestautosorter.core.StackKey;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps planner identities back to concrete prototype stacks so a planned count can be materialized
 * without losing components. Populated from the same snapshot the plan was built from.
 */
public final class PlannedStacks {

    private static final ThreadLocal<Map<String, ItemStack>> ACTIVE = ThreadLocal.withInitial(HashMap::new);

    private PlannedStacks() {}

    public static void begin() {
        ACTIVE.get().clear();
    }

    public static void put(StackKey key, ItemStack prototype) {
        ACTIVE.get().put(key.identity(), prototype.copyWithCount(1));
    }

    public static ItemStack materialize(StackEntry entry) {
        ItemStack proto = ACTIVE.get().get(entry.key().identity());
        if (proto == null) {
            throw new IllegalStateException("no prototype for " + entry.key().identity());
        }
        ItemStack out = proto.copy();
        out.setCount((int) Math.min(entry.count(), Integer.MAX_VALUE));
        return out;
    }

    public static void end() {
        ACTIVE.remove();
    }
}
