package dev.krys.blockradio.mixin;
import dev.krys.blockradio.RadioScreen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.block.Blocks;
import net.minecraft.util.hit.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class RadioMixin {
 @Inject(method="doItemUse", at=@At("HEAD"), cancellable=true)
 private void radio$open(CallbackInfo ci) {
  MinecraftClient mc=(MinecraftClient)(Object)this;
  if(mc.player!=null && mc.world!=null && !mc.player.isSneaking()
    && mc.crosshairTarget instanceof BlockHitResult hit
    && mc.world.getBlockState(hit.getBlockPos()).isOf(Blocks.JUKEBOX)) {
   mc.setScreen(new RadioScreen(hit.getBlockPos())); ci.cancel();
  }
 }
}
