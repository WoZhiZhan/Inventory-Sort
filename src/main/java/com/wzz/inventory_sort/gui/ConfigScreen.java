package com.wzz.inventory_sort.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.wzz.inventory_sort.config.SortConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Inventory Sort 配置界面
 * Tab 0：常规（排序方式、音效）
 * Tab 1：物品白名单（整理时跳过的物品）
 * Tab 2：GUI 黑名单（不触发整理的界面）
 */
@OnlyIn(Dist.CLIENT)
public class ConfigScreen extends Screen {

    // ── 布局常量 ──────────────────────────────────────────────────────────────
    private static final int W         = 330;
    private static final int H         = 280;
    private static final int TITLE_H   = 26;
    private static final int TAB_H     = 20;
    private static final int FOOT_H    = 30;
    private static final int CONTENT_Y = TITLE_H + TAB_H;
    private static final int CONTENT_H = H - TITLE_H - TAB_H - FOOT_H;
    private static final int ROW_H     = 14;

    private static final String[] TAB_LABELS = { "常规", "物品白名单", "GUI 黑名单" };

    // ── 状态 ──────────────────────────────────────────────────────────────────
    private final Screen parent;
    private int activeTab = 0;

    // 常规
    private SortConfig.SortMode pendingMode;
    private boolean              pendingSound;

    // 物品白名单
    private final List<String> pendingWhitelist    = new ArrayList<>();
    private int    whitelistScroll   = 0;
    private int    whitelistSelected = -1;
    private EditBox whitelistBox;

    // GUI 黑名单
    private final List<String> pendingGuiBlacklist = new ArrayList<>();
    private int    guiScroll         = 0;
    private int    guiSelected       = -1;
    private EditBox guiBox;

    // ── 构造 ──────────────────────────────────────────────────────────────────
    public ConfigScreen(Screen parent) {
        super(Component.literal("Inventory Sort  配置"));
        this.parent = parent;
    }

    private int px() { return (width  - W) / 2; }
    private int py() { return (height - H) / 2; }

    // ── 初始化 ────────────────────────────────────────────────────────────────
    @Override
    protected void init() {
        pendingMode = SortConfig.getSortMode();
        pendingSound = SortConfig.isSortSoundEnabled();
        pendingWhitelist.clear();
        pendingWhitelist.addAll(SortConfig.getWhitelist());
        pendingGuiBlacklist.clear();
        pendingGuiBlacklist.addAll(SortConfig.getGuiBlacklist());
        rebuildWidgets();
    }

    @Override
    public void rebuildWidgets() {
        clearWidgets();
        int px = px(), py = py();

        // ── Tab 按钮 ─────────────────────────────────────────────────────────
        int tabW = W / TAB_LABELS.length;
        for (int i = 0; i < TAB_LABELS.length; i++) {
            final int idx = i;
            addRenderableWidget(Button.builder(
                    Component.literal(TAB_LABELS[i]),
                    b -> switchTab(idx))
                    .bounds(px + i * tabW, py + TITLE_H, tabW - 1, TAB_H)
                    .build());
        }

        // ── 底部按钮 ──────────────────────────────────────────────────────────
        int footY = py + H - FOOT_H + 5;
        addRenderableWidget(Button.builder(Component.literal("保存"),
                b -> saveAndClose())
                .bounds(px + W / 2 - 84, footY, 80, 20).build());
        addRenderableWidget(Button.builder(Component.literal("取消"),
                b -> onClose())
                .bounds(px + W / 2 + 4, footY, 80, 20).build());

        // ── Tab 内容 ──────────────────────────────────────────────────────────
        switch (activeTab) {
            case 0 -> buildGeneralTab();
            case 1 -> buildListTab(true);
            case 2 -> buildListTab(false);
        }
    }

    private void switchTab(int idx) {
        activeTab       = idx;
        whitelistScroll = guiScroll = 0;
        whitelistSelected = guiSelected = -1;
        rebuildWidgets();
    }

