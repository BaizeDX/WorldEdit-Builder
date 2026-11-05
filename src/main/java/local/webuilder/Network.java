package local.webuilder;

import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraft.server.level.ServerPlayer;

public final class Network {
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(BuilderMod.ID, "main"), () -> "1", "1"::equals, "1"::equals);
    public record Action(int action, int value) {
        static Action read(FriendlyByteBuf b) { return new Action(b.readVarInt(), b.readVarInt()); }
        void write(FriendlyByteBuf b) { b.writeVarInt(action); b.writeVarInt(value); }
        void handle(Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context c = supplier.get();
            c.enqueueWork(() -> { if (c.getSender() != null) ServerController.action(c.getSender(), action, value); });
            c.setPacketHandled(true);
        }
    }
    public record Status(CompoundTag data) {
        static Status read(FriendlyByteBuf b) { return new Status(b.readNbt()); }
        void write(FriendlyByteBuf b) { b.writeNbt(data); }
        void handle(Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context c = supplier.get();
            c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientController.receive(data)));
            c.setPacketHandled(true);
        }
    }
    static void init() {
        CHANNEL.messageBuilder(Action.class, 0, NetworkDirection.PLAY_TO_SERVER)
            .encoder(Action::write).decoder(Action::read).consumerMainThread(Action::handle).add();
        CHANNEL.messageBuilder(Status.class, 1, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(Status::write).decoder(Status::read).consumerMainThread(Status::handle).add();
    }
    public static void action(int action, int value) { CHANNEL.sendToServer(new Action(action, value)); }
    static void status(ServerPlayer p, CompoundTag data) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new Status(data));
    }
}
