package com.vortexso.guest_settlements.fabric.mixin;

import com.vortexso.guest_settlements.illager.IllagerRhythm;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.CustomSpawner;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
  @Shadow @Final @Mutable private List<CustomSpawner> customSpawners;

  @Inject(method = "<init>", at = @At("RETURN"))
  private void guestSettlements$seasonalPatrols(CallbackInfo ci) {
    List<CustomSpawner> spawners = new ArrayList<>(customSpawners);
    IllagerRhythm.modifySpawners(spawners);
    customSpawners = spawners;
  }
}