    // ── Tab 0：常规 ───────────────────────────────────────────────────────────
    private void buildGeneralTab() {
        int px = px(), cy = py() + CONTENT_Y;
        int modeY  = cy + 22;
        int soundY = cy + 60;

        // 排序方式：< 名称 >
        addRenderableWidget(Button.builder(Component.literal("<"),
                b -> cycleSortMode(-1))
                .bounds(px + 8, modeY, 20, 18).build());
        addRenderableWidget(Button.builder(Component.literal(">"),
                b -> cycleSortMode(+1))
                .bounds(px + W - 28, modeY, 20, 18).build());

        // 音效
        addRenderableWidget(Button.builder(
                Component.literal(pendingSound ? "开启" : "关闭"),
                b -> {
                    pendingSound = !pendingSound;
                    b.setMessage(Component.literal(pendingSound ? "开启" : "关闭"));
                })
                .bounds(px + W - 70, soundY, 62, 18).build());
    }

    private void cycleSortMode(int dir) {
        SortConfig.SortMode[] v = SortConfig.SortMode.values();
        pendingMode = v[(pendingMode.ordinal() + dir + v.length) % v.length];
    }

    // ── Tab 1 / 2：列表 Tab ───────────────────────────────────────────────────
    private void buildListTab(boolean isWhitelist) {
        int px = px(), py = py();
        int inputY = py + H - FOOT_H - 24;

        EditBox box = new EditBox(font, px + 4, inputY, W - 100, 16,
                Component.literal(""));
        box.setMaxLength(300);
        if (isWhitelist) {
            box.setHint(Component.literal("minecraft:diamond"));
            whitelistBox = box;
        } else {
            box.setHint(Component.literal("ChestScreen"));
            guiBox = box;
        }
        addRenderableWidget(box);

        addRenderableWidget(Button.builder(Component.literal("添加"),
                b -> addEntry(isWhitelist))
                .bounds(px + W - 94, inputY, 40, 16).build());
        addRenderableWidget(Button.builder(Component.literal("移除"),
                b -> removeSelected(isWhitelist))
                .bounds(px + W - 52, inputY, 48, 16).build());

        // 快捷填充按钮
        String hintLabel = isWhitelist ? "填入手持物品" : "填入当前界面";
        addRenderableWidget(Button.builder(Component.literal(hintLabel),
                b -> pasteHint(isWhitelist))
                .bounds(px + 4, inputY - 20, 100, 16).build());
    }

    private void addEntry(boolean isWhitelist) {
        EditBox box = isWhitelist ? whitelistBox : guiBox;
        List<String> list = isWhitelist ? pendingWhitelist : pendingGuiBlacklist;
        if (box == null) return;
        String v = box.getValue().trim();
        if (!v.isEmpty() && !list.contains(v)) {
            list.add(v);
            box.setValue("");
            if (isWhitelist) whitelistSelected = -1; else guiSelected = -1;
        }
    }

    private void removeSelected(boolean isWhitelist) {
        List<String> list = isWhitelist ? pendingWhitelist : pendingGuiBlacklist;
        int sel = isWhitelist ? whitelistSelected : guiSelected;
        if (sel >= 0 && sel < list.size()) {
            list.remove(sel);
            int newSel = Math.min(sel, list.size() - 1);
            if (isWhitelist) whitelistSelected = newSel; else guiSelected = newSel;
        }
    }

