package com.chestautosorter.core;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure classification logic, driven by a {@link Rules} lookup so it can be unit-tested without a game.
 * Priority (as implemented by {@link RuleEngine} in-game):
 *   1. user explicit item ids
 *   2. user tag rules
 *   3. verified built-in tag/item rules
 *   4. MISC
 */
public final class ItemClassifier {

    /** Abstraction over "does this item carry tag X / is it item Y". */
    public interface Probe {
        boolean hasTag(String tagId);

        boolean isItem(String itemId);
    }

    /** A tag rule: if the item has tagId, classify as category. */
    public record TagRule(String tagId, Category category) {}

    private final Map<String, Category> itemOverrides;
    private final List<TagRule> tagRules;
    private final List<TagRule> builtinRules;

    public ItemClassifier(Map<String, Category> itemOverrides, List<TagRule> userTagRules, List<TagRule> builtinRules) {
        this.itemOverrides = new HashMap<>(itemOverrides);
        this.tagRules = List.copyOf(userTagRules);
        this.builtinRules = List.copyOf(builtinRules);
    }

    public Category classify(String itemId, Probe probe) {
        Category c = itemOverrides.get(itemId);
        if (c != null) {
            return c;
        }
        for (TagRule r : tagRules) {
            if (probe.hasTag(r.tagId())) {
                return r.category();
            }
        }
        for (TagRule r : builtinRules) {
            if (probe.hasTag(r.tagId()) || probe.isItem(r.tagId())) {
                return r.category();
            }
        }
        return Category.MISC;
    }

    /**
     * Built-in rules, verified against the 26.3 vanilla tag set. Order matters: more specific first.
     * This list intentionally avoids invented tags.
     */
    public static List<TagRule> defaultBuiltinRules() {
        return List.of(
                // --- Tools / equipment first (so an iron pickaxe is not classified as ore) ---
                new TagRule("minecraft:pickaxes", Category.TOOL_EQUIPMENT),
                new TagRule("minecraft:axes", Category.TOOL_EQUIPMENT),
                new TagRule("minecraft:shovels", Category.TOOL_EQUIPMENT),
                new TagRule("minecraft:hoes", Category.TOOL_EQUIPMENT),
                new TagRule("minecraft:swords", Category.TOOL_EQUIPMENT),
                new TagRule("minecraft:spears", Category.TOOL_EQUIPMENT),
                new TagRule("minecraft:mace", Category.TOOL_EQUIPMENT),
                new TagRule("minecraft:trident", Category.TOOL_EQUIPMENT),
                new TagRule("minecraft:weapon", Category.TOOL_EQUIPMENT),
                new TagRule("minecraft:melee_weapon", Category.TOOL_EQUIPMENT),
                new TagRule("minecraft:armor", Category.TOOL_EQUIPMENT),
                new TagRule("minecraft:head_armor", Category.TOOL_EQUIPMENT),
                new TagRule("minecraft:chest_armor", Category.TOOL_EQUIPMENT),
                new TagRule("minecraft:leg_armor", Category.TOOL_EQUIPMENT),
                new TagRule("minecraft:foot_armor", Category.TOOL_EQUIPMENT),
                new TagRule("minecraft:durability", Category.TOOL_EQUIPMENT),
                new TagRule("minecraft:arrows", Category.TOOL_EQUIPMENT),
                new TagRule("minecraft:bow", Category.TOOL_EQUIPMENT),
                new TagRule("minecraft:crossbow", Category.TOOL_EQUIPMENT),
                new TagRule("minecraft:fishing", Category.TOOL_EQUIPMENT),

                // --- Ore (raw ores, gems, ingots, ore blocks). Explicit ids where no tag exists. ---
                new TagRule("minecraft:ores", Category.ORE),
                new TagRule("minecraft:iron_ores", Category.ORE),
                new TagRule("minecraft:gold_ores", Category.ORE),
                new TagRule("minecraft:copper_ores", Category.ORE),
                new TagRule("minecraft:coal_ores", Category.ORE),
                new TagRule("minecraft:diamond_ores", Category.ORE),
                new TagRule("minecraft:emerald_ores", Category.ORE),
                new TagRule("minecraft:lapis_ores", Category.ORE),
                new TagRule("minecraft:redstone_ores", Category.ORE),
                new TagRule("minecraft:coals", Category.ORE),
                new TagRule("minecraft:metal_nuggets", Category.ORE),
                new TagRule("minecraft:trim_materials", Category.ORE),

                // --- Redstone ---
                new TagRule("minecraft:buttons", Category.REDSTONE),
                new TagRule("minecraft:stone_buttons", Category.REDSTONE),
                new TagRule("minecraft:wooden_buttons", Category.REDSTONE),
                new TagRule("minecraft:rails", Category.REDSTONE),
                new TagRule("minecraft:lightning_rods", Category.REDSTONE),

                // --- Wood (logs first so leaves do not fall here) ---
                new TagRule("minecraft:logs", Category.WOOD),
                new TagRule("minecraft:logs_that_burn", Category.WOOD),
                new TagRule("minecraft:planks", Category.WOOD),
                new TagRule("minecraft:wooden_slabs", Category.WOOD),
                new TagRule("minecraft:wooden_stairs", Category.WOOD),
                new TagRule("minecraft:wooden_fences", Category.WOOD),
                new TagRule("minecraft:wooden_doors", Category.WOOD),
                new TagRule("minecraft:wooden_trapdoors", Category.WOOD),
                new TagRule("minecraft:wooden_pressure_plates", Category.WOOD),
                new TagRule("minecraft:saplings", Category.PLANT),
                new TagRule("minecraft:leaves", Category.PLANT),

                // --- Plant ---
                new TagRule("minecraft:flowers", Category.PLANT),
                new TagRule("minecraft:small_flowers", Category.PLANT),
                new TagRule("minecraft:mushrooms", Category.PLANT),
                new TagRule("minecraft:grass_blocks", Category.PLANT),
                new TagRule("minecraft:moss_blocks", Category.PLANT),
                new TagRule("minecraft:wart_blocks", Category.PLANT),
                new TagRule("minecraft:wool", Category.PLANT),

                // --- Crop / food ---
                new TagRule("minecraft:meat", Category.CROP_FOOD),
                new TagRule("minecraft:fishes", Category.CROP_FOOD),
                new TagRule("minecraft:eggs", Category.CROP_FOOD),

                // --- Mob drops ---
                new TagRule("minecraft:skulls", Category.MOB_DROP),
                new TagRule("minecraft:banners", Category.MOB_DROP),

                // --- Stone / building ---
                new TagRule("minecraft:stone_bricks", Category.STONE_BUILDING),
                new TagRule("minecraft:stone_crafting_materials", Category.STONE_BUILDING),
                new TagRule("minecraft:stone_tool_materials", Category.STONE_BUILDING),
                new TagRule("minecraft:stairs", Category.STONE_BUILDING),
                new TagRule("minecraft:slabs", Category.STONE_BUILDING),
                new TagRule("minecraft:walls", Category.STONE_BUILDING),
                new TagRule("minecraft:fences", Category.STONE_BUILDING),
                new TagRule("minecraft:doors", Category.STONE_BUILDING),
                new TagRule("minecraft:trapdoors", Category.STONE_BUILDING),
                new TagRule("minecraft:sand", Category.STONE_BUILDING),
                new TagRule("minecraft:dirt", Category.STONE_BUILDING),
                new TagRule("minecraft:mud", Category.STONE_BUILDING),
                new TagRule("minecraft:terracotta", Category.STONE_BUILDING),
                new TagRule("minecraft:glazed_terracotta", Category.STONE_BUILDING),
                new TagRule("minecraft:concrete", Category.STONE_BUILDING),
                new TagRule("minecraft:concrete_powders", Category.STONE_BUILDING)
        );
    }

