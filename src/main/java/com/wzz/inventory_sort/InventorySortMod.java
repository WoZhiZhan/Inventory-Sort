package com.wzz.inventory_sort;

import com.mojang.logging.LogUtils;
import com.wzz.inventory_sort.network.SortPacket;
import com.wzz.inventory_sort.core.CoreHandler;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import net.minecraft.resources.ResourceLocation;

@Mod(InventorySortMod.MODID)
public class InventorySortMod {
    public static final String MODID = "inventory_sort";
    private static final Logger LOGGER = LogUtils.getLogger();

    public static KeyMapping sortKey;
    public InventorySortMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            modEventBus.addListener(this::onClientSetup);
            modEventBus.addListener(this::onKeyRegister);
        }
        this.registerNetworking();
    }

    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel NETWORK = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private void registerNetworking() {
        NETWORK.registerMessage(0, SortPacket.class,
                SortPacket::encode,
                SortPacket::decode,
                SortPacket::handle);
    }

    private void onClientSetup(final FMLClientSetupEvent event) {
        MinecraftForge.EVENT_BUS.register(new CoreHandler());
    }

    private void onKeyRegister(final RegisterKeyMappingsEvent event) {
        sortKey = new KeyMapping(
                "key.inventorysort.sort",
                GLFW.GLFW_KEY_R,
                "key.categories.inventorysort"
        );
        event.register(sortKey);
    }
}