package com.wzz.inventory_sort.core;

import com.mojang.logging.LogUtils;
import com.wzz.inventory_sort.InventorySortMod;
import com.wzz.inventory_sort.config.SortConfig;
import net.minecraftforge.registries.ForgeRegistries;
import com.wzz.inventory_sort.category.ItemCategory;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class CoreServer {
    public static final int HOTBAR_START = 0;      // 快捷栏开始槽位
    public static final int HOTBAR_END = 8;        // 快捷栏结束槽位
    public static final int INVENTORY_START = 9;   // 主背包开始槽位
    public static final int INVENTORY_END = 35;    // 主背包结束槽位
    public static final int BATCH_SIZE = 100;
    public static final int MAX_MERGE_ITERATIONS = 250;
    public static final int ARMOR_START = 36;      // 护甲槽开始
    public static final int ARMOR_END = 39;        // 护甲槽结束
    public static final int OFFHAND_SLOT = 40;     // 副手槽
    private static final Logger LOGGER = LogUtils.getLogger();
    public static void handleServerRequest(ServerPlayer player, boolean isQuickTransfer, int slotId, boolean isPlayerInventory) {
        AbstractContainerMenu container = player.containerMenu;

        try {
            if (isQuickTransfer) {
                handleServerQuickTransfer(player, container, slotId);
            } else {
                handleServerSort(player, container, isPlayerInventory);
            }
        } catch (Exception e) {
            LOGGER.error("服务器端处理请求时发生错误: ", e);
        }
    }

    private static void handleServerSort(ServerPlayer player, AbstractContainerMenu container, boolean isPlayerInventory) {
        List<Slot> slotsToSort = new ArrayList<>();
        if (isPlayerInventory || container instanceof InventoryMenu) {
            for (int i = INVENTORY_START; i <= INVENTORY_END; i++) {
                slotsToSort.add(container.getSlot(i));
            }
        } else {
            int containerSize = container.slots.size() - 36;
            for (int i = 0; i < containerSize; i++) {
                slotsToSort.add(container.getSlot(i));
            }
        }
        serverSortSlots(slotsToSort, container, player);
    }

    private static void serverSortSlots(List<Slot> slots, AbstractContainerMenu container, ServerPlayer player) {
        serverMergeIdenticalItems(slots, container, player);
        serverPerformSort(slots, container, player);
    }

    private static void serverMergeIdenticalItems(List<Slot> slots, AbstractContainerMenu container, ServerPlayer player) {
        boolean merged;
        int iterations = 0;

        do {
            merged = false;
            iterations++;
            if (iterations > MAX_MERGE_ITERATIONS) break;

            for (int i = 0; i < slots.size() && !merged; i++) {
                Slot slot1 = slots.get(i);
                ItemStack stack1 = slot1.getItem();

                if (stack1.isEmpty() || stack1.getCount() >= stack1.getMaxStackSize()) {
                    continue;
                }
                // 白名单物品跳过 merge，保持原有分散状态
                if (SortConfig.isWhitelisted(stack1)) {
                    continue;
                }
                for (int j = i + 1; j < slots.size(); j++) {
                    Slot slot2 = slots.get(j);
                    ItemStack stack2 = slot2.getItem();

                    if (stack2.isEmpty()) continue;
                    if (SortConfig.isWhitelisted(stack2)) continue;

                    if (ItemStack.isSameItemSameTags(stack1, stack2)) {
                        int spaceAvailable = stack1.getMaxStackSize() - stack1.getCount();
                        if (spaceAvailable > 0) {
                            if (serverMergeStacks(slot1, slot2, container, player)) {
                                merged = true;
                                break;
                            }
                        }
                    }
                }
            }
        } while (merged);
    }

    private static boolean serverMergeStacks(Slot targetSlot, Slot sourceSlot, AbstractContainerMenu container, ServerPlayer player) {
        ItemStack target = targetSlot.getItem();
        ItemStack source = sourceSlot.getItem();
        if (!ItemStack.isSameItemSameTags(target, source)) {
            return false;
        }
        int maxStackSize = target.getMaxStackSize();
        int spaceAvailable = maxStackSize - target.getCount();
        if (spaceAvailable <= 0) {
            return false;
        }
        int transferAmount = Math.min(spaceAvailable, source.getCount());
        // 使用 slot.set() 而不是直接修改引用，避免中途 broadcastChanges 同步 carried item
        ItemStack newTarget = target.copy();
        newTarget.grow(transferAmount);
        targetSlot.set(newTarget);
        ItemStack newSource = source.copy();
        newSource.shrink(transferAmount);
        sourceSlot.set(newSource.isEmpty() ? ItemStack.EMPTY : newSource);
        // 不在此处调 broadcastChanges，由 serverSortSlots 统一在最后广播
        return transferAmount > 0;
    }

    private static void serverPerformSort(List<Slot> slots, AbstractContainerMenu container, ServerPlayer player) {
        if (player.isCreative()) {
            InventorySortMod.queueServerWork(3, () -> performCreativeModeSort(slots, container, player));
            return;
        }

        SortConfig.SortMode mode = SortConfig.getSortMode();

        // 白名单物品保持原位，记录哪些槽位需要参与排序
        List<Integer> freeIndices = new ArrayList<>();   // 参与排序的槽位下标（在 slots 列表中）
        List<ItemStack> itemsToSort = new ArrayList<>(); // 对应的物品
        for (int i = 0; i < slots.size(); i++) {
            ItemStack stack = slots.get(i).getItem();
            if (!stack.isEmpty() && SortConfig.isWhitelisted(stack)) {
                // 白名单：保持不动
                continue;
            }
            freeIndices.add(i);
            if (!stack.isEmpty()) {
                itemsToSort.add(stack.copy());
            }
        }

        itemsToSort.sort(buildComparator(mode));

        // 生成实际写入顺序（逐行 or 逐列）
        List<Integer> placement = buildPlacement(freeIndices, slots.size(), mode);

        for (int k = 0; k < placement.size(); k++) {
            int slotListIdx = placement.get(k);
            slots.get(slotListIdx).set(k < itemsToSort.size() ? itemsToSort.get(k) : ItemStack.EMPTY);
        }

        container.broadcastChanges();
    }

    /**
     * 构建填充顺序索引列表（指向 slots 列表的下标）。
     * 逐行模式：freeIndices 原样返回。
     * 逐列模式：将 freeIndices 中的槽位按列优先重排。
     * 背包/容器宽度统一按 9 格，行数 = total / 9（取整）。
     */
    private static List<Integer> buildPlacement(List<Integer> freeIndices, int totalSlots, SortConfig.SortMode mode) {
        if (!SortConfig.isColumnMode(mode) || freeIndices.isEmpty()) {
            return new ArrayList<>(freeIndices);
        }

        // 推算网格宽度（大多数容器是 9 宽）
        int width = 9;
        int rows = Math.max(1, (int) Math.ceil((double) totalSlots / width));

        // 把 freeIndices 按物理位置映射到 (row, col)，再列优先遍历
        // freeIndices 里存的是 slots 列表的下标，其物理位置即为该下标在 9xN 网格中的坐标
        // 列优先遍历：col 0..width-1，每列 row 0..rows-1
        List<Integer> result = new ArrayList<>();
        for (int col = 0; col < width; col++) {
            for (int row = 0; row < rows; row++) {
                int physicalIdx = row * width + col; // 在 slots 中的下标
                if (freeIndices.contains(physicalIdx)) {
                    result.add(physicalIdx);
                }
            }
        }
        // 防止遗漏（比如非标准尺寸容器）
        for (int idx : freeIndices) {
            if (!result.contains(idx)) result.add(idx);
        }
        return result;
    }

    /**
     * 根据排序模式构建比较器（列模式与对应行模式共用同一比较器，区别仅在填充顺序）
     */
    public static java.util.Comparator<ItemStack> buildComparator(SortConfig.SortMode mode) {
        return switch (mode) {
            case NAME, NAME_COLUMN -> Comparator.comparing((ItemStack a) -> getItemName(a.getItem()));

            case COUNT ->
                (a, b) -> {
                    int cmp = Integer.compare(b.getCount(), a.getCount());
                    return cmp != 0 ? cmp : getItemName(a.getItem()).compareTo(getItemName(b.getItem()));
                };

            case COUNT_ASC -> Comparator.comparingInt(ItemStack::getCount).thenComparing(a -> getItemName(a.getItem()));

            case CATEGORY_COUNT ->
                // 分类 → 子分类，同类内数量从多到少
                (a, b) -> {
                    String catA = String.format("%02d_%s_%s",
                            getItemCategory(a).getPriority(),
                            getItemCategory(a).name(),
                            getSubCategory(a, getItemCategory(a)));
                    String catB = String.format("%02d_%s_%s",
                            getItemCategory(b).getPriority(),
                            getItemCategory(b).name(),
                            getSubCategory(b, getItemCategory(b)));
                    int catCmp = catA.compareTo(catB);
                    if (catCmp != 0) return catCmp;
                    return Integer.compare(b.getCount(), a.getCount());
                };

            default ->
                // CATEGORY / CATEGORY_COLUMN：分类 → 子分类 → 名称 → 数量
                (a, b) -> {
                    String keyA = getItemKey(a);
                    String keyB = getItemKey(b);
                    int result = keyA.compareTo(keyB);
                    if (result == 0) return Integer.compare(b.getCount(), a.getCount());
                    return result;
                };
        };
    }

    private static void performCreativeModeSort(List<Slot> slots, AbstractContainerMenu container, ServerPlayer player) {
        SortConfig.SortMode mode = SortConfig.getSortMode();

        List<Integer> freeIndices = new ArrayList<>();
        List<ItemStack> itemsToSort = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            ItemStack stack = slots.get(i).getItem();
            if (!stack.isEmpty() && SortConfig.isWhitelisted(stack)) continue;
            freeIndices.add(i);
            if (!stack.isEmpty()) itemsToSort.add(stack.copy());
        }
        itemsToSort.sort(buildComparator(mode));
        List<Integer> placement = buildPlacement(freeIndices, slots.size(), mode);

        for (int k = 0; k < placement.size(); k++) {
            int idx = placement.get(k);
            ItemStack targetItem = k < itemsToSort.size() ? itemsToSort.get(k) : ItemStack.EMPTY;
            slots.get(idx).set(targetItem);
            player.connection.send(new ClientboundContainerSetSlotPacket(
                    container.containerId,
                    container.incrementStateId(),
                    slots.get(idx).index,
                    targetItem
            ));
        }
        container.broadcastChanges();
    }

    private static void handleServerQuickTransfer(ServerPlayer player, AbstractContainerMenu container, int sourceSlotId) {
        if (sourceSlotId < 0 || sourceSlotId >= container.slots.size()) {
            return;
        }

        Slot sourceSlot = container.getSlot(sourceSlotId);
        boolean isContainerToInventory = isContainerSlot(sourceSlot, container);

        if (isContainerToInventory) {
            serverTransferAllFromContainer(container, player);
        } else {
            boolean isHotbarSlot = isHotbarSlot(sourceSlot, container);
            serverTransferAllFromInventory(container, player, isHotbarSlot);
        }
    }

    private static void serverTransferAllFromContainer(AbstractContainerMenu container, ServerPlayer player) {
        List<Slot> containerSlots = getContainerSlots(container);
        serverTransferAllSlots(containerSlots, container, player, true);
    }

    private static void serverTransferAllFromInventory(AbstractContainerMenu container, ServerPlayer player, boolean onlyHotbar) {
        List<Slot> inventorySlots = getPlayerInventorySlots(container, onlyHotbar);
        serverTransferAllSlots(inventorySlots, container, player, false);
    }

    private static void serverTransferAllSlots(List<Slot> slots, AbstractContainerMenu container, ServerPlayer player, boolean toInventory) {
        for (Slot slot : slots) {
            if (slot.getItem().isEmpty()) continue;
            ItemStack itemToTransfer = slot.getItem().copy();
            if (slot.mayPickup(player)) {
                slot.onTake(player, itemToTransfer);
                if (toInventory) {
                    moveToPlayerInventory(itemToTransfer, container, player);
                } else {
                    moveToContainer(itemToTransfer, container, player);
                }
                slot.set(ItemStack.EMPTY);
            }
        }

        container.broadcastChanges();
    }

    private static void moveToPlayerInventory(ItemStack item, AbstractContainerMenu container, ServerPlayer player) {
        for (int i = 9; i < 36; i++) {
            if (i < container.slots.size()) {
                Slot targetSlot = container.getSlot(i);
                if (targetSlot.getItem().isEmpty()) {
                    targetSlot.set(item);
                    return;
                } else if (ItemStack.isSameItemSameTags(targetSlot.getItem(), item)) {
                    int space = targetSlot.getItem().getMaxStackSize() - targetSlot.getItem().getCount();
                    if (space > 0) {
                        int transfer = Math.min(space, item.getCount());
                        targetSlot.getItem().grow(transfer);
                        item.shrink(transfer);
                        if (item.isEmpty()) return;
                    }
                }
            }
        }
    }

    private static void moveToContainer(ItemStack item, AbstractContainerMenu container, ServerPlayer player) {
        int containerSize = container.slots.size() - 36;
        for (int i = 0; i < containerSize; i++) {
            Slot targetSlot = container.getSlot(i);
            if (targetSlot.getItem().isEmpty()) {
                targetSlot.set(item);
                return;
            } else if (ItemStack.isSameItemSameTags(targetSlot.getItem(), item)) {
                int space = targetSlot.getItem().getMaxStackSize() - targetSlot.getItem().getCount();
                if (space > 0) {
                    int transfer = Math.min(space, item.getCount());
                    targetSlot.getItem().grow(transfer);
                    item.shrink(transfer);
                    if (item.isEmpty()) return;
                }
            }
        }
    }

    protected static boolean isHotbarSlot(Slot slot, AbstractContainerMenu container) {
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

    protected static List<Slot> getPlayerInventorySlots(AbstractContainerMenu container, boolean onlyHotbar) {
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

    protected static List<Slot> getContainerSlots(AbstractContainerMenu container) {
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

    protected static boolean isContainerSlot(Slot slot, AbstractContainerMenu container) {
        if (container instanceof InventoryMenu) {
            return false;
        }
        int totalSlots = container.slots.size();
        int playerSlotsStart = totalSlots - 36;
        return slot.index < playerSlotsStart;
    }

    protected static boolean needsNBTDifferentiation(ItemStack stack) {
        Item item = stack.getItem();
        return stack.isEnchanted() ||
                item instanceof PotionItem ||
                item instanceof SplashPotionItem ||
                item instanceof LingeringPotionItem ||
                item instanceof TippedArrowItem ||
                item instanceof WrittenBookItem ||
                item instanceof MapItem;
    }

    protected static String getItemKey(ItemStack stack) {
        if (stack.isEmpty()) {
            return "zzz_empty";
        }
        ItemCategory category = getItemCategory(stack);
        String itemName = getItemName(stack.getItem());
        String nbtIdentifier = "";
        if (needsNBTDifferentiation(stack)) {
            nbtIdentifier = getSimplifiedNBT(stack);
        }
        String subCategory = getSubCategory(stack, category);
        return String.format("%02d_%s_%s_%s_%s",
                category.getPriority(),
                category.name(),
                subCategory,
                itemName,
                nbtIdentifier);
    }

    private static String getSubCategory(ItemStack stack, ItemCategory category) {
        Item item = stack.getItem();

        switch (category) {
            case WEAPONS:
                if (item instanceof SwordItem) return "swords";
                if (item instanceof BowItem || item instanceof CrossbowItem) return "ranged";
                if (item instanceof TridentItem) return "trident";
                return "other_weapons";

            case TOOLS:
                if (item instanceof PickaxeItem) return "pickaxes";
                if (item instanceof AxeItem) return "axes";
                if (item instanceof ShovelItem) return "shovels";
                if (item instanceof HoeItem) return "hoes";
                if (item instanceof ShearsItem) return "shears";
                return "other_tools";

            case ARMOR:
                if (item instanceof ArmorItem armorItem) {
                    return armorItem.getType().getName();
                }
                return "other_armor";

            case BLOCKS:
                String itemName = getItemName(item).toLowerCase();
                if (itemName.contains("stone")) return "stone";
                if (itemName.contains("wood") || itemName.contains("planks") || itemName.contains("log")) return "wood";
                if (itemName.contains("iron")) return "metal";
                if (itemName.contains("glass")) return "glass";
                return "other_blocks";

            case MATERIALS:
                if (isInTag(stack, "ingots")) return "ingots";
                if (isInTag(stack, "gems")) return "gems";
                if (isInTag(stack, "dusts")) return "dusts";
                if (isInTag(stack, "nuggets")) return "nuggets";
                return "other_materials";

            default:
                return "default";
        }
    }

    private static ItemCategory getItemCategory(ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemCategory.MISC;
        }
        Item item = stack.getItem();
        if (isWeapon(item, stack)) return ItemCategory.WEAPONS;
        if (isTool(item, stack)) return ItemCategory.TOOLS;
        if (isArmor(item, stack)) return ItemCategory.ARMOR;
        if (isFood(item, stack)) return ItemCategory.FOOD;
        if (isPotion(item, stack)) return ItemCategory.POTIONS;
        if (isMusic(item, stack)) return ItemCategory.MUSIC;
        if (isRedstone(item, stack)) return ItemCategory.REDSTONE;
        if (isDecoration(item, stack)) return ItemCategory.DECORATIONS;
        if (isBlock(item, stack)) return ItemCategory.BLOCKS;
        if (isMaterial(item, stack)) return ItemCategory.MATERIALS;
        return ItemCategory.MISC;
    }

    private static String getSimplifiedNBT(ItemStack stack) {
        if (stack.isEnchanted()) {
            int totalEnchantLevel = stack.getAllEnchantments().values().stream()
                    .mapToInt(Integer::intValue).sum();
            return "ench_" + String.format("%03d", totalEnchantLevel);
        }
        if (stack.getItem() instanceof PotionItem ||
                stack.getItem() instanceof SplashPotionItem ||
                stack.getItem() instanceof LingeringPotionItem) {
            return "potion_" + stack.getOrCreateTag().getString("Potion");
        }
        if (stack.getItem() instanceof WrittenBookItem) {
            return "book_" + stack.getOrCreateTag().getString("title");
        }
        if (stack.getItem() instanceof MapItem) {
            return "map_" + stack.getOrCreateTag().getInt("map");
        }
        if (stack.getTag() != null) {
            return String.valueOf(Math.abs(stack.getTag().toString().hashCode() % 1000));
        }
        return "0";
    }

    private static boolean isWeapon(Item item, ItemStack stack) {
        if (item instanceof SwordItem || item instanceof TridentItem ||
                item instanceof BowItem || item instanceof CrossbowItem) {
            return true;
        }
        if (stack.is(ItemTags.SWORDS) ||
                isInTag(stack, "weapons") || isInTag(stack, "swords") ||
                isInTag(stack, "bows") || isInTag(stack, "crossbows")) {
            return true;
        }
        String itemName = getItemName(item).toLowerCase();
        return itemName.contains("sword") || itemName.contains("blade") ||
                itemName.contains("bow") || itemName.contains("crossbow") ||
                itemName.equals("trident");
    }

    private static boolean isTool(Item item, ItemStack stack) {
        if (item instanceof DiggerItem || item instanceof ShearsItem ||
                item instanceof FlintAndSteelItem || item instanceof FishingRodItem ||
                item instanceof CompassItem || item instanceof SpyglassItem ||
                item instanceof BrushItem) {
            return true;
        }
        if (stack.is(ItemTags.TOOLS) || stack.is(ItemTags.PICKAXES) ||
                stack.is(ItemTags.AXES) || stack.is(ItemTags.SHOVELS) ||
                stack.is(ItemTags.HOES)) {
            return true;
        }
        if (isWeapon(item, stack)) {
            return false;
        }
        return false;
    }

    private static boolean isMusic(Item item, ItemStack stack) {
        if (item instanceof RecordItem) {
            return true;
        }
        if (item instanceof BlockItem) {
            Block block = ((BlockItem) item).getBlock();
            if (block instanceof JukeboxBlock || block instanceof NoteBlock) {
                return true;
            }
        }
        if (isInTag(stack, "music_discs") || isInTag(stack, "records")) {
            return true;
        }
        String itemName = getItemName(item).toLowerCase();
        return itemName.contains("music_disc") || itemName.contains("record") ||
                itemName.contains("disc") || itemName.contains("jukebox") ||
                itemName.contains("note_block");
    }

    private static boolean isArmor(Item item, ItemStack stack) {
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

    private static boolean isFood(Item item, ItemStack stack) {
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

    private static boolean isPotion(Item item, ItemStack stack) {
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

    private static boolean isRedstone(Item item, ItemStack stack) {
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

    private static boolean isDecoration(Item item, ItemStack stack) {
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

    private static boolean isBlock(Item item, ItemStack stack) {
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

    private static boolean isMaterial(Item item, ItemStack stack) {
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

    private static boolean hasArmorValue(Item item) {
        return item instanceof ArmorItem;
    }

    private static String getItemName(Item item) {
        ResourceLocation registryName = BuiltInRegistries.ITEM.getKey(item);
        return registryName != null ? registryName.getPath() : item.toString();
    }

    private static boolean isInTag(ItemStack stack, String tagName) {
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
}
