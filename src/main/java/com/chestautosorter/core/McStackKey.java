package com.chestautosorter.core;

import com.mojang.serialization.DataResult;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Minecraft-backed {@link StackKey}. {@code itemId} is the registry id; {@code dataId} is produced by
 * encoding the FULL stack (id + component patch) with {@link ItemStack#CODEC}, so enchantments,
 * damage, custom names, potion contents, nested container contents and any other component affect
 * identity. Count is excluded by encoding a 1-count copy.
 */
public final class McStackKey implements StackKey {

    private final Identifier itemId;
    private final String dataId;

    private McStackKey(Identifier itemId, String dataId) {
        this.itemId = itemId;
        this.dataId = dataId;
    }

    public static McStackKey of(ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) {
            id = Identifier.fromNamespaceAndPath("unknown", "unknown");
        }
        String data = encodeData(stack);
        return new McStackKey(id, data);
    }

    private static String encodeData(ItemStack stack) {
        ItemStack probe = stack.copyWithCount(1);
        DataResult<Tag> encoded = ItemStack.CODEC.encodeStart(NbtOps.INSTANCE, probe);
        // On any encode failure fall back to a component-backed identity rather than throwing.
        return encoded.result().map(Object::toString).orElseGet(() -> stack.getComponents().toString());
    }

    @Override
    public String itemId() {
        return itemId.toString();
    }

    @Override
    public String dataId() {
        return dataId;
    }
}
