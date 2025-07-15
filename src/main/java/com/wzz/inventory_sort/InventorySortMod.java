package com.wzz.inventory_sort;

import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.item.*;
import net.minecraft.tags.ItemTags;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.*;

@Mod(InventorySortMod.MODID)
public class InventorySortMod {
    public static final String MODID = "inventory_sort";
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int MAX_CONTAINER_SIZE = 2000;

    private static final int BATCH_SIZE = 100;

    private static final int MAX_MERGE_ITERATIONS = 200;

    public static KeyMapping sortKey;

    private volatile boolean isSorting = false;

    private static final int HOTBAR_START = 0;      // 快捷栏开始槽位
    private static final int HOTBAR_END = 8;        // 快捷栏结束槽位
    private static final int INVENTORY_START = 9;   // 主背包开始槽位
    private static final int INVENTORY_END = 35;    // 主背包结束槽位
    private static final int ARMOR_START = 36;      // 护甲槽开始
    private static final int ARMOR_END = 39;        // 护甲槽结束
    private static final int OFFHAND_SLOT = 40;     // 副手槽
    private static final int SPACE_KEY = GLFW.GLFW_KEY_SPACE;
    private static final int BUTTON_WIDTH = 12;
    private static final int BUTTON_HEIGHT = 12;

    private int containerButtonX = -1, containerButtonY = -1;
    private int playerButtonX = -1, playerButtonY = -1;

    private enum ItemCategory {
        WEAPONS(1, "武器"),
        TOOLS(2, "工具"),
        ARMOR(3, "护甲"),
        BLOCKS(4, "方块"),
        FOOD(5, "食物"),
        POTIONS(6, "药水"),
        REDSTONE(7, "红石"),
        DECORATIONS(8, "装饰"),
        MATERIALS(9, "材料"),
        MISC(10, "杂项");

        private final int priority;
        private final String displayName;

        ItemCategory(int priority, String displayName) {
            this.priority = priority;
            this.displayName = displayName;
        }

        public int getPriority() {
            return priority;
        }
    }

    public InventorySortMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(this::onKeyRegister);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void onClientSetup(final FMLClientSetupEvent event) {
    }

    private void onKeyRegister(final RegisterKeyMappingsEvent event) {
        sortKey = new KeyMapping(
                "key.inventorysort.sort",
                GLFW.GLFW_KEY_R,
                "key.categories.inventorysort"
        );
        event.register(sortKey);
    }