    private void pasteHint(boolean isWhitelist) {
        if (isWhitelist) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || whitelistBox == null) return;
            ItemStack held = mc.player.getMainHandItem();
            if (held.isEmpty()) return;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(held.getItem());
            if (id != null) whitelistBox.setValue(id.toString());
        } else {
            if (parent == null || guiBox == null) return;
            String simple = parent.getClass().getSimpleName();
            guiBox.setValue(simple.isEmpty() ? parent.getClass().getName() : simple);
        }
    }

    // ── 保存 ─────────────────────────────────────────────────────────────────
    private void saveAndClose() {
        SortConfig.COMMON.sortMode.set(pendingMode);
        SortConfig.COMMON.sortSound.set(pendingSound);
        SortConfig.COMMON.whitelist.set(new ArrayList<>(pendingWhitelist));
        SortConfig.COMMON.guiBlacklist.set(new ArrayList<>(pendingGuiBlacklist));
        SortConfig.COMMON_SPEC.save();
        onClose();
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    // ── 渲染 ─────────────────────────────────────────────────────────────────
    @Override
    public void render(@NotNull GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g);
        int px = px(), py = py();

        // 面板背景 + 外框
        g.fill(px - 1, py - 1, px + W + 1, py + H + 1, 0xFF080808);
        g.fill(px,     py,     px + W,     py + H,     0xFF1B1B1B);

        // 标题栏
        g.fill(px, py, px + W, py + TITLE_H, 0xFF182438);
        g.fill(px, py, px + W, py + 1, 0xFF3C6AAA);
        g.drawCenteredString(font, "Inventory Sort  配置",
                px + W / 2, py + (TITLE_H - 8) / 2, 0xCCDDFF);

        // Tab 背景
        int tabW = W / TAB_LABELS.length;
        for (int i = 0; i < TAB_LABELS.length; i++) {
            int tx = px + i * tabW;
            int ty = py + TITLE_H;
            boolean active = (i == activeTab);
            g.fill(tx, ty, tx + tabW - 1, ty + TAB_H, active ? 0xFF232335 : 0xFF141420);
            if (active) g.fill(tx, ty, tx + tabW - 1, ty + 2, 0xFF4466BB);
        }

        // 内容区背景
        int cy = py + CONTENT_Y;
        g.fill(px, cy, px + W, cy + CONTENT_H, 0xFF181828);
        // 底部栏
        g.fill(px, py + H - FOOT_H, px + W, py + H - FOOT_H + 1, 0xFF252535);

        switch (activeTab) {
            case 0 -> renderGeneralTab(g, px, py, mx, my);
            case 1 -> renderListTabContent(g, px, py, mx, my, pendingWhitelist,
                    whitelistScroll, whitelistSelected, true);
            case 2 -> renderListTabContent(g, px, py, mx, my, pendingGuiBlacklist,
                    guiScroll, guiSelected, false);
        }

        super.render(g, mx, my, partial);
    }

    private void renderGeneralTab(GuiGraphics g, int px, int py, int mx, int my) {
        int cy = py + CONTENT_Y;

        // 排序方式区
        int modeSecY = cy + 8;
        g.fill(px + 2, modeSecY, px + W - 2, modeSecY + 40, 0xFF1E1E2E);
        g.fill(px + 2, modeSecY, px + W - 2, modeSecY + 1, 0xFF2E2E4E);
        g.drawString(font, "排序方式", px + 10, modeSecY + 4, 0x8899BB, false);
        g.drawCenteredString(font, pendingMode.getDisplayName(),
                px + W / 2, modeSecY + 22, 0xEEEEEE);

        // 子说明
        String desc = getSortModeDesc(pendingMode);
        g.drawCenteredString(font, desc, px + W / 2, modeSecY + 34, 0x556677);

        // 音效区
        int soundSecY = cy + 54;
        g.fill(px + 2, soundSecY, px + W - 2, soundSecY + 28, 0xFF1C1C2C);
        g.fill(px + 2, soundSecY, px + W - 2, soundSecY + 1, 0xFF2E2E4E);
        g.drawString(font, "整理音效", px + 10, soundSecY + 10, 0x8899BB, false);
        g.drawString(font, pendingSound ? "开启后整理时会播放 UI 点击声" : "已静音",
                px + 12, soundSecY + 20, 0x445566, false);
    }

    private void renderListTabContent(GuiGraphics g, int px, int py,
                                      int mx, int my,
                                      List<String> list, int scroll, int selected,
                                      boolean isWhitelist) {
        int cy     = py + CONTENT_Y;
        int listH  = CONTENT_H - 46;
        int maxVis = listH / ROW_H;
        int listY  = cy + 4;

        // 说明文字
        String header = isWhitelist
                ? "整理时跳过这些物品（点击条目选中，再点移除）"
                : "在这些界面中禁用整理（支持末段类名，如 ChestScreen）";
        g.drawString(font, header, px + 4, listY, 0x667788, false);
        listY += 12;

        // 列表背景
        g.fill(px + 2, listY, px + W - 2, listY + maxVis * ROW_H, 0xFF111118);

        for (int i = 0; i < maxVis; i++) {
            int idx = scroll + i;
            if (idx >= list.size()) break;
            int rowY = listY + i * ROW_H;
            boolean isSelected = (idx == selected);
            boolean hovered    = mx >= px + 2 && mx < px + W - 2
                    && my >= rowY && my < rowY + ROW_H;

            if (isSelected)     g.fill(px + 2, rowY, px + W - 2, rowY + ROW_H, 0xFF2A3A6A);
            else if (hovered)   g.fill(px + 2, rowY, px + W - 2, rowY + ROW_H, 0xFF1E1E32);
            else if (i % 2 == 1) g.fill(px + 2, rowY, px + W - 2, rowY + ROW_H, 0xFF141420);

            String entry = list.get(idx);

            if (isWhitelist) {
                // 白名单：尝试渲染物品图标
                try {
                    ResourceLocation loc = new ResourceLocation(entry);
                    net.minecraft.world.item.Item item = ForgeRegistries.ITEMS.getValue(loc);
                    if (item != null && item != net.minecraft.world.item.Items.AIR) {
                        RenderSystem.enableDepthTest();
                        g.renderItem(new ItemStack(item), px + 3, rowY);
                        RenderSystem.disableDepthTest();
                        g.drawString(font, clipText(entry, W - 30), px + 20, rowY + 3,
                                isSelected ? 0xCCDDFF : 0x9AABBC, false);
                    } else {
                        g.drawString(font, clipText(entry, W - 10), px + 5, rowY + 3,
                                isSelected ? 0xCCDDFF : 0x778899, false);
                    }
                } catch (Exception ex) {
                    g.drawString(font, clipText(entry, W - 10), px + 5, rowY + 3,
                            0x667788, false);
                }
            } else {
                // GUI黑名单：直接显示类名
                g.drawString(font, clipText(entry, W - 10), px + 5, rowY + 3,
                        isSelected ? 0xCCDDFF : 0x9AABBC, false);
            }
        }

        // 底部边线 + 计数
        g.fill(px + 2, listY + maxVis * ROW_H, px + W - 2, listY + maxVis * ROW_H + 1, 0xFF222230);
        g.drawString(font, "共 " + list.size() + " 项", px + 4,
                listY + maxVis * ROW_H + 3, 0x445566, false);

        // 滚动条
        if (list.size() > maxVis) {
            int totalH = maxVis * ROW_H;
            int barH   = Math.max(8, totalH * maxVis / list.size());
            int barTop = listY + (totalH - barH) * scroll / Math.max(1, list.size() - maxVis);
            g.fill(px + W - 5, listY, px + W - 2, listY + totalH, 0xFF1A1A28);
            g.fill(px + W - 5, barTop, px + W - 2, barTop + barH, 0xFF4455AA);
        }
    }

    private String clipText(String s, int maxW) {
        if (font.width(s) <= maxW) return s;
        return font.plainSubstrByWidth(s, maxW - font.width("..")) + "..";
    }

    // ── 鼠标交互 ─────────────────────────────────────────────────────────────
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            if (activeTab == 1) {
                int sel = pickListRow((int) mx, (int) my, pendingWhitelist, whitelistScroll);
                if (sel >= 0) { whitelistSelected = sel; return true; }
            } else if (activeTab == 2) {
                int sel = pickListRow((int) mx, (int) my, pendingGuiBlacklist, guiScroll);
                if (sel >= 0) { guiSelected = sel; return true; }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private int pickListRow(int mx, int my, List<String> list, int scroll) {
        int px = px(), py = py();
        int listY  = py + CONTENT_Y + 16;   // header 12 + gap 4
        int maxVis = (CONTENT_H - 46) / ROW_H;
        if (mx < px + 2 || mx > px + W - 2) return -1;
        for (int i = 0; i < maxVis; i++) {
            int rowY = listY + i * ROW_H;
            if (my >= rowY && my < rowY + ROW_H) {
                int idx = scroll + i;
                return (idx < list.size()) ? idx : -1;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int d = delta < 0 ? 1 : -1;
        if (activeTab == 1) {
            int max = Math.max(0, pendingWhitelist.size() - (CONTENT_H - 46) / ROW_H);
            whitelistScroll = Math.max(0, Math.min(whitelistScroll + d, max));
            return true;
        } else if (activeTab == 2) {
            int max = Math.max(0, pendingGuiBlacklist.size() - (CONTENT_H - 46) / ROW_H);
            guiScroll = Math.max(0, Math.min(guiScroll + d, max));
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ── 排序模式说明 ──────────────────────────────────────────────────────────
    private static String getSortModeDesc(SortConfig.SortMode mode) {
        return switch (mode) {
            case CATEGORY        -> "按分类 > 子分类 > 名称，横向填充";
            case CATEGORY_COLUMN -> "按分类 > 子分类 > 名称，纵向填充";
            case NAME            -> "按注册表 ID 字母顺序，横向填充";
            case NAME_COLUMN     -> "按注册表 ID 字母顺序，纵向填充";
            case COUNT           -> "按数量从多到少";
            case COUNT_ASC       -> "按数量从少到多";
            case CATEGORY_COUNT  -> "按分类优先，同类内数量从多到少";
        };
    }
}
