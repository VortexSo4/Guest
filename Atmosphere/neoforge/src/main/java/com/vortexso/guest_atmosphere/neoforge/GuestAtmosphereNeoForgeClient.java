package com.vortexso.guest_atmosphere.neoforge;

import com.vortexso.guest_atmosphere.GuestAtmosphere;
import com.vortexso.guest_atmosphere.block.AtmosphereBlocks;
import com.vortexso.guest_atmosphere.block.CoveredPlantBlock;
import com.vortexso.guest_atmosphere.client.AtmosphereModels;
import com.vortexso.guest_atmosphere.client.AuroraRenderer;
import com.vortexso.guest_atmosphere.client.ClientWeatherEffects;
import com.vortexso.guest_atmosphere.client.FogLights;
import com.vortexso.guest_atmosphere.client.GroundMist;
import com.vortexso.guest_atmosphere.client.GuestAtmosphereClient;
import com.vortexso.guest_atmosphere.client.VegetationTint;
import com.vortexso.guest_atmosphere.client.WeatherFog;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterSpriteSourcesEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Vector4f;

@Mod(value = GuestAtmosphere.MODID, dist = Dist.CLIENT)
public final class GuestAtmosphereNeoForgeClient {
  private static final Map<Block, StandaloneModelKey<BlockStateModel>> SPARSE =
      new IdentityHashMap<>();

  static {
    for (Block block : AtmosphereModels.DECIDUOUS) {
      SPARSE.put(
          block, new StandaloneModelKey<>(() -> AtmosphereModels.sparseModel(block).toString()));
    }
  }

  public GuestAtmosphereNeoForgeClient(IEventBus modBus) {
    GuestAtmosphereClient.init();
    modBus.addListener(
        RegisterColorHandlersEvent.BlockTintSources.class,
        event -> VegetationTint.registerTints(event.getBlockColors()));
    modBus.addListener(
        RegisterSpriteSourcesEvent.class,
        event ->
            event.register(AtmosphereModels.SPARSE_SOURCE, AtmosphereModels.SparseTextures.CODEC));
    modBus.addListener(GuestAtmosphereNeoForgeClient::onRegisterStandalone);
    modBus.addListener(GuestAtmosphereNeoForgeClient::onModifyBakingResult);
    NeoForge.EVENT_BUS.addListener(GuestAtmosphereNeoForgeClient::onRenderFog);
    NeoForge.EVENT_BUS.addListener(GuestAtmosphereNeoForgeClient::onFogColor);
    NeoForge.EVENT_BUS.addListener(
        RenderLevelStageEvent.AfterSky.class, event -> AuroraRenderer.renderSky());
    NeoForge.EVENT_BUS.addListener(GuestAtmosphereNeoForgeClient::onAfterTranslucent);
    NeoForge.EVENT_BUS.addListener(GuestAtmosphereNeoForgeClient::onExtract);
    NeoForge.EVENT_BUS.addListener(
        PlaySoundEvent.class,
        event -> event.setSound(ClientWeatherEffects.playSound(event.getSound())));
    NeoForge.EVENT_BUS.addListener(
        ClientPlayerNetworkEvent.LoggingOut.class, event -> ClientWeatherEffects.loggingOut());
  }

  private static void onRegisterStandalone(ModelEvent.RegisterStandalone event) {
    SPARSE.forEach(
        (block, key) ->
            event.register(
                key,
                SimpleUnbakedStandaloneModel.blockStateModel(AtmosphereModels.sparseModel(block))));
  }

  private static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
    Map<BlockState, BlockStateModel> models = event.getBakingResult().blockStateModels();
    for (Block block : SPARSE.keySet()) {
      modify(event, models, block);
    }
    for (CoveredPlantBlock block : AtmosphereBlocks.coveredPlants()) {
      modify(event, models, block);
    }
  }

  private static void modify(
      ModelEvent.ModifyBakingResult event, Map<BlockState, BlockStateModel> models, Block block) {
    for (BlockState state : block.getStateDefinition().getPossibleStates()) {
      models.computeIfPresent(
          state,
          (ignored, model) ->
              AtmosphereModels.modify(
                  state,
                  model,
                  leaves -> {
                    BlockStateModel sparse =
                        event.getBakingResult().standaloneModels().get(SPARSE.get(leaves));
                    return () -> sparse;
                  }));
    }
  }

  private static void onRenderFog(ViewportEvent.RenderFog event) {
    WeatherFog.renderFog(event.getType(), event.getFogData());
  }

  private static void onFogColor(ViewportEvent.ComputeFogColor event) {
    Vector4f color = new Vector4f(event.getRed(), event.getGreen(), event.getBlue(), 1.0F);
    WeatherFog.fogColor(event.getCamera(), color);
    event.setRed(color.x());
    event.setGreen(color.y());
    event.setBlue(color.z());
  }

  private static void onAfterTranslucent(RenderLevelStageEvent.AfterTranslucentBlocks event) {
    FogLights.render(event.getLevelRenderState(), event.getPoseStack());
    GroundMist.render(event.getLevelRenderState(), event.getPoseStack());
  }

  private static void onExtract(ExtractLevelRenderStateEvent event) {
    WeatherFog.extract(event.getRenderState());
    ClientWeatherEffects.extract(
        event.getRenderState(), event.getDeltaTracker(), event.getCamera());
  }
}