    @SubscribeEvent
    public void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (event.getScreen() instanceof AbstractContainerScreen<?> containerScreen) {
            if (containerScreen instanceof CreativeModeInventoryScreen) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            AbstractContainerMenu container = containerScreen.getMenu();
            renderSortButtons(event.getGuiGraphics(), containerScreen, container);
        }
    }

    private void renderSortButtons(GuiGraphics guiGraphics, AbstractContainerScreen<?> containerScreen, AbstractContainerMenu container) {
        int leftPos = containerScreen.getGuiLeft();
        int topPos = containerScreen.getGuiTop();
        int imageWidth = containerScreen.getXSize();
        int imageHeight = containerScreen.getYSize();
        Minecraft mc = Minecraft.getInstance();
        double mouseX = mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
        double mouseY = mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight();
        if (!(container instanceof InventoryMenu)) {
            containerButtonX = leftPos + imageWidth - BUTTON_WIDTH - 2;
            containerButtonY = topPos + 4;
            boolean isHovered = mouseX >= containerButtonX && mouseX < containerButtonX + BUTTON_WIDTH &&
                    mouseY >= containerButtonY && mouseY < containerButtonY + BUTTON_HEIGHT;
            renderSortButton(guiGraphics, containerButtonX, containerButtonY, "📦", 0x4A90E2, isHovered);
            if (isHovered) {
                guiGraphics.renderTooltip(mc.font, Component.literal("整理容器"), (int)mouseX, (int)mouseY);
            }
            return;
        } else {
            containerButtonX = -1;
        }
        playerButtonX = leftPos + imageWidth - BUTTON_WIDTH - 2;
        playerButtonY = topPos + imageHeight - 97;
        boolean isPlayerHovered = mouseX >= playerButtonX && mouseX < playerButtonX + BUTTON_WIDTH &&
                mouseY >= playerButtonY && mouseY < playerButtonY + BUTTON_HEIGHT;
        renderSortButton(guiGraphics, playerButtonX, playerButtonY, "=", 0x50C878, isPlayerHovered);
        if (isPlayerHovered) {
            guiGraphics.renderTooltip(mc.font, Component.literal("整理背包"), (int)mouseX, (int)mouseY);
        }
    }

    /**
     * 渲染单个整理按钮
     */
    private void renderSortButton(GuiGraphics guiGraphics, int x, int y, String icon, int color, boolean isHovered) {
        int alpha = isHovered ? 0xBB : 0x88;
        int bgColor = (alpha << 24);
        int buttonColor = (alpha << 24) | (color & 0xFFFFFF);
        guiGraphics.fill(x, y, x + BUTTON_WIDTH, y + BUTTON_HEIGHT, bgColor);
        guiGraphics.fill(x + 1, y + 1, x + BUTTON_WIDTH - 1, y + BUTTON_HEIGHT - 1, buttonColor);
        int borderColor = isHovered ? 0xFFFFFFFF : 0xFFAAAAAA;
        guiGraphics.fill(x, y, x + BUTTON_WIDTH, y + 1, borderColor); // 上
        guiGraphics.fill(x, y, x + 1, y + BUTTON_HEIGHT, borderColor); // 左
        guiGraphics.fill(x + BUTTON_WIDTH - 1, y, x + BUTTON_WIDTH, y + BUTTON_HEIGHT, 0xFF555555); // 右
        guiGraphics.fill(x, y + BUTTON_HEIGHT - 1, x + BUTTON_WIDTH, y + BUTTON_HEIGHT, 0xFF555555); // 下
        Minecraft mc = Minecraft.getInstance();
        int textWidth = mc.font.width(icon);
        int textX = x + (BUTTON_WIDTH - textWidth) / 2;
        int textY = y + (BUTTON_HEIGHT - mc.font.lineHeight) / 2;
        guiGraphics.drawString(mc.font, icon, textX, textY, 0xFFFFFFFF);
        if (isHovered) {
            guiGraphics.fill(x + 1, y + 1, x + BUTTON_WIDTH - 1, y + BUTTON_HEIGHT - 1, 0x40FFFFFF);
        }
    }

    @SubscribeEvent
    public void onScreenMouseClick(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() == 0) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.screen == null || isSorting) return;
            if (checkSortButtonClick(event.getMouseX(), event.getMouseY())) {
                mc.getSoundManager().play(new SimpleSoundInstance(SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.VOICE, 0.5f, 1f,
                        RandomSource.create(), mc.player.blockPosition()));
                event.setCanceled(true);
                return;
            }
            if (GLFW.glfwGetKey(mc.getWindow().getWindow(), SPACE_KEY) == GLFW.GLFW_PRESS) {
                if (mc.screen instanceof AbstractContainerScreen<?> containerScreen) {
                    AbstractContainerMenu container = containerScreen.getMenu();
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

    /**
     * 检查是否点击了整理按钮
     */
    private boolean checkSortButtonClick(double mouseX, double mouseY) {
        if (containerButtonX != -1 && containerButtonY != -1) {
            if (mouseX >= containerButtonX && mouseX < containerButtonX + BUTTON_WIDTH &&
                    mouseY >= containerButtonY && mouseY < containerButtonY + BUTTON_HEIGHT) {
                sortContainerAsync();
                return true;
            }
        }
        if (playerButtonX != -1 && playerButtonY != -1) {
            if (mouseX >= playerButtonX && mouseX < playerButtonX + BUTTON_WIDTH &&
                    mouseY >= playerButtonY && mouseY < playerButtonY + BUTTON_HEIGHT) {
                sortPlayerInventoryAsync();
                return true;
            }
        }
        return false;
    }

    /**
     * 屏幕关闭时重置按钮位置
     */
    @SubscribeEvent
    public void onScreenClose(ScreenEvent.Closing event) {
        containerButtonX = -1;
        containerButtonY = -1;
        playerButtonX = -1;
        playerButtonY = -1;
    }

    /**
     * 智能转移方法 - 根据点击的槽位类型决定转移范围
     */
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
     * 判断槽位是否是快捷栏
     */
    private boolean isHotbarSlot(Slot slot, AbstractContainerMenu container) {
        if (container instanceof InventoryMenu) {
            return slot.index >= HOTBAR_START && slot.index <= HOTBAR_END;
        } else {
            int totalSlots = container.slots.size();
            int playerSlotsStart = totalSlots - 36;
            int hotbarStart = playerSlotsStart + 27;
            int hotbarEnd = playerSlotsStart + 35;
            return slot.index >= hotbarStart && slot.index <= hotbarEnd;
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
     * 获取玩家背包槽位 - 支持选择性包含快捷栏
     */
    private List<Slot> getPlayerInventorySlots(AbstractContainerMenu container, boolean onlyHotbar) {
        List<Slot> slots = new ArrayList<>();
        if (container instanceof InventoryMenu) {
            if (onlyHotbar) {
                for (int i = HOTBAR_START; i <= HOTBAR_END; i++) {
                    slots.add(container.getSlot(i));
                }
            } else {
                for (int i = INVENTORY_START; i <= INVENTORY_END; i++) {
                    slots.add(container.getSlot(i));
                }
            }
        } else {
            int totalSlots = container.slots.size();
            int playerSlotsStart = totalSlots - 36;
            if (onlyHotbar) {
                for (int i = playerSlotsStart + 27; i < playerSlotsStart + 36; i++) {
                    if (i >= 0 && i < totalSlots) {
                        slots.add(container.getSlot(i));
                    }
                }
            } else {
                for (int i = playerSlotsStart; i < playerSlotsStart + 27; i++) {
                    if (i >= 0 && i < totalSlots) {
                        slots.add(container.getSlot(i));
                    }
                }
            }
        }

        return slots;
    }

    /**
     * 重载方法保持向后兼容
     */
    private List<Slot> getPlayerInventorySlots(AbstractContainerMenu container) {
        return getPlayerInventorySlots(container, false);
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

    /**
     * 获取容器槽位（不包括玩家背包）
     */
    private List<Slot> getContainerSlots(AbstractContainerMenu container) {
        List<Slot> slots = new ArrayList<>();
        if (container instanceof InventoryMenu) {
            return slots;
        }
        int totalSlots = container.slots.size();
        int containerSlotsEnd = totalSlots - 36;
        for (int i = 0; i < containerSlotsEnd; i++) {
            if (i >= 0 && i < totalSlots) {
                slots.add(container.getSlot(i));
            }
        }
        return slots;
    }

    /**
     * 改进的判断是否为容器槽位的方法
     */
    private boolean isContainerSlot(Slot slot, AbstractContainerMenu container) {
        if (container instanceof InventoryMenu) {
            return false;
        }
        int totalSlots = container.slots.size();
        int playerSlotsStart = totalSlots - 36;
        return slot.index < playerSlotsStart;
    }

    /**
     * 获取鼠标下的槽位
     */
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

    @SubscribeEvent
    public void onScreenKeyPress(ScreenEvent.KeyPressed.Pre event) {
        if (sortKey.matches(event.getKeyCode(), event.getScanCode())) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || isSorting || mc.screen instanceof CreativeModeInventoryScreen || mc.screen == null) return;
            if (mc.screen instanceof AbstractContainerScreen<?> containerScreen) {
                AbstractContainerMenu container = containerScreen.getMenu();
                if (mc.screen instanceof InventoryScreen || container instanceof InventoryMenu) {
                    sortPlayerInventoryAsync();
                } else {
                    sortContainerAsync();
                }
                event.setCanceled(true);
            }
        }
    }

    private void sortContainerAsync() {
        if (isSorting) return;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            try {
                sortContainer();
            } catch (Exception e) {
                LOGGER.error("排序容器时发生错误: ", e);
            }
        });
    }

    private void sortPlayerInventoryAsync() {
        if (isSorting) return;

        CompletableFuture.runAsync(() -> {
            try {
                sortPlayerInventory();
            } catch (Exception e) {
                LOGGER.error("异步排序玩家背包时发生错误: ", e);
            }
        });
    }

    private void sortContainer() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || isSorting) return;
        isSorting = true;
        try {
            AbstractContainerMenu container = player.containerMenu;
            List<Slot> containerSlots = new ArrayList<>();
            int totalSlots = container.slots.size();
            int containerSlotsCount = totalSlots - 36;
            for (int i = 0; i < containerSlotsCount; i++) {
                if (i < totalSlots) {
                    containerSlots.add(container.getSlot(i));
                }
            }
            if (!containerSlots.isEmpty()) {
                sortSlots(containerSlots, container);
            }
        } finally {
            isSorting = false;
        }
    }

    private boolean isPlayerSlot(Slot slot, AbstractContainerMenu container) {
        return !isContainerSlot(slot, container);
    }

    private void sortPlayerInventory() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || isSorting) return;
        isSorting = true;
        try {
            AbstractContainerMenu container = player.containerMenu;
            List<Slot> inventorySlots = new ArrayList<>();
            if (container instanceof InventoryMenu) {
                for (int i = INVENTORY_START; i <= INVENTORY_END; i++) {
                    Slot slot = container.getSlot(i);
                    inventorySlots.add(slot);
                }
            } else {
                int totalSlots = container.slots.size();
                int playerInventoryStart = totalSlots - 36 + INVENTORY_START;
                int playerInventoryEnd = totalSlots - 9;
                for (int i = playerInventoryStart; i < playerInventoryEnd; i++) {
                    if (i >= 0 && i < totalSlots) {
                        Slot slot = container.getSlot(i);
                        inventorySlots.add(slot);
                    }
                }
            }
            if (!inventorySlots.isEmpty()) {
                sortSlots(inventorySlots, container);
            }
        } finally {
            isSorting = false;
        }
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

    private void sortSlots(List<Slot> slots, AbstractContainerMenu container) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return;

        try {
            if (!ensureMouseEmpty(container, mc)) {
                return;
            }
            mergeIdenticalItemsBatched(slots, container, mc);
            performSortBatched(slots, container, mc);
            if (!ensureMouseEmpty(container, mc)) {
                LOGGER.error("排序完成后无法清空鼠标，可能存在物品丢失");
            }

        } catch (Exception e) {
            LOGGER.error("整理物品时发生错误: ", e);
            try {
                ensureMouseEmpty(container, mc);
            } catch (Exception ex) {
                LOGGER.error("错误恢复时也发生异常: ", ex);
            }
        }
    }

    private boolean ensureMouseEmpty(AbstractContainerMenu container, Minecraft mc) {
        if (mc.gameMode == null || mc.player == null) return true;

        ItemStack carriedItem = mc.player.inventoryMenu.getCarried();
        if (carriedItem.isEmpty()) {
            return true;
        }
        int attempts = 0;
        final int MAX_ATTEMPTS = 3;
        while (!carriedItem.isEmpty() && attempts < MAX_ATTEMPTS) {
            attempts++;
            boolean placed = false;
            for (Slot slot : container.slots) {
                if (slot.getItem().isEmpty() && slot.mayPlace(carriedItem)) {
                    mc.gameMode.handleInventoryMouseClick(
                            container.containerId,
                            slot.index,
                            0,
                            net.minecraft.world.inventory.ClickType.PICKUP,
                            mc.player
                    );
                    carriedItem = mc.player.inventoryMenu.getCarried();
                    if (carriedItem.isEmpty()) {
                        placed = true;
                        break;
                    }
                }
            }
            if (placed) break;
            for (Slot slot : container.slots) {
                ItemStack slotItem = slot.getItem();
                if (!slotItem.isEmpty() &&
                        ItemStack.isSameItemSameTags(carriedItem, slotItem) &&
                        slotItem.getCount() < slotItem.getMaxStackSize() &&
                        slot.mayPlace(carriedItem)) {

                    mc.gameMode.handleInventoryMouseClick(
                            container.containerId,
                            slot.index,
                            0,
                            net.minecraft.world.inventory.ClickType.PICKUP,
                            mc.player
                    );
                    carriedItem = mc.player.inventoryMenu.getCarried();
                    if (carriedItem.isEmpty()) {
                        break;
                    }
                }
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return carriedItem.isEmpty();
    }

    private void mergeIdenticalItemsBatched(List<Slot> slots, AbstractContainerMenu container, Minecraft mc) {
        int iterations = 0;
        boolean merged;

        do {
            merged = false;
            iterations++;
            if (iterations > MAX_MERGE_ITERATIONS) {
                LOGGER.warn("合并操作达到最大迭代次数，停止合并");
                break;
            }
            if (!mc.player.inventoryMenu.getCarried().isEmpty()) {
                LOGGER.warn("检测到鼠标不为空，停止合并操作");
                break;
            }
            for (int batchStart = 0; batchStart < slots.size() && !merged; batchStart += BATCH_SIZE) {
                int batchEnd = Math.min(batchStart + BATCH_SIZE, slots.size());

                for (int i = batchStart; i < batchEnd && !merged; i++) {
                    Slot slot1 = slots.get(i);
                    ItemStack stack1 = slot1.getItem();

                    if (stack1.isEmpty() || stack1.getCount() >= stack1.getMaxStackSize()) {
                        continue;
                    }

                    for (int j = i + 1; j < slots.size(); j++) {
                        Slot slot2 = slots.get(j);
                        ItemStack stack2 = slot2.getItem();

                        if (stack2.isEmpty()) {
                            continue;
                        }

                        if (ItemStack.isSameItemSameTags(stack1, stack2)) {
                            int spaceAvailable = stack1.getMaxStackSize() - stack1.getCount();
                            if (spaceAvailable > 0) {
                                if (!mc.player.inventoryMenu.getCarried().isEmpty()) {
                                    return;
                                }
                                if (mergeStacks(slot1, slot2, container, mc)) {
                                    merged = true;
                                    break;
                                }
                            }
                        }
                    }
                }
                if (merged) {
                    try {
                        Thread.sleep(3);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        } while (merged);
    }

    private boolean mergeStacks(Slot targetSlot, Slot sourceSlot, AbstractContainerMenu container, Minecraft mc) {
        if (mc.gameMode == null || mc.player == null) return false;

        ItemStack target = targetSlot.getItem();
        ItemStack source = sourceSlot.getItem();
        if (!ItemStack.isSameItemSameTags(target, source)) {
            return false;
        }
        int spaceAvailable = target.getMaxStackSize() - target.getCount();
        if (spaceAvailable <= 0) {
            return false;
        }
        boolean targetIsContainer = isContainerSlot(targetSlot, container);
        boolean sourceIsContainer = isContainerSlot(sourceSlot, container);
        if (targetIsContainer != sourceIsContainer) {
            return false;
        }
        int originalTargetCount = target.getCount();
        int originalSourceCount = source.getCount();
        try {
            mc.gameMode.handleInventoryMouseClick(
                    container.containerId,
                    sourceSlot.index,
                    0,
                    net.minecraft.world.inventory.ClickType.PICKUP,
                    mc.player
            );
            mc.gameMode.handleInventoryMouseClick(
                    container.containerId,
                    targetSlot.index,
                    0, // 左键
                    net.minecraft.world.inventory.ClickType.PICKUP,
                    mc.player
            );
            mc.gameMode.handleInventoryMouseClick(
                    container.containerId,
                    sourceSlot.index,
                    0,
                    net.minecraft.world.inventory.ClickType.PICKUP,
                    mc.player
            );
            return targetSlot.getItem().getCount() > originalTargetCount;
        } catch (Exception e) {
            LOGGER.error("合并物品时发生错误: ", e);
            return false;
        }
    }

    private void performSortBatched(List<Slot> slots, AbstractContainerMenu container, Minecraft mc) {
        if (mc.player == null) return;
        if (!mc.player.inventoryMenu.getCarried().isEmpty()) {
            return;
        }
        List<SlotInfo> slotInfos = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            ItemStack stack = slot.getItem();
            slotInfos.add(new SlotInfo(i, slot, stack.copy()));
        }
        slotInfos.sort((a, b) -> {
            if (a.stack.isEmpty() && b.stack.isEmpty()) return 0;
            if (a.stack.isEmpty()) return 1;
            if (b.stack.isEmpty()) return -1;

            String keyA = getItemKey(a.stack);
            String keyB = getItemKey(b.stack);
            return keyA.compareTo(keyB);
        });

        int swapCount = 0;
        for (int targetIndex = 0; targetIndex < slotInfos.size(); targetIndex++) {
            if (!mc.player.inventoryMenu.getCarried().isEmpty()) {
                break;
            }
            SlotInfo targetInfo = slotInfos.get(targetIndex);
            if (targetInfo.originalIndex == targetIndex) {
                continue;
            }
            SlotInfo currentAtTarget = null;
            for (int i = targetIndex + 1; i < slotInfos.size(); i++) {
                if (slotInfos.get(i).originalIndex == targetIndex) {
                    currentAtTarget = slotInfos.get(i);
                    break;
                }
            }
            if (currentAtTarget != null) {
                if (swapSlots(slots.get(targetIndex), slots.get(targetInfo.originalIndex), container, mc)) {
                    int tempIndex = targetInfo.originalIndex;
                    targetInfo.originalIndex = targetIndex;
                    currentAtTarget.originalIndex = tempIndex;

                    swapCount++;
                    if (swapCount % 20 == 0) {
                        try {
                            Thread.sleep(3);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }
        }
    }

    private boolean swapSlots(Slot slot1, Slot slot2, AbstractContainerMenu container, Minecraft mc) {
        if (slot1.getItem().isEmpty() && slot2.getItem().isEmpty()) {
            return true;
        }

        if (mc.gameMode == null || mc.player == null) return false;
        if (!mc.player.inventoryMenu.getCarried().isEmpty()) {
            return false;
        }

        mc.gameMode.handleInventoryMouseClick(
                container.containerId,
                slot1.index,
                0,
                net.minecraft.world.inventory.ClickType.PICKUP,
                mc.player
        );

        mc.gameMode.handleInventoryMouseClick(
                container.containerId,
                slot2.index,
                0,
                net.minecraft.world.inventory.ClickType.PICKUP,
                mc.player
        );

        mc.gameMode.handleInventoryMouseClick(
                container.containerId,
                slot1.index,
                0,
                net.minecraft.world.inventory.ClickType.PICKUP,
                mc.player
        );

        if (!mc.player.inventoryMenu.getCarried().isEmpty()) {
            LOGGER.error("交换操作后鼠标仍不为空，可能存在问题");
            return false;
        }
        return true;
    }

    private String getItemKey(ItemStack stack) {
        if (stack.isEmpty()) {
            return "zzz_empty";
        }
        ItemCategory category = getItemCategory(stack);
        String itemName = stack.getItem().toString();
        String nbtHash = stack.getTag() != null ?
                String.valueOf(stack.getTag().toString().hashCode()) : "0";
        return String.format("%02d_%s_%s_%s",
                category.getPriority(),
                category.name(),
                itemName,
                nbtHash);
    }

    private ItemCategory getItemCategory(ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemCategory.MISC;
        }
        Item item = stack.getItem();
        if (isWeapon(item, stack)) {
            return ItemCategory.WEAPONS;
        }
        if (isTool(item, stack)) {
            return ItemCategory.TOOLS;
        }
        if (isArmor(item, stack)) {
            return ItemCategory.ARMOR;
        }
        if (isFood(item, stack)) {
            return ItemCategory.FOOD;
        }
        if (isPotion(item, stack)) {
            return ItemCategory.POTIONS;
        }
        if (isRedstone(item, stack)) {
            return ItemCategory.REDSTONE;
        }
        if (isDecoration(item, stack)) {
            return ItemCategory.DECORATIONS;
        }
        if (isBlock(item, stack)) {
            return ItemCategory.BLOCKS;
        }
        if (isMaterial(item, stack)) {
            return ItemCategory.MATERIALS;
        }
        return ItemCategory.MISC;
    }

    private boolean isWeapon(Item item, ItemStack stack) {
        if (item instanceof SwordItem || item instanceof TridentItem ||
                item instanceof BowItem || item instanceof CrossbowItem) {
            return true;
        }
        if (stack.is(ItemTags.SWORDS) ||
                isInTag(stack, "weapons") || isInTag(stack, "swords") ||
                isInTag(stack, "bows") || isInTag(stack, "crossbows")) {
            return true;
        }
        if (getAttackDamage(item) > 2.0f && !isTool(item, stack)) {
            return true;
        }
        String itemName = getItemName(item).toLowerCase();
        return itemName.contains("sword") || itemName.contains("blade") ||
                itemName.contains("katana") || itemName.contains("dagger") ||
                itemName.contains("bow") || itemName.contains("crossbow") ||
                itemName.contains("gun") || itemName.contains("rifle") ||
                itemName.contains("pistol") || itemName.contains("weapon");
    }

    private boolean isTool(Item item, ItemStack stack) {
        if (item instanceof DiggerItem || item instanceof ShearsItem ||
                item instanceof FlintAndSteelItem || item instanceof FishingRodItem ||
                item instanceof CompassItem ||
                item instanceof SpyglassItem || item instanceof BrushItem) {
            return true;
        }
        if (stack.is(ItemTags.TOOLS) || stack.is(ItemTags.PICKAXES) ||
                stack.is(ItemTags.AXES) || stack.is(ItemTags.SHOVELS) ||
                stack.is(ItemTags.HOES) || isInTag(stack, "tools") ||
                isInTag(stack, "pickaxes") || isInTag(stack, "axes") ||
                isInTag(stack, "shovels") || isInTag(stack, "hoes")) {
            return true;
        }
        if (stack.isDamageableItem() && item instanceof DiggerItem) {
            return true;
        }
        String itemName = getItemName(item).toLowerCase();
        return itemName.contains("pickaxe") || itemName.contains("axe") ||
                itemName.contains("shovel") || itemName.contains("hoe") ||
                itemName.contains("wrench") || itemName.contains("hammer") ||
                itemName.contains("drill") || itemName.contains("chainsaw") ||
                itemName.contains("tool");
    }

    private boolean isArmor(Item item, ItemStack stack) {
        if (item instanceof ArmorItem || item instanceof ElytraItem ||
                item instanceof ShieldItem) {
            return true;
        }
        if (isInTag(stack, "armor") || isInTag(stack, "helmets") ||
                isInTag(stack, "chestplates") || isInTag(stack, "leggings") ||
                isInTag(stack, "boots") || isInTag(stack, "shields")) {
            return true;
        }
        if (stack.isDamageableItem() && hasArmorValue(item)) {
            return true;
        }
        String itemName = getItemName(item).toLowerCase();
        return itemName.contains("helmet") || itemName.contains("chestplate") ||
                itemName.contains("leggings") || itemName.contains("boots") ||
                itemName.contains("armor") || itemName.contains("shield") ||
                itemName.contains("elytra");
    }

    private boolean isFood(Item item, ItemStack stack) {
        if (item.getFoodProperties(stack, null) != null) {
            return true;
        }
        if (item instanceof MilkBucketItem || item instanceof HoneyBottleItem ||
                item instanceof SuspiciousStewItem) {
            return true;
        }
        if (isInTag(stack, "food") || isInTag(stack, "foods")) {
            return true;
        }
        String itemName = getItemName(item).toLowerCase();
        return itemName.contains("food") || itemName.contains("bread") ||
                itemName.contains("meat") || itemName.contains("fish") ||
                itemName.contains("fruit") || itemName.contains("vegetable") ||
                itemName.contains("cake") || itemName.contains("pie") ||
                itemName.contains("soup") || itemName.contains("stew");
    }

    private boolean isPotion(Item item, ItemStack stack) {
        if (item instanceof PotionItem || item instanceof SplashPotionItem ||
                item instanceof LingeringPotionItem || item instanceof TippedArrowItem ||
                item == Items.GLASS_BOTTLE || item == Items.EXPERIENCE_BOTTLE) {
            return true;
        }
        if (isInTag(stack, "potions") || isInTag(stack, "bottles")) {
            return true;
        }
        String itemName = getItemName(item).toLowerCase();
        return itemName.contains("potion") || itemName.contains("bottle") ||
                itemName.contains("elixir") || itemName.contains("tonic") ||
                itemName.contains("brew") || itemName.contains("essence");
    }

    private boolean isRedstone(Item item, ItemStack stack) {
        if (isInTag(stack, "redstone") || isInTag(stack, "redstone_dusts")) {
            return true;
        }
        if (item instanceof BlockItem) {
            Block block = ((BlockItem) item).getBlock();
            if (block instanceof RedStoneWireBlock || block instanceof RepeaterBlock ||
                    block instanceof ComparatorBlock || block instanceof PistonBaseBlock ||
                    block instanceof DispenserBlock || block instanceof DropperBlock ||
                    block instanceof HopperBlock || block instanceof ObserverBlock ||
                    block instanceof DaylightDetectorBlock || block instanceof TripWireHookBlock ||
                    block instanceof LeverBlock || block instanceof ButtonBlock ||
                    block instanceof PressurePlateBlock || block instanceof PoweredRailBlock ||
                    block instanceof DetectorRailBlock || block instanceof RailBlock ||
                    block instanceof RedstoneLampBlock || block instanceof TargetBlock) {
                return true;
            }
        }
        String itemName = getItemName(item).toLowerCase();
        return itemName.contains("redstone") || itemName.contains("repeater") ||
                itemName.contains("comparator") || itemName.contains("piston") ||
                itemName.contains("dispenser") || itemName.contains("dropper") ||
                itemName.contains("hopper") || itemName.contains("observer") ||
                itemName.contains("lever") || itemName.contains("button") ||
                itemName.contains("circuit") || itemName.contains("wire");
    }

    private boolean isDecoration(Item item, ItemStack stack) {
        if (isInTag(stack, "decorations") || isInTag(stack, "flowers") ||
                isInTag(stack, "banners") || isInTag(stack, "candles")) {
            return true;
        }
        if (item == Items.PAINTING || item == Items.ITEM_FRAME ||
                item == Items.GLOW_ITEM_FRAME || item == Items.ARMOR_STAND) {
            return true;
        }
        if (item instanceof BlockItem) {
            Block block = ((BlockItem) item).getBlock();
            if (block instanceof FlowerBlock || block instanceof BannerBlock ||
                    block instanceof CandleBlock || block instanceof CarpetBlock ||
                    block instanceof GlassBlock || block instanceof StainedGlassBlock) {
                return true;
            }
        }
        String itemName = getItemName(item).toLowerCase();
        return itemName.contains("decoration") || itemName.contains("flower") ||
                itemName.contains("banner") || itemName.contains("candle") ||
                itemName.contains("painting") || itemName.contains("frame") ||
                itemName.contains("carpet") || itemName.contains("glass");
    }

    private boolean isBlock(Item item, ItemStack stack) {
        if (!(item instanceof BlockItem)) {
            return false;
        }
        if (isInTag(stack, "blocks") || isInTag(stack, "stone") ||
                isInTag(stack, "wood") || isInTag(stack, "planks") ||
                isInTag(stack, "logs") || isInTag(stack, "ores")) {
            return true;
        }
        return !isRedstone(item, stack) && !isDecoration(item, stack);
    }

    private boolean isMaterial(Item item, ItemStack stack) {
        if (isInTag(stack, "ingots") || isInTag(stack, "gems") ||
                isInTag(stack, "dusts") || isInTag(stack, "nuggets") ||
                isInTag(stack, "ores") || isInTag(stack, "raw_materials") ||
                isInTag(stack, "dyes") || isInTag(stack, "seeds")) {
            return true;
        }
        if (!stack.isDamageableItem() && !isFood(item, stack) &&
                !isPotion(item, stack) && !(item instanceof BlockItem)) {
            return true;
        }
        String itemName = getItemName(item).toLowerCase();
        return itemName.contains("ingot") || itemName.contains("gem") ||
                itemName.contains("dust") || itemName.contains("nugget") ||
                itemName.contains("crystal") || itemName.contains("shard") ||
                itemName.contains("essence") || itemName.contains("powder") ||
                itemName.contains("seed") || itemName.contains("dye") ||
                itemName.contains("material") || itemName.contains("component");
    }

    private float getAttackDamage(Item item) {
        if (item instanceof SwordItem) {
            return ((SwordItem) item).getDamage();
        } else if (item instanceof AxeItem) {
            return ((AxeItem) item).getAttackDamage();
        } else if (item instanceof DiggerItem) {
            return ((DiggerItem) item).getAttackDamage();
        }
        return 0.0f;
    }

    private boolean hasArmorValue(Item item) {
        return item instanceof ArmorItem;
    }

    private String getItemName(Item item) {
        ResourceLocation registryName = BuiltInRegistries.ITEM.getKey(item);
        return registryName != null ? registryName.getPath() : item.toString();
    }

    private boolean isInTag(ItemStack stack, String tagName) {
        try {
            String[] namespaces = {"minecraft", "forge", "c", "common"};
            for (String namespace : namespaces) {
                ResourceLocation tagLocation = new ResourceLocation(namespace, tagName);
                if (BuiltInRegistries.ITEM.getTag(
                        net.minecraft.tags.TagKey.create(
                                BuiltInRegistries.ITEM.key(),
                                tagLocation
                        )
                ).isPresent()) {
                    return stack.is(net.minecraft.tags.TagKey.create(
                            BuiltInRegistries.ITEM.key(),
                            tagLocation
                    ));
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }


    private static class SlotInfo {
        int originalIndex;
        Slot slot;
        ItemStack stack;

        SlotInfo(int originalIndex, Slot slot, ItemStack stack) {
            this.originalIndex = originalIndex;
            this.slot = slot;
            this.stack = stack;
        }
    }
}