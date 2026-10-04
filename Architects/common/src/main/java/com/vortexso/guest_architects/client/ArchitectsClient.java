package com.vortexso.guest_architects.client;

import com.vortexso.guest_architects.GuestArchitects;
import com.vortexso.guest_architects.city.Escalation;
import com.vortexso.guest_architects.entity.Cure;
import com.vortexso.guest_core.platform.Events;
import com.vortexso.guest_core.platform.Platform;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

public final class ArchitectsClient {
  public static final ModelLayerLocation ARCHITECT_LAYER =
      new ModelLayerLocation(
          Identifier.fromNamespaceAndPath(GuestArchitects.MODID, "architect"), "main");

  private ArchitectsClient() {}

  public static void init() {
    Platform.INSTANCE.registerConfigScreen(GuestArchitects.MODID);
    Events.DEBUG_RENDER.register(ArchitectsDebugRenderer::render);
  }

  public static void extractRenderState(LivingEntity entity, LivingEntityRenderState state) {
    if (Cure.isCuring(entity)) {
      state.isFullyFrozen = true;
    }
  }

  public static boolean steadyFov(Player player) {
    return Escalation.isHeld(player);
  }
}
