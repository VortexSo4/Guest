package com.vortexso.guest_atmosphere.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.vortexso.guest_atmosphere.GuestAtmosphere;
import com.vortexso.guest_atmosphere.block.AtmosphereBlocks;
import com.vortexso.guest_atmosphere.block.CoveredPlantBlock;
import com.vortexso.guest_core.api.GuestHash;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.ARGB;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterSpriteSourcesEvent;
import net.neoforged.neoforge.client.model.DynamicBlockStateModel;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;

@EventBusSubscriber(modid = GuestAtmosphere.MODID, value = Dist.CLIENT)
public final class AtmosphereModels {
  private static final long SALT = 0x3C6EF372FE94F82BL;

  private static final Block[] DECIDUOUS = {
    Blocks.OAK_LEAVES,
    Blocks.BIRCH_LEAVES,
    Blocks.JUNGLE_LEAVES,
    Blocks.ACACIA_LEAVES,
    Blocks.DARK_OAK_LEAVES,
    Blocks.MANGROVE_LEAVES,
    Blocks.CHERRY_LEAVES,
    Blocks.AZALEA_LEAVES,
    Blocks.FLOWERING_AZALEA_LEAVES,
    Blocks.PALE_OAK_LEAVES
  };

  private static final Map<Block, StandaloneModelKey<BlockStateModel>> SPARSE =
      new IdentityHashMap<>();

  static {
    for (Block block : DECIDUOUS) {
      String name = "block/sparse_" + BuiltInRegistries.BLOCK.getKey(block).getPath();
      SPARSE.put(block, new StandaloneModelKey<>(() -> GuestAtmosphere.MODID + ":" + name));
    }
  }

  private AtmosphereModels() {}

  @SubscribeEvent
  public static void onRegisterSpriteSources(RegisterSpriteSourcesEvent event) {
    event.register(
        Identifier.fromNamespaceAndPath(GuestAtmosphere.MODID, "sparse"), SparseTextures.CODEC);
  }

  @SubscribeEvent
  public static void onRegisterStandalone(ModelEvent.RegisterStandalone event) {
    SPARSE.forEach(
        (block, key) ->
            event.register(
                key,
                SimpleUnbakedStandaloneModel.blockStateModel(
                    Identifier.fromNamespaceAndPath(
                        GuestAtmosphere.MODID,
                        "block/sparse_" + BuiltInRegistries.BLOCK.getKey(block).getPath()))));
  }

  @SubscribeEvent
  public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
    Map<BlockState, BlockStateModel> models = event.getBakingResult().blockStateModels();
    SPARSE.forEach(
        (block, key) -> {
          BlockStateModel sparse = event.getBakingResult().standaloneModels().get(key);
          if (sparse != null) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
              models.computeIfPresent(state, (ignored, normal) -> new Seasonal(normal, sparse));
            }
          }
        });
    for (CoveredPlantBlock block : AtmosphereBlocks.coveredPlants()) {
      for (BlockState state : block.getStateDefinition().getPossibleStates()) {
        List<Part> parts = new ArrayList<>(2);
        BlockState plant = CoveredPlantBlock.plantState(state);
        BlockStateModel plantModel = models.get(plant);
        if (plantModel != null) {
          parts.add(new Part(plantModel, plant));
        }
        BlockState cover = CoveredPlantBlock.coverState(state);
        BlockStateModel coverModel = cover == null ? null : models.get(cover);
        if (coverModel != null) {
          parts.add(new Part(coverModel, cover));
        }
        if (!parts.isEmpty()) {
          models.put(state, new Layered(List.copyOf(parts)));
        }
      }
    }
  }

  private record Seasonal(BlockStateModel normal, BlockStateModel sparse)
      implements DynamicBlockStateModel {
    private BlockStateModel current() {
      return VegetationTint.sparse() ? sparse : normal;
    }

    @Override
    public void collectParts(
        BlockAndTintGetter level,
        BlockPos pos,
        BlockState state,
        RandomSource random,
        List<BlockStateModelPart> parts) {
      current().collectParts(level, pos, state, random, parts);
    }

    @Override
    public Material.Baked particleMaterial() {
      return normal.particleMaterial();
    }

    @Override
    public int materialFlags() {
      return normal.materialFlags() | sparse.materialFlags();
    }
  }

  private record Part(BlockStateModel model, BlockState state) {}

  private record Layered(List<Part> parts) implements DynamicBlockStateModel {
    @Override
    public void collectParts(
        BlockAndTintGetter level,
        BlockPos pos,
        BlockState state,
        RandomSource random,
        List<BlockStateModelPart> output) {
      long seed = random.nextLong();
      for (Part part : parts) {
        random.setSeed(seed);
        part.model().collectParts(level, pos, part.state(), random, output);
      }
    }

    @Override
    public Material.Baked particleMaterial() {
      return parts.getLast().model().particleMaterial();
    }

    @Override
    public int materialFlags() {
      int flags = 0;
      for (Part part : parts) {
        flags |= part.model().materialFlags();
      }
      return flags;
    }
  }

  public record SparseTextures(List<Identifier> textures) implements SpriteSource {
    public static final MapCodec<SparseTextures> CODEC =
        RecordCodecBuilder.mapCodec(
            instance ->
                instance
                    .group(
                        Identifier.CODEC
                            .listOf()
                            .fieldOf("textures")
                            .forGetter(SparseTextures::textures))
                    .apply(instance, SparseTextures::new));

    @Override
    public void run(ResourceManager resourceManager, SpriteSource.Output output) {
      for (Identifier texture : textures) {
        Optional<Resource> resource =
            resourceManager.getResource(TEXTURE_ID_CONVERTER.idToFile(texture));
        if (resource.isEmpty()) {
          GuestAtmosphere.LOGGER.warn("Missing texture {} for sparse leaves", texture);
          continue;
        }
        Identifier sparse = texture.withSuffix("_sparse");
        output.add(
            sparse,
            loader ->
                loader.loadSprite(
                    sparse,
                    resource.get(),
                    (id, size, image, animation, metadata, info) -> {
                      thin(image);
                      return new SpriteContents(id, size, image, animation, metadata, info);
                    }));
      }
    }

    private static void thin(NativeImage image) {
      for (int x = 0; x < image.getWidth(); x++) {
        for (int y = 0; y < image.getHeight(); y++) {
          if (ARGB.alpha(image.getPixel(x, y)) > 0
              && GuestHash.unit(GuestHash.hash(SALT, x >> 1, y >> 1)) < 0.5) {
            image.setPixel(x, y, 0);
          }
        }
      }
    }

    @Override
    public MapCodec<SparseTextures> codec() {
      return CODEC;
    }
  }
}
