package com.vortexso.guest_architects.client;

import com.vortexso.guest_architects.GuestArchitects;
import com.vortexso.guest_architects.city.Escalation;
import com.vortexso.guest_architects.entity.Cure;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.blockentity.TheEndPortalRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent;

@Mod(value = GuestArchitects.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = GuestArchitects.MODID, value = Dist.CLIENT)
public final class ArchitectsClient {
  public static final ModelLayerLocation ARCHITECT_LAYER =
      new ModelLayerLocation(
          Identifier.fromNamespaceAndPath(GuestArchitects.MODID, "architect"), "main");

  public ArchitectsClient(ModContainer container) {
    container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
  }

  @SubscribeEvent
  public static void layers(EntityRenderersEvent.RegisterLayerDefinitions event) {
    event.registerLayerDefinition(ARCHITECT_LAYER, ArchitectModel::createBodyLayer);
  }

  @SubscribeEvent
  public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
    event.registerEntityRenderer(GuestArchitects.ARCHITECT.get(), ArchitectRenderer::new);

    event.registerBlockEntityRenderer(
        GuestArchitects.PORTAL_BLOCK_ENTITY.get(), context -> new TheEndPortalRenderer());
  }

  @SubscribeEvent
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static void renderStates(RegisterRenderStateModifiersEvent event) {
    event.registerEntityModifier(
        (Class) LivingEntityRenderer.class,
        (LivingEntity entity, LivingEntityRenderState state) -> {
          if (Cure.isCuring(entity)) {
            state.isFullyFrozen = true;
          }
        });
  }

  @SubscribeEvent
  public static void fov(ComputeFovModifierEvent event) {
    if (Escalation.isHeld(event.getPlayer())) {
      event.setNewFovModifier(1.0F);
    }
  }
}
