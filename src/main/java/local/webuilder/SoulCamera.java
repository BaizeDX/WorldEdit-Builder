package local.webuilder;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid=BuilderMod.ID,value=Dist.CLIENT)
final class SoulCamera {
    private static RemotePlayer camera;
    private static LocalPlayer body;
    private static CameraType perspective;
    private static Vec3 anchor;
    private static float yaw,pitch;

    static boolean active() { return camera!=null; }
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
        camera=new RemotePlayer(mc.level,new GameProfile(UUID.randomUUID(),"BuilderCamera"));
        camera.noPhysics=true;
        camera.moveTo(anchor.x,anchor.y,anchor.z,yaw,pitch);
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        mc.setCameraEntity(camera);
    }
    static void close(Minecraft mc) {
        if (!active()) return;
        if (mc.getCameraEntity()==camera && mc.player!=null) mc.setCameraEntity(mc.player);
        if (body==mc.player && body.level()==mc.level && body.position().distanceToSqr(anchor)<0.01) {
            body.setYRot(yaw); body.setXRot(pitch);
        }
        mc.options.setCameraType(perspective);
        camera=null; body=null; anchor=null;
    }
    private static void look(Minecraft mc) {
        if (mc.player!=body || mc.level!=body.level() || !body.isAlive() || !body.isCreative()
            || body.position().distanceToSqr(anchor)>0.01 || mc.getCameraEntity()!=camera) { close(mc); return; }
        camera.setYRot(camera.getYRot()+Mth.wrapDegrees(body.getYRot()-yaw));
        camera.setXRot(body.getXRot());
        camera.yRotO=camera.getYRot(); camera.xRotO=camera.getXRot();
        body.setYRot(yaw);
    }
    @SubscribeEvent public static void render(TickEvent.RenderTickEvent e) {
        if (active() && e.phase==TickEvent.Phase.START) look(Minecraft.getInstance());
    }
    @SubscribeEvent public static void move(TickEvent.ClientTickEvent e) {
        if (!active() || e.phase!=TickEvent.Phase.START) return;
        var mc=Minecraft.getInstance();
        look(mc);
        if (!active()) return;
        camera.setOldPosAndRot();
        if (mc.screen!=null) return;
        var keys=mc.options;
        int forward=(keys.keyUp.isDown()?1:0)-(keys.keyDown.isDown()?1:0);
        int side=(keys.keyRight.isDown()?1:0)-(keys.keyLeft.isDown()?1:0);
        int up=(keys.keyJump.isDown()?1:0)-(keys.keyShift.isDown()?1:0);
        double angle=Math.toRadians(camera.getYRot());
        Vec3 motion=new Vec3(side*-Math.cos(angle)-forward*Math.sin(angle),up,
            forward*Math.cos(angle)-side*Math.sin(angle)).normalize().scale(keys.keySprint.isDown()?1.2:0.5);
        Vec3 next=camera.position().add(motion);
        if (mc.level.hasChunkAt(BlockPos.containing(next))) camera.setPos(next);
    }
    @SubscribeEvent public static void input(MovementInputUpdateEvent e) {
        if (!active() || e.getEntity()!=body) return;
        var input=e.getInput();
        input.forwardImpulse=0; input.leftImpulse=0;
        input.up=input.down=input.left=input.right=input.jumping=input.shiftKeyDown=false;
    }
    @SubscribeEvent public static void hand(RenderHandEvent event) { if (active()) event.setCanceled(true); }
}
