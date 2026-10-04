package com.vortexso.guest_architects.fabric;

import com.vortexso.guest_architects.GuestArchitects;
import com.vortexso.guest_architects.client.ArchitectModel;
import com.vortexso.guest_architects.client.ArchitectRenderer;
import com.vortexso.guest_architects.client.ArchitectsClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.blockentity.TheEndPortalRenderer;

public final class GuestArchitectsFabricClient implements ClientModInitializer {
  @Override
  public void onInitializeClient() {
    ArchitectsClient.init();
    ModelLayerRegistry.registerModelLayer(
        ArchitectsClient.ARCHITECT_LAYER, ArchitectModel::createBodyLayer);
    EntityRendererRegistry.register(GuestArchitects.ARCHITECT.get(), ArchitectRenderer::new);
    BlockEntityRenderers.register(
        GuestArchitects.PORTAL_BLOCK_ENTITY.get(), context -> new TheEndPortalRenderer());
  }
}
