package com.wzz.inventory_sort.config;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

public class SortConfig {

    public static final ForgeConfigSpec COMMON_SPEC;
    public static final CommonConfig COMMON;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        COMMON = new CommonConfig(builder);
        COMMON_SPEC = builder.build();
    }

    @SuppressWarnings("removal")
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, COMMON_SPEC);
    }

    // ─── 排序模式 ──────────────────────────────────────────────────────────────

    public enum SortMode {
        /** 按分类 → 子分类 → 名称，逐行填充（默认） */
        CATEGORY("按分类排序（逐行）"),
        /** 按分类 → 子分类 → 名称，逐列填充 */
        CATEGORY_COLUMN("按分类排序（逐列）"),
        /** 纯按注册表 ID 字母，逐行 */
        NAME("按名称排序（逐行）"),
        /** 纯按注册表 ID 字母，逐列 */
        NAME_COLUMN("按名称排序（逐列）"),
        /** 数量从多到少，逐行 */
        COUNT("按数量排序（多到少）"),
        /** 数量从少到多，逐行 */
        COUNT_ASC("按数量排序（少到多）"),
        /** 分类优先，同类内数量从多到少 */
        CATEGORY_COUNT("按分类+数量排序");

        private final String displayName;

        SortMode(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    // ─── 配置字段 ──────────────────────────────────────────────────────────────

    public static class CommonConfig {

        public final ForgeConfigSpec.EnumValue<SortMode> sortMode;
        public final ForgeConfigSpec.BooleanValue sortSound;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> whitelist;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> guiBlacklist;

        public CommonConfig(ForgeConfigSpec.Builder builder) {
            builder.push("sorting");

            sortMode = builder
                    .comment(
                        "整理时使用的排序方式",
                        "  CATEGORY        - 按物品分类排序，同类内按名称，逐行填充（默认）",
                        "  CATEGORY_COLUMN - 按物品分类排序，同类内按名称，逐列填充",
                        "  NAME            - 按注册表 ID 字母顺序，逐行填充",
                        "  NAME_COLUMN     - 按注册表 ID 字母顺序，逐列填充",
                        "  COUNT           - 按数量从多到少，逐行填充",
                        "  COUNT_ASC       - 按数量从少到多，逐行填充",
                        "  CATEGORY_COUNT  - 按分类优先，同类内按数量从多到少"
                    )
                    .defineEnum("sortMode", SortMode.CATEGORY);

            sortSound = builder
                    .comment("整理时播放 UI 点击音效（默认开启）")
                    .define("sortSound", true);

            builder.pop();
            builder.push("whitelist");

            whitelist = builder
                    .comment(
                        "整理时跳过的物品 ID 列表，列表中的物品不会被移动",
                        "格式：\"minecraft:diamond\"，\"modid:item_name\"",
                        "可在游戏内 ConfigScreen 中管理"
                    )
                    .defineListAllowEmpty(
                        "whitelist",
                        new ArrayList<>(),
                        e -> e instanceof String s && ResourceLocation.isValidResourceLocation(s)
                    );

            guiBlacklist = builder
                    .comment(
                        "不触发整理的界面类名列表（精确匹配）",
                        "格式：\"net.minecraft.client.gui.screens.inventory.ChestScreen\"",
                        "或只写末段类名：\"ChestScreen\"（前缀匹配）",
                        "可在游戏内 ConfigScreen 中管理"
                    )
                    .defineListAllowEmpty(
                        "guiBlacklist",
                        new ArrayList<>(),
                        e -> e instanceof String s && !s.isBlank()
                    );

            builder.pop();
        }
    }

    // ─── 访问方法 ──────────────────────────────────────────────────────────────

    public static SortMode getSortMode() {
        return COMMON.sortMode.get();
    }

    public static boolean isSortSoundEnabled() {
        return COMMON.sortSound.get();
    }

    public static List<String> getWhitelist() {
        List<? extends String> raw = COMMON.whitelist.get();
        return new ArrayList<>(raw);
    }

    public static boolean isWhitelisted(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) return false;
        String idStr = id.toString();
        for (String entry : COMMON.whitelist.get()) {
            if (entry.equals(idStr)) return true;
        }
        return false;
    }

    public static void addToWhitelist(String itemId) {
        List<String> current = getWhitelist();
        if (!current.contains(itemId)) {
            current.add(itemId);
            COMMON.whitelist.set(current);
            COMMON_SPEC.save();
        }
    }

    public static List<String> getGuiBlacklist() {
        List<? extends String> raw = COMMON.guiBlacklist.get();
        return new ArrayList<>(raw);
    }

    /**
     * 检查当前屏幕类名是否在 GUI 黑名单中
     * 支持精确匹配或末段类名前缀匹配
     */
    public static boolean isGuiBlacklisted(String screenClassName) {
        for (String entry : COMMON.guiBlacklist.get()) {
            if (entry.isEmpty()) continue;
            if (screenClassName.equals(entry)) return true;
            // 末段类名匹配：entry 不含 "." 时做 endsWith
            if (!entry.contains(".") && screenClassName.endsWith("." + entry)) return true;
        }
        return false;
    }

    public static void addToGuiBlacklist(String className) {
        List<String> current = getGuiBlacklist();
        if (!current.contains(className)) {
            current.add(className);
            COMMON.guiBlacklist.set(current);
            COMMON_SPEC.save();
        }
    }

    public static void removeFromGuiBlacklist(String className) {
        List<String> current = getGuiBlacklist();
        if (current.remove(className)) {
            COMMON.guiBlacklist.set(current);
            COMMON_SPEC.save();
        }
    }

    public static void removeFromWhitelist(String itemId) {
        List<String> current = getWhitelist();
        if (current.remove(itemId)) {
            COMMON.whitelist.set(current);
            COMMON_SPEC.save();
        }
    }

    /** 判断该模式是否需要按列填充 */
    public static boolean isColumnMode(SortMode mode) {
        return mode == SortMode.CATEGORY_COLUMN || mode == SortMode.NAME_COLUMN;
    }
}
