package com.vortexso.guest_atmosphere.fabric;

import com.vortexso.guest_atmosphere.client.AtmosphereModels;
import com.vortexso.guest_atmosphere.client.ClientWeatherEffects;
import com.vortexso.guest_atmosphere.client.FogLights;
import com.vortexso.guest_atmosphere.client.GroundMist;
import com.vortexso.guest_atmosphere.client.GuestAtmosphereClient;
import com.vortexso.guest_atmosphere.client.WeatherFog;
import com.vortexso.guest_atmosphere.network.WeatherSyncPayload;
import java.util.IdentityHashMap;
import java.util.Map;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.model.loading.v1.ExtraModelKey;
import net.fabricmc.fabric.api.client.model.loading.v1.FabricModelManager;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.SimpleUnbakedExtraModel;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.SpriteSourceRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.world.level.block.Block;

public final class GuestAtmosphereFabricClient implements ClientModInitializer {
  private static final Map<Block, ExtraModelKey<BlockStateModel>> SPARSE = new IdentityHashMap<>();

  @Override
  public void onInitializeClient() {
    GuestAtmosphereClient.init();
    for (Block block : AtmosphereModels.DECIDUOUS) {
      SPARSE.put(block, ExtraModelKey.create(() -> AtmosphereModels.sparseModel(block).toString()));
    }
    ClientPlayNetworking.registerGlobalReceiver(
        WeatherSyncPayload.TYPE, (payload, context) -> WeatherSyncPayload.handle(payload));
    ClientPlayConnectionEvents.DISCONNECT.register(
        (listener, client) -> ClientWeatherEffects.loggingOut());
    SpriteSourceRegistry.register(
        AtmosphereModels.SPARSE_SOURCE, AtmosphereModels.SparseTextures.CODEC);
    ModelLoadingPlugin.register(
        context -> {
          SPARSE.forEach(
              (block, key) ->
                  context.addModel(
                      key,
                      SimpleUnbakedExtraModel.blockStateModel(
                          AtmosphereModels.sparseModel(block))));
          context
              .modifyBlockModelAfterBake()
              .register(
                  (model, bake) ->
                      AtmosphereModels.modify(
                          bake.state(),
                          model,
                          leaves ->
                              () ->
                                  ((FabricModelManager) Minecraft.getInstance().getModelManager())
                                      .getModel(SPARSE.get(leaves))));
        });
    LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(
        context -> {
          FogLights.render(context.levelState(), context.poseStack());
          GroundMist.render(context.levelState(), context.poseStack());
        });
    LevelRenderEvents.END_EXTRACTION.register(
        context -> {
          WeatherFog.extract(context.levelState());
          ClientWeatherEffects.extract(
              context.levelState(), context.deltaTracker(), context.camera());
        });
  }
}
