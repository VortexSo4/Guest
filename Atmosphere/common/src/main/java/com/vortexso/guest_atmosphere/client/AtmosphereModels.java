package com.vortexso.guest_atmosphere.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.vortexso.guest_atmosphere.GuestAtmosphere;
import com.vortexso.guest_atmosphere.block.CoveredPlantBlock;
import com.vortexso.guest_core.api.GuestHash;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.ARGB;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

public final class AtmosphereModels {
  private static final long SALT = 0x3C6EF372FE94F82BL;

  public static final Identifier SPARSE_SOURCE =
      Identifier.fromNamespaceAndPath(GuestAtmosphere.MODID, "sparse");

  public static final List<Block> DECIDUOUS =
      List.of(
          Blocks.OAK_LEAVES,
          Blocks.BIRCH_LEAVES,
          Blocks.JUNGLE_LEAVES,
          Blocks.ACACIA_LEAVES,
          Blocks.DARK_OAK_LEAVES,
          Blocks.MANGROVE_LEAVES,
          Blocks.CHERRY_LEAVES,
          Blocks.AZALEA_LEAVES,
          Blocks.FLOWERING_AZALEA_LEAVES,
          Blocks.PALE_OAK_LEAVES);

  private AtmosphereModels() {}

  public static Identifier sparseModel(Block block) {
    return Identifier.fromNamespaceAndPath(
        GuestAtmosphere.MODID, "block/sparse_" + BuiltInRegistries.BLOCK.getKey(block).getPath());
  }

  public static BlockStateModel modify(
      BlockState state,
      BlockStateModel model,
      Function<Block, Supplier<@Nullable BlockStateModel>> sparse) {
    if (state.getBlock() instanceof CoveredPlantBlock) {
      return new Layered(CoveredPlantBlock.plantState(state), CoveredPlantBlock.coverState(state));
    }
    if (DECIDUOUS.contains(state.getBlock())) {
      return new Seasonal(model, sparse.apply(state.getBlock()));
    }
    return model;
  }

  private record Seasonal(BlockStateModel normal, Supplier<@Nullable BlockStateModel> sparse)
      implements BlockStateModel {
    private BlockStateModel current() {
      BlockStateModel model = VegetationTint.sparse() ? sparse.get() : null;
      return model != null ? model : normal;
    }

    @Override
    public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
      current().collectParts(random, parts);
    }

    @Override
    public Material.Baked particleMaterial() {
      return normal.particleMaterial();
    }

    @Override
    public int materialFlags() {
      BlockStateModel model = sparse.get();
      return model == null
          ? normal.materialFlags()
          : normal.materialFlags() | model.materialFlags();
    }
  }

  private record Layered(BlockState plant, @Nullable BlockState cover) implements BlockStateModel {
    private static BlockStateModel model(BlockState state) {
      return Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
    }

    @Override
    public void collectParts(RandomSource random, List<BlockStateModelPart> output) {
      long seed = random.nextLong();
      random.setSeed(seed);
      model(plant).collectParts(random, output);
      if (cover != null) {
        random.setSeed(seed);
        model(cover).collectParts(random, output);
      }
    }

    @Override
    public Material.Baked particleMaterial() {
      return model(cover != null ? cover : plant).particleMaterial();
    }

    @Override
    public int materialFlags() {
      return cover == null
          ? model(plant).materialFlags()
          : model(plant).materialFlags() | model(cover).materialFlags();
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
            loader -> {
              try (InputStream input = resource.get().open()) {
                NativeImage image = NativeImage.read(input);
                thin(image);
                return new SpriteContents(
                    sparse,
                    new FrameSize(image.getWidth(), image.getHeight()),
                    image,
                    Optional.empty(),
                    List.of(),
                    resource.get().metadata().getSection(TextureMetadataSection.TYPE));
              } catch (IOException exception) {
                GuestAtmosphere.LOGGER.warn("Could not thin {}", texture, exception);
                return null;
              }
            });
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
