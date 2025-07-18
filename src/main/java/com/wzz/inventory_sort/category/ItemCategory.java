package com.wzz.inventory_sort.category;

public enum ItemCategory {
        WEAPONS(1),      // 武器
        TOOLS(2),        // 工具
        ARMOR(3),        // 护甲
        FOOD(4),         // 食物
        POTIONS(5),      // 药水
        BLOCKS(6),       // 方块
        MATERIALS(7),    // 材料
        REDSTONE(8),     // 红石
        DECORATIONS(9),  // 装饰
        MUSIC(10),       // 音乐
        MISC(99);        // 杂项

        private final int priority;

        ItemCategory(int priority) {
                this.priority = priority;
        }

        public int getPriority() {
                return priority;
        }
}