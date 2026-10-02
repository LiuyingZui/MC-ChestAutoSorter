package com.chestautosorter;

import net.minecraft.resources.Identifier;

public final class CAS {
    public static final String MOD_ID = "chestautosorter";

    private CAS() {}

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
