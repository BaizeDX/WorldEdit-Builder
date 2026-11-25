package local.webuilder;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid=BuilderMod.ID,value=Dist.CLIENT)
public final class SoulCamera {
    private static RemotePlayer camera;
    private static LocalPlayer body;
    private static CameraType perspective;
    private static Vec3 anchor;
    private static float yaw,pitch;

    static boolean active() { return camera!=null; }
    public static boolean turnCamera(net.minecraft.world.entity.Entity entity, double horizontal, double vertical) {
        if (camera == null || entity != body) return false;
        camera.turn(horizontal, vertical);
        return true;
    }
    static void toggle(Minecraft mc) {
        if (active()) { close(mc); return; }
        var player=mc.player;
        if (player==null || !player.isCreative() || player.isPassenger() || mc.getCameraEntity()!=player) return;
        if (!player.onGround() && !player.getAbilities().flying) {
            player.displayClientMessage(Component.literal("请在地面或创造飞行中出窍。"),true); return;
        }
        player.setDeltaMovement(Vec3.ZERO);
        body=player; anchor=player.position(); yaw=player.getYRot(); pitch=player.getXRot();
        perspective=mc.options.getCameraType();
        camera=new RemotePlayer(mc.level,new GameProfile(UUID.randomUUID(),"BuilderCamera")) {
            @Override public float getViewYRot(float partialTick) { return getYRot(); }
            @Override public float getViewXRot(float partialTick) { return getXRot(); }
        };
        camera.noPhysics=true;
        camera.moveTo(anchor.x,anchor.y,anchor.z,yaw,pitch);
        camera.setOldPosAndRot();
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        mc.setCameraEntity(camera);
    }
    static void close(Minecraft mc) {
        if (!active()) return;
        if (mc.getCameraEntity()==camera && mc.player!=null) mc.setCameraEntity(mc.player);
        if (body==mc.player && body.level()==mc.level && body.position().distanceToSqr(anchor)<0.01) {
            body.setYRot(yaw); body.setXRot(pitch);
            body.yRotO=yaw; body.xRotO=pitch;
        }
        mc.options.setCameraType(perspective);
        camera=null; body=null; anchor=null;
    }
    @SubscribeEvent public static void checkBody(TickEvent.ClientTickEvent e) {
        if (!active() || e.phase!=TickEvent.Phase.START) return;
        var mc=Minecraft.getInstance();
        if (mc.player!=body || mc.level!=body.level() || !body.isAlive() || !body.isCreative()
            || body.isPassenger() || body.position().distanceToSqr(anchor)>0.01 || mc.getCameraEntity()!=camera) close(mc);
    }
    @SubscribeEvent public static void input(MovementInputUpdateEvent e) {
        if (!active() || e.getEntity()!=body) return;
        var input=e.getInput();
        var mc=Minecraft.getInstance();
        camera.setOldPosAndRot();
        if (mc.screen==null) {
            int vertical=(input.jumping?1:0)-(input.shiftKeyDown?1:0);
            camera.setDeltaMovement(Vec3.ZERO);
            camera.moveRelative(mc.options.keySprint.isDown()?1.2F:0.5F,
                new Vec3(input.leftImpulse,vertical,input.forwardImpulse));
            Vec3 motion=camera.getDeltaMovement();
            if (mc.level.hasChunkAt(BlockPos.containing(camera.position().add(motion)))) camera.move(MoverType.SELF,motion);
            camera.setDeltaMovement(Vec3.ZERO);
        }
        input.forwardImpulse=0; input.leftImpulse=0;
        input.up=input.down=input.left=input.right=input.jumping=input.shiftKeyDown=false;
    }
    @SubscribeEvent public static void hand(RenderHandEvent event) { if (active()) event.setCanceled(true); }
}
