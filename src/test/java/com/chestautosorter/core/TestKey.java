package com.chestautosorter.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Simple in-memory StackKey for tests. */
public record TestKey(String itemId, String dataId) implements StackKey {
    public static TestKey of(String item) {
        return new TestKey(item, "");
    }

    public static TestKey of(String item, String data) {
        return new TestKey(item, data);
    }

    public static Map<String, Boolean> tags(String... present) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (String t : present) {
            m.put(t, Boolean.TRUE);
        }
        return m;
    }
}
