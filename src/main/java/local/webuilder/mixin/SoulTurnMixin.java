package local.webuilder.mixin;

import local.webuilder.SoulCamera;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
abstract class SoulTurnMixin {
    @Inject(method = {"turn(DD)V", "m_19884_(DD)V"}, at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void webuilder$turnCamera(double horizontal, double vertical, CallbackInfo callback) {
        if (SoulCamera.turnCamera((Entity)(Object)this, horizontal, vertical)) callback.cancel();
    }
}