    /** Explicit item-id fallbacks where vanilla has no suitable tag (verified ids only). */
    public static Map<String, Category> defaultItemOverrides() {
        Map<String, Category> m = new HashMap<>();
        m.put("minecraft:wheat", Category.CROP_FOOD);
        m.put("minecraft:wheat_seeds", Category.CROP_FOOD);
        m.put("minecraft:beetroot", Category.CROP_FOOD);
        m.put("minecraft:beetroot_seeds", Category.CROP_FOOD);
        m.put("minecraft:carrot", Category.CROP_FOOD);
        m.put("minecraft:potato", Category.CROP_FOOD);
        m.put("minecraft:baked_potato", Category.CROP_FOOD);
        m.put("minecraft:poisonous_potato", Category.CROP_FOOD);
        m.put("minecraft:apple", Category.CROP_FOOD);
        m.put("minecraft:bread", Category.CROP_FOOD);
        m.put("minecraft:melon_slice", Category.CROP_FOOD);
        m.put("minecraft:pumpkin_pie", Category.CROP_FOOD);
        m.put("minecraft:sugar_cane", Category.PLANT);
        m.put("minecraft:bamboo", Category.PLANT);
        m.put("minecraft:cactus", Category.PLANT);
        m.put("minecraft:kelp", Category.PLANT);
        m.put("minecraft:iron_ingot", Category.ORE);
        m.put("minecraft:gold_ingot", Category.ORE);
        m.put("minecraft:copper_ingot", Category.ORE);
        m.put("minecraft:netherite_ingot", Category.ORE);
        m.put("minecraft:iron_nugget", Category.ORE);
        m.put("minecraft:gold_nugget", Category.ORE);
        m.put("minecraft:diamond", Category.ORE);
        m.put("minecraft:emerald", Category.ORE);
        m.put("minecraft:lapis_lazuli", Category.ORE);
        m.put("minecraft:quartz", Category.ORE);
        m.put("minecraft:amethyst_shard", Category.ORE);
        m.put("minecraft:redstone", Category.REDSTONE);
        m.put("minecraft:redstone_torch", Category.REDSTONE);
        m.put("minecraft:repeater", Category.REDSTONE);
        m.put("minecraft:comparator", Category.REDSTONE);
        m.put("minecraft:piston", Category.REDSTONE);
        m.put("minecraft:sticky_piston", Category.REDSTONE);
        m.put("minecraft:observer", Category.REDSTONE);
        m.put("minecraft:dispenser", Category.REDSTONE);
        m.put("minecraft:dropper", Category.REDSTONE);
        m.put("minecraft:hopper", Category.REDSTONE);
        m.put("minecraft:lever", Category.REDSTONE);
        m.put("minecraft:tripwire_hook", Category.REDSTONE);
        m.put("minecraft:target", Category.REDSTONE);
        m.put("minecraft:daylight_detector", Category.REDSTONE);
        m.put("minecraft:bone", Category.MOB_DROP);
        m.put("minecraft:bone_meal", Category.MOB_DROP);
        m.put("minecraft:string", Category.MOB_DROP);
        m.put("minecraft:spider_eye", Category.MOB_DROP);
        m.put("minecraft:gunpowder", Category.MOB_DROP);
        m.put("minecraft:slime_ball", Category.MOB_DROP);
        m.put("minecraft:ender_pearl", Category.MOB_DROP);
        m.put("minecraft:blaze_rod", Category.MOB_DROP);
        m.put("minecraft:ghast_tear", Category.MOB_DROP);
        m.put("minecraft:leather", Category.MOB_DROP);
        m.put("minecraft:feather", Category.MOB_DROP);
        m.put("minecraft:ink_sac", Category.MOB_DROP);
        m.put("minecraft:glow_ink_sac", Category.MOB_DROP);
        m.put("minecraft:rotten_flesh", Category.MOB_DROP);
        m.put("minecraft:magma_cream", Category.MOB_DROP);
        m.put("minecraft:phantom_membrane", Category.MOB_DROP);
        m.put("minecraft:rabbit_hide", Category.MOB_DROP);
        m.put("minecraft:turtle_scute", Category.MOB_DROP);
        m.put("minecraft:armadillo_scute", Category.MOB_DROP);
        m.put("minecraft:torch", Category.MISC);
        m.put("minecraft:crafting_table", Category.WOOD);
        m.put("minecraft:furnace", Category.STONE_BUILDING);
        m.put("minecraft:chest", Category.WOOD);
        m.put("minecraft:trapped_chest", Category.REDSTONE);
        m.put("minecraft:barrel", Category.WOOD);

        // Plain stone-family blocks carry no vanilla tag (verified against 26.3 data: e.g.
        // `minecraft:stone` is absent from stone_crafting_materials / stone_tool_materials /
        // stone_bricks, which list only cobblestone, blackstone and cobbled_deepslate).
        m.put("minecraft:stone", Category.STONE_BUILDING);
        m.put("minecraft:smooth_stone", Category.STONE_BUILDING);
        m.put("minecraft:stone_slab", Category.STONE_BUILDING);
        m.put("minecraft:smooth_stone_slab", Category.STONE_BUILDING);
        m.put("minecraft:stone_stairs", Category.STONE_BUILDING);
        m.put("minecraft:cobblestone", Category.STONE_BUILDING);
        m.put("minecraft:cobblestone_slab", Category.STONE_BUILDING);
        m.put("minecraft:cobblestone_stairs", Category.STONE_BUILDING);
        m.put("minecraft:cobblestone_wall", Category.STONE_BUILDING);
        m.put("minecraft:mossy_cobblestone", Category.STONE_BUILDING);
        m.put("minecraft:blackstone", Category.STONE_BUILDING);
        m.put("minecraft:cobbled_deepslate", Category.STONE_BUILDING);
        m.put("minecraft:stone_bricks", Category.STONE_BUILDING);
        m.put("minecraft:bricks", Category.STONE_BUILDING);
        m.put("minecraft:sandstone", Category.STONE_BUILDING);
        m.put("minecraft:deepslate", Category.STONE_BUILDING);
        m.put("minecraft:deepslate_bricks", Category.STONE_BUILDING);
        m.put("minecraft:nether_bricks", Category.STONE_BUILDING);
        m.put("minecraft:quartz_block", Category.STONE_BUILDING);
        m.put("minecraft:prismarine", Category.STONE_BUILDING);
        m.put("minecraft:obsidian", Category.STONE_BUILDING);
        m.put("minecraft:glass", Category.STONE_BUILDING);
        return m;
    }

    /** Item ids that must be split off into their own handling regardless of tag rules. */
    public static Set<String> alwaysSupportedBeside() {
        return Set.of();
    }
}
