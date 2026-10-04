package com.vortexso.guest_architects.neoforge;

import com.vortexso.guest_architects.GuestArchitects;
import com.vortexso.guest_architects.client.ArchitectModel;
import com.vortexso.guest_architects.client.ArchitectRenderer;
import com.vortexso.guest_architects.client.ArchitectsClient;
import net.minecraft.client.renderer.blockentity.TheEndPortalRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = GuestArchitects.MODID, dist = Dist.CLIENT)
public final class GuestArchitectsNeoForgeClient {
  public GuestArchitectsNeoForgeClient(IEventBus modBus) {
    ArchitectsClient.init();
    modBus.addListener(GuestArchitectsNeoForgeClient::layers);
    modBus.addListener(GuestArchitectsNeoForgeClient::renderers);
    modBus.addListener(GuestArchitectsNeoForgeClient::renderStates);
    NeoForge.EVENT_BUS.addListener(GuestArchitectsNeoForgeClient::fov);
  }

  private static void layers(EntityRenderersEvent.RegisterLayerDefinitions event) {
    event.registerLayerDefinition(
        ArchitectsClient.ARCHITECT_LAYER, ArchitectModel::createBodyLayer);
  }

  private static void renderers(EntityRenderersEvent.RegisterRenderers event) {
    event.registerEntityRenderer(GuestArchitects.ARCHITECT.get(), ArchitectRenderer::new);
    event.registerBlockEntityRenderer(
        GuestArchitects.PORTAL_BLOCK_ENTITY.get(), context -> new TheEndPortalRenderer());
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void renderStates(RegisterRenderStateModifiersEvent event) {
    event.registerEntityModifier(
        (Class) LivingEntityRenderer.class,
        (LivingEntity entity, LivingEntityRenderState state) ->
            ArchitectsClient.extractRenderState(entity, state));
  }

  private static void fov(ComputeFovModifierEvent event) {
    if (ArchitectsClient.steadyFov(event.getPlayer())) {
      event.setNewFovModifier(1.0F);
    }
  }
}
