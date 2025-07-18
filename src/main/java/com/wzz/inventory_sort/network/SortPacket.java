package com.wzz.inventory_sort.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

import static com.wzz.inventory_sort.core.CoreServer.handleServerRequest;

public class SortPacket {
    private final boolean isQuickTransfer;
    private final int slotId;
    private final boolean isPlayerInventory;

    public SortPacket(boolean isQuickTransfer, int slotId, boolean isPlayerInventory) {
        this.isQuickTransfer = isQuickTransfer;
        this.slotId = slotId;
        this.isPlayerInventory = isPlayerInventory;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBoolean(this.isQuickTransfer);
        buffer.writeInt(this.slotId);
        buffer.writeBoolean(this.isPlayerInventory);
    }

    public static SortPacket decode(FriendlyByteBuf buffer) {
        return new SortPacket(buffer.readBoolean(), buffer.readInt(), buffer.readBoolean());
    }

    public static void handle(SortPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                handleServerRequest(player, packet.isQuickTransfer, packet.slotId, packet.isPlayerInventory);
            }
        });
        context.setPacketHandled(true);
    }
}