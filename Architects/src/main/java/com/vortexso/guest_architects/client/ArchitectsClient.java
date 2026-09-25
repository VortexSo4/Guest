package com.vortexso.guest_architects.client;

import com.vortexso.guest_architects.GuestArchitects;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

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
    event.registerLayerDefinition(
        ARCHITECT_LAYER,
        () -> LayerDefinition.create(HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F), 64, 64));
  }

  @SubscribeEvent
  public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
    event.registerEntityRenderer(GuestArchitects.ARCHITECT.get(), ArchitectRenderer::new);
  }
}
