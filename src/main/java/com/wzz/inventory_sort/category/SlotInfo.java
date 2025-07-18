package com.wzz.inventory_sort.category;

import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class SlotInfo {
    int originalIndex;
    Slot slot;
    ItemStack stack;

    SlotInfo(int originalIndex, Slot slot, ItemStack stack) {
        this.originalIndex = originalIndex;
        this.slot = slot;
        this.stack = stack;
    }
}