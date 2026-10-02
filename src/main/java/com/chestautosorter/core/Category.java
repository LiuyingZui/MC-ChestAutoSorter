package com.chestautosorter.core;

/**
 * Target categories. Order here is the display/segment order used by the planner.
 */
public enum Category {
    WOOD("木材"),
    STONE_BUILDING("石材/建筑"),
    ORE("矿物"),
    PLANT("植物"),
    CROP_FOOD("农作物/食物"),
    MOB_DROP("生物掉落"),
    REDSTONE("红石"),
    TOOL_EQUIPMENT("工具/装备"),
    MISC("其他");

    private final String zh;

    Category(String zh) {
        this.zh = zh;
    }

    public String zh() {
        return zh;
    }
}
