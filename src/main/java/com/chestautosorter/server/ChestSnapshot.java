package com.chestautosorter.server;

import com.chestautosorter.core.Category;
import com.chestautosorter.core.ItemClassifier;
import com.chestautosorter.core.McStackKey;
import com.chestautosorter.core.StackEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/** Read-only snapshot of a logical container, with independent ItemStack copies. */
public final class ChestSnapshot {

    public final BlockPos origin;
    public final boolean doubleChest;
    public final List<ItemStack> slots;
    public final int size;

    private ChestSnapshot(BlockPos origin, boolean doubleChest, List<ItemStack> slots, int size) {
        this.origin = origin;
        this.doubleChest = doubleChest;
        this.slots = slots;
        this.size = size;
    }

    public static ChestSnapshot of(BlockPos origin, Container container, boolean doubleChest) {
        int size = container.getContainerSize();
        List<ItemStack> copies = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            copies.add(container.getItem(i).copy());
        }
        return new ChestSnapshot(origin.immutable(), doubleChest, copies, size);
    }

    /** Builds aggregated planner entries from this snapshot, using the real per-item slot limit. */
    public List<StackEntry> toEntries(Container container, ItemClassifier classifier, Level level) {
        List<StackEntry> out = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ItemStack s = slots.get(i);
            if (s.isEmpty()) {
                continue;
            }
            out.add(entryFor(container, s, classifier));
        }
        return out;
    }

    /** Builds a single planner entry (real slot limit + classification) for one stack. */
    public static StackEntry entryFor(Container container, ItemStack s, ItemClassifier classifier) {
        int max = container.getMaxStackSize(s);
        if (max < 1) {
            max = Math.max(1, s.getMaxStackSize());
        }
        return new StackEntry(McStackKey.of(s), s.getCount(), max, classify(s, classifier));
    }

    private static Category classify(ItemStack s, ItemClassifier classifier) {
        return classifier.classify(itemId(s), new NeoProbe(s));
    }

    public static String itemId(ItemStack s) {
        Identifier id = BuiltInRegistries.ITEM.getKey(s.getItem());
        return id == null ? "unknown:unknown" : id.toString();
    }

    /** Probe backed by the real item and its tag holders. */
    public static final class NeoProbe implements ItemClassifier.Probe {
        private final ItemStack stack;

        public NeoProbe(ItemStack stack) {
            this.stack = stack;
        }

        @Override
        public boolean hasTag(String tagId) {
            Identifier id = Identifier.tryParse(tagId);
            if (id == null) {
                return false;
            }
            var tag = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, id);
            return stack.getItem().builtInRegistryHolder().is(tag);
        }

        @Override
        public boolean isItem(String itemId) {
            return itemId(stack).equals(itemId);
        }
    }
}
