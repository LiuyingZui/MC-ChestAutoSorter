package com.chestautosorter.core;

import java.util.Map;

/** Test double for {@link ItemClassifier.Probe}. */
public final class FakeProbe implements ItemClassifier.Probe {
    private final Map<String, Boolean> tags;
    private final String itemId;

    public FakeProbe(String itemId, Map<String, Boolean> tags) {
        this.itemId = itemId;
        this.tags = tags;
    }

    @Override
    public boolean hasTag(String tagId) {
        return Boolean.TRUE.equals(tags.get(tagId));
    }

    @Override
    public boolean isItem(String itemId) {
        return this.itemId.equals(itemId);
    }
}
