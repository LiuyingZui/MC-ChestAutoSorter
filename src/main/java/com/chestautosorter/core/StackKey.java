package com.chestautosorter.core;

import java.util.Objects;

/**
 * Identity of a stack for merging purposes: item type + the full set of data that affects sameness
 * (components). Count is NOT part of identity. Implementations must derive {@code dataId} from the
 * complete component set, never from a localised display name.
 */
public interface StackKey {

    /** Stable string identifying the item type, e.g. "minecraft:oak_log". */
    String itemId();

    /** Stable string identifying the complete data/components of the stack. */
    String dataId();

    /** Whether two keys may be merged into one stack. */
    default boolean sameAs(StackKey other) {
        return other != null && itemId().equals(other.itemId()) && dataId().equals(other.dataId());
    }

    /** Total identity used as a map key. */
    default String identity() {
        return itemId() + "\u0000" + dataId();
    }

    /** Comparable, deterministic ordering by identity. */
    static int compare(StackKey a, StackKey b) {
        int c = a.itemId().compareTo(b.itemId());
        if (c != 0) {
            return c;
        }
        return a.dataId().compareTo(b.dataId());
    }

    static boolean equal(StackKey a, StackKey b) {
        return Objects.equals(a == null ? null : a.identity(), b == null ? null : b.identity());
    }
}
