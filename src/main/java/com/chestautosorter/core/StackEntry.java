package com.chestautosorter.core;

/**
 * A single stack entry in the abstract planner model. {@code key} is the merge identity,
 * {@code count} is the total number of items, {@code maxStack} is the runtime per-slot limit for
 * this item inside the target container.
 */
public record StackEntry(StackKey key, long count, int maxStack, Category category) {
    public StackEntry {
        if (count < 0) {
            throw new IllegalArgumentException("negative count");
        }
        if (maxStack < 1) {
            throw new IllegalArgumentException("maxStack must be >= 1");
        }
    }
}
