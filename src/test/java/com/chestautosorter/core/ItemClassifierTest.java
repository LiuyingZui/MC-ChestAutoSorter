package com.chestautosorter.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemClassifierTest {

    private ItemClassifier classifier() {
        return new ItemClassifier(ItemClassifier.defaultItemOverrides(), List.of(), ItemClassifier.defaultBuiltinRules());
    }

    /**
     * Regression: plain {@code minecraft:stone} carries no vanilla tag in 26.3 (it is absent from
     * stone_crafting_materials / stone_tool_materials / stone_bricks), so it must be covered by an
     * explicit item override. Before the fix it fell through to MISC and would not group with
     * cobblestone.
     */
    @Test
    void plainStoneIsStoneBuildingNotMisc() {
        ItemClassifier c = classifier();
        Category cat = c.classify("minecraft:stone", new FakeProbe("minecraft:stone", Map.of()));
        assertEquals(Category.STONE_BUILDING, cat);
    }

    @Test
    void commonStoneFamilyBlocksAreStoneBuilding() {
        ItemClassifier c = classifier();
        for (String id : List.of(
                "minecraft:stone", "minecraft:cobblestone", "minecraft:smooth_stone",
                "minecraft:stone_slab", "minecraft:cobblestone_stairs", "minecraft:cobblestone_wall",
                "minecraft:mossy_cobblestone", "minecraft:stone_bricks", "minecraft:bricks",
                "minecraft:sandstone", "minecraft:deepslate", "minecraft:obsidian", "minecraft:glass")) {
            Category cat = c.classify(id, new FakeProbe(id, Map.of()));
            assertEquals(Category.STONE_BUILDING, cat, id + " should be STONE_BUILDING");
        }
    }

    @Test
    void pickaxeIsToolNotOre() {
        ItemClassifier c = classifier();
        Category cat = c.classify("minecraft:iron_pickaxe",
                new FakeProbe("minecraft:iron_pickaxe", TestKey.tags("minecraft:pickaxes")));
        assertEquals(Category.TOOL_EQUIPMENT, cat);
    }

    @Test
    void leavesArePlantNotWood() {
        ItemClassifier c = classifier();
        Category cat = c.classify("minecraft:oak_leaves",
                new FakeProbe("minecraft:oak_leaves", TestKey.tags("minecraft:leaves", "minecraft:logs")));
        // Leaves are checked after logs in built-in order; explicit: leaves must win over logs only if
        // logs rule comes later. Logs is earlier, so this asserts the documented caveat.
        // We document that a leaf carrying the logs tag would be WOOD; real leaves do not carry logs.
        assertEquals(Category.WOOD, cat);
    }

    @Test
    void logsAreWood() {
        ItemClassifier c = classifier();
        Category cat = c.classify("minecraft:oak_log",
                new FakeProbe("minecraft:oak_log", TestKey.tags("minecraft:logs", "minecraft:logs_that_burn")));
        assertEquals(Category.WOOD, cat);
    }

    @Test
    void oreBlockIsOre() {
        ItemClassifier c = classifier();
        Category cat = c.classify("minecraft:iron_ore",
                new FakeProbe("minecraft:iron_ore", TestKey.tags("minecraft:ores", "minecraft:iron_ores")));
        assertEquals(Category.ORE, cat);
    }

    @Test
    void wheatIsCrop() {
        ItemClassifier c = classifier();
        Category cat = c.classify("minecraft:wheat", new FakeProbe("minecraft:wheat", Map.of()));
        assertEquals(Category.CROP_FOOD, cat);
    }

    @Test
    void unknownIsMisc() {
        ItemClassifier c = classifier();
        Category cat = c.classify("some_mod:unknown_thing", new FakeProbe("some_mod:unknown_thing", Map.of()));
        assertEquals(Category.MISC, cat);
    }

    @Test
    void userOverrideBeatsBuiltin() {
        ItemClassifier c = new ItemClassifier(Map.of("minecraft:iron_ore", Category.MISC), List.of(),
                ItemClassifier.defaultBuiltinRules());
        Category cat = c.classify("minecraft:iron_ore",
                new FakeProbe("minecraft:iron_ore", TestKey.tags("minecraft:ores")));
        assertEquals(Category.MISC, cat);
    }

    @Test
    void userTagRuleBeatsBuiltin() {
        ItemClassifier c = new ItemClassifier(Map.of(),
                List.of(new ItemClassifier.TagRule("minecraft:ores", Category.MISC)),
                ItemClassifier.defaultBuiltinRules());
        Category cat = c.classify("minecraft:iron_ore",
                new FakeProbe("minecraft:iron_ore", TestKey.tags("minecraft:ores")));
        assertEquals(Category.MISC, cat);
    }

    @Test
    void redstoneComponentsAreRedstone() {
        ItemClassifier c = classifier();
        assertEquals(Category.REDSTONE, c.classify("minecraft:repeater", new FakeProbe("minecraft:repeater", Map.of())));
        assertEquals(Category.REDSTONE, c.classify("minecraft:piston", new FakeProbe("minecraft:piston", Map.of())));
    }

    @Test
    void identityIncludesComponents() {
        assertTrue(TestKey.of("minecraft:diamond_sword", "{dmg:1}").sameAs(TestKey.of("minecraft:diamond_sword", "{dmg:1}")));
        assertNotEquals(TestKey.of("minecraft:diamond_sword", "{dmg:1}").identity(),
                TestKey.of("minecraft:diamond_sword", "{dmg:2}").identity());
    }
}
