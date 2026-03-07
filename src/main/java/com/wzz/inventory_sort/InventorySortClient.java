package com.wzz.inventory_sort;

import com.wzz.inventory_sort.core.CoreHandler;
import com.wzz.inventory_sort.gui.ConfigScreen;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;

import static com.wzz.inventory_sort.InventorySortMod.MODID;

public class InventorySortClient {
    public static void init() {
        MinecraftForge.EVENT_BUS.register(new CoreHandler());
        ModList.get().getModContainerById(MODID).ifPresent(container -> container.registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (mc, parent) -> new ConfigScreen(parent)
                )
        ));
    }
}
