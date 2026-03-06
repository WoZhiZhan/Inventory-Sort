package com.wzz.inventory_sort.core;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.wzz.inventory_sort.network.SortPacket;
import com.wzz.inventory_sort.util.SophisticatedBackpacksHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import com.wzz.inventory_sort.config.SortConfig;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import static com.wzz.inventory_sort.InventorySortMod.NETWORK;
import static com.wzz.inventory_sort.InventorySortMod.sortKey;
import static com.wzz.inventory_sort.InventorySortMod.whitelistKey;
import static com.wzz.inventory_sort.InventorySortMod.guiBlacklistKey;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import static com.wzz.inventory_sort.core.CoreServer.*;

public class CoreHandler {
    public static final int SPACE_KEY = GLFW.GLFW_KEY_SPACE;
    public static final Logger LOGGER = LogUtils.getLogger();

    @OnlyIn(Dist.CLIENT)
    @SubscribeEvent
    public void onScreenMouseClick(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() == 0) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.screen == null) return;
            if (GLFW.glfwGetKey(mc.getWindow().getWindow(), SPACE_KEY) == GLFW.GLFW_PRESS) {
                if (mc.screen instanceof AbstractContainerScreen<?> containerScreen) {
                    if (!(containerScreen instanceof RecipeUpdateListener)) {
                        AbstractContainerMenu container = containerScreen.getMenu();
                        if (!(container instanceof FurnaceMenu)) {
                            Slot hoveredSlot = getSlotUnderMouse(containerScreen);
                            if (hoveredSlot != null && !hoveredSlot.getItem().isEmpty()) {
                                CompletableFuture.runAsync(() -> {
                                    try {
                                        performQuickTransfer(hoveredSlot, container, mc);
                                    } catch (Exception e) {
                                        LOGGER.error("一键转移时发生错误: ", e);
                                    }
                                });
                                event.setCanceled(true);
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean performQuickTransfer(Slot sourceSlot, AbstractContainerMenu container, Minecraft mc) {
        if (mc.gameMode == null || mc.player == null) return false;
        if (!mc.player.inventoryMenu.getCarried().isEmpty()) {
            return false;
        }
        ItemStack sourceItem = sourceSlot.getItem();
        if (sourceItem.isEmpty()) return false;

        boolean isContainerToInventory = isContainerSlot(sourceSlot, container);

        try {
            if (isContainerToInventory) {
                return transferAllFromContainer(container, mc);
            } else {
                boolean isHotbarSlot = isHotbarSlot(sourceSlot, container);
                return transferAllFromInventory(container, mc, isHotbarSlot);
            }
        } catch (Exception e) {
            LOGGER.error("转移所有物品时发生错误: ", e);
            return false;
        }
    }

    /**
     * 转移容器中的所有物品到背包
     */
    private boolean transferAllFromContainer(AbstractContainerMenu container, Minecraft mc) {
        List<Slot> containerSlots = getContainerSlots(container);
        return transferAllSlots(containerSlots, container, mc, "容器到背包");
    }

    /**
     * 转移背包中的物品到容器 - 支持选择性转移
     */
    private boolean transferAllFromInventory(AbstractContainerMenu container, Minecraft mc, boolean onlyHotbar) {
        List<Slot> inventorySlots = getPlayerInventorySlots(container, onlyHotbar);
        String direction = onlyHotbar ? "快捷栏到容器" : "主背包到容器";
        return transferAllSlots(inventorySlots, container, mc, direction);
    }

    /**
     * 转移指定槽位列表中的所有物品
     */
    private boolean transferAllSlots(List<Slot> slots, AbstractContainerMenu container, Minecraft mc, String direction) {
        if (slots.isEmpty()) {
            return false;
        }

        boolean anyTransferred = false;
        int transferredSlots = 0;
        int totalSlots = 0;
        for (Slot slot : slots) {
            if (!slot.getItem().isEmpty()) {
                totalSlots++;
            }
        }

        if (totalSlots == 0) {
            return false;
        }

        try {
            for (Slot slot : slots) {
                if (slot.getItem().isEmpty()) continue;
                if (!mc.player.inventoryMenu.getCarried().isEmpty()) {
                    LOGGER.warn("转移过程中鼠标不为空，停止转移");
                    break;
                }
                ItemStack originalItem = slot.getItem().copy();
                mc.gameMode.handleInventoryMouseClick(
                        container.containerId,
                        slot.index,
                        0,
                        net.minecraft.world.inventory.ClickType.QUICK_MOVE,
                        mc.player
                );
                ItemStack currentItem = slot.getItem();
                if (currentItem.getCount() < originalItem.getCount() || currentItem.isEmpty()) {
                    anyTransferred = true;
                    transferredSlots++;
                }
                if (transferredSlots % 10 == 0) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            return anyTransferred;
        } catch (Exception e) {
            LOGGER.error("转移所有物品时发生错误: ", e);
            return false;
        }
    }


    @SubscribeEvent
    public void onScreenKeyPress(ScreenEvent.KeyPressed.Pre event) {
        if (sortKey.matches(event.getKeyCode(), event.getScanCode())) {
            tryTriggerSort(event);
            return;
        }
        // 悬停物品上按键 → 切换物品白名单
        if (whitelistKey.matches(event.getKeyCode(), event.getScanCode())) {
            toggleHoveredItemWhitelist(event);
            return;
        }
        // 按键 → 切换当前 GUI 黑名单
        if (guiBlacklistKey.matches(event.getKeyCode(), event.getScanCode())) {
            toggleCurrentGuiBlacklist(event);
        }
    }

    @SubscribeEvent
    public void onMouseClick(InputEvent.MouseButton event) {
        if (event.getAction() != GLFW.GLFW_PRESS) return;
        if (sortKey.getKey().getType() == InputConstants.Type.MOUSE
                && sortKey.getKey().getValue() == event.getButton()) {
            tryTriggerSort(null);
        }
    }

    private void tryTriggerSort(@Nullable ScreenEvent.KeyPressed.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (shouldSkipSorting(mc)) {
            return;
        }
        if (trySortSophisticatedBackpack(event, mc)) {
            return;
        }
        if (mc.screen instanceof AbstractContainerScreen<?> containerScreen) {
            handleContainerSorting(containerScreen, event);
        }
    }

    private boolean shouldSkipSorting(Minecraft mc) {
        if (mc.player == null || mc.screen == null) return true;
        if (mc.screen instanceof CraftingScreen) return true;
        String className = mc.screen.getClass().getName();
        if (className.startsWith("com.refinedmods.refinedstorage.screen.grid")) return true;
        // 用户配置的 GUI 黑名单
        if (SortConfig.isGuiBlacklisted(className)) return true;
        return false;
    }

    private boolean trySortSophisticatedBackpack(ScreenEvent.KeyPressed.Pre event, Minecraft mc) {
        if (!SophisticatedBackpacksHandler.hasSophisticatedBackpacksMod()) {
            return false;
        }
        boolean sorted;
        if (event != null) {
            sorted = SophisticatedBackpacksHandler.sortBackpack(event.getScreen());
            if (sorted) {
                event.setCanceled(true);
            }
        } else sorted = SophisticatedBackpacksHandler.sortBackpack(mc.screen);
        return sorted;
    }

    private void handleContainerSorting(AbstractContainerScreen<?> containerScreen, ScreenEvent.KeyPressed.Pre event) {
        AbstractContainerMenu container = containerScreen.getMenu();
        boolean isPlayerInventory = isPlayerInventory(containerScreen, container);

        if (isPlayerInventory) {
            NETWORK.sendToServer(new SortPacket(false, -1, true));
            playSortSound();
        } else if (shouldSortContainer(container)) {
            NETWORK.sendToServer(new SortPacket(false, -1, false));
            playSortSound();
        }
    }

    /** 播放整理音效（UI 按钮点击声），可在 config 中关闭 */
    @OnlyIn(Dist.CLIENT)
    private void playSortSound() {
        if (!SortConfig.isSortSoundEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(
                SoundEvents.UI_BUTTON_CLICK.get(),
                0.4f,
                1.0f
            );
        }
    }

    /**
     * 把鼠标悬停的物品 ID 加入 / 移出 物品白名单
     */
    @OnlyIn(Dist.CLIENT)
    private void toggleHoveredItemWhitelist(ScreenEvent.KeyPressed.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return;

        Slot hovered = getSlotUnderMouse(screen);
        if (hovered == null || hovered.getItem().isEmpty()) return;

        ResourceLocation id = ForgeRegistries.ITEMS.getKey(hovered.getItem().getItem());
        if (id == null) return;
        String idStr = id.toString();

        List<String> wl = SortConfig.getWhitelist();
        if (wl.contains(idStr)) {
            SortConfig.removeFromWhitelist(idStr);
            showOverlayMessage(mc, "已从白名单移除: " + idStr, false);
        } else {
            SortConfig.addToWhitelist(idStr);
            showOverlayMessage(mc, "已加入白名单: " + idStr, true);
        }
        event.setCanceled(true);
    }

    /**
     * 把当前打开的界面类名加入 / 移出 GUI 黑名单
     */
    @OnlyIn(Dist.CLIENT)
    private void toggleCurrentGuiBlacklist(ScreenEvent.KeyPressed.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null) return;

        // 优先用简单类名，方便识别；如含匿名类则用全名
        String simple = mc.screen.getClass().getSimpleName();
        String key = (simple.isEmpty() || simple.contains("$"))
                ? mc.screen.getClass().getName()
                : simple;

        List<String> bl = SortConfig.getGuiBlacklist();
        if (bl.contains(key)) {
            SortConfig.removeFromGuiBlacklist(key);
            showOverlayMessage(mc, "GUI 黑名单已移除: " + key, false);
        } else {
            SortConfig.addToGuiBlacklist(key);
            showOverlayMessage(mc, "GUI 黑名单已添加: " + key, true);
        }
        event.setCanceled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private void showOverlayMessage(Minecraft mc, String msg, boolean added) {
        if (mc.player == null) return;
        String color = added ? "§a" : "§c";
        mc.player.displayClientMessage(
                Component.literal(color + "[整理] " + msg),
                true
        );
    }

    private boolean isPlayerInventory(AbstractContainerScreen<?> screen, AbstractContainerMenu menu) {
        return screen instanceof InventoryScreen || menu instanceof InventoryMenu;
    }

    private boolean shouldSortContainer(AbstractContainerMenu container) {
        final int MIN_CONTAINER_SIZE = 10;
        return getContainerSize(container) >= MIN_CONTAINER_SIZE;
    }

    private int getContainerSize(AbstractContainerMenu container) {
        int totalSlots = container.slots.size();
        if (container instanceof InventoryMenu) {
            return 0;
        }
        if (totalSlots > 36) {
            return totalSlots - 36;
        }
        return totalSlots;
    }

    private Slot getSlotUnderMouse(AbstractContainerScreen<?> screen) {
        try {
            java.lang.reflect.Method method = null;
            Class<?> clazz = AbstractContainerScreen.class;
            String[] methodNames = {"getSlotUnderMouse", "m_97894_", "getHoveredSlot"};
            for (String methodName : methodNames) {
                try {
                    method = clazz.getDeclaredMethod(methodName);
                    break;
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (method != null) {
                method.setAccessible(true);
                return (Slot) method.invoke(screen);
            }
            java.lang.reflect.Field[] fields = clazz.getDeclaredFields();
            for (java.lang.reflect.Field field : fields) {
                if (field.getType() == Slot.class) {
                    field.setAccessible(true);
                    Object value = field.get(screen);
                    if (value instanceof Slot) {
                        return (Slot) value;
                    }
                }
            }
        } catch (Exception e) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("获取鼠标下槽位失败: ", e);
            }
        }
        return null;
    }
}
