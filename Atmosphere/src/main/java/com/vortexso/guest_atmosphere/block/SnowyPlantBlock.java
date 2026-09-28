package com.vortexso.guest_atmosphere.block;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import org.jspecify.annotations.Nullable;

public class SnowyPlantBlock extends SnowLayerBlock {
  public static final EnumProperty<Plant> PLANT = EnumProperty.create("plant", Plant.class);

  public enum Plant implements StringRepresentable {
    SHORT_GRASS(Blocks.SHORT_GRASS),
    FERN(Blocks.FERN),
    BUSH(Blocks.BUSH),
    DEAD_BUSH(Blocks.DEAD_BUSH),
    SHORT_DRY_GRASS(Blocks.SHORT_DRY_GRASS),
    FIREFLY_BUSH(Blocks.FIREFLY_BUSH),
    DANDELION(Blocks.DANDELION),
    GOLDEN_DANDELION(Blocks.GOLDEN_DANDELION),
    POPPY(Blocks.POPPY),
    BLUE_ORCHID(Blocks.BLUE_ORCHID),
    ALLIUM(Blocks.ALLIUM),
    AZURE_BLUET(Blocks.AZURE_BLUET),
    RED_TULIP(Blocks.RED_TULIP),
    ORANGE_TULIP(Blocks.ORANGE_TULIP),
    WHITE_TULIP(Blocks.WHITE_TULIP),
    PINK_TULIP(Blocks.PINK_TULIP),
    OXEYE_DAISY(Blocks.OXEYE_DAISY),
    CORNFLOWER(Blocks.CORNFLOWER),
    LILY_OF_THE_VALLEY(Blocks.LILY_OF_THE_VALLEY),
    TORCHFLOWER(Blocks.TORCHFLOWER);

    private static final Map<Block, Plant> BY_BLOCK = new IdentityHashMap<>();

    static {
      for (Plant plant : values()) {
        BY_BLOCK.put(plant.block, plant);
      }
    }

    private final Block block;

    Plant(Block block) {
      this.block = block;
    }

    public Block block() {
      return block;
    }

    public static @Nullable Plant of(BlockState state) {
      return BY_BLOCK.get(state.getBlock());
    }

    @Override
    public String getSerializedName() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public SnowyPlantBlock(BlockBehaviour.Properties properties) {
    super(properties);
    registerDefaultState(
        stateDefinition.any().setValue(LAYERS, 1).setValue(PLANT, Plant.SHORT_GRASS));
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    super.createBlockStateDefinition(builder);
    builder.add(PLANT);
  }

  public static @Nullable BlockState cover(BlockState plant, int layers) {
    Plant type = Plant.of(plant);
    return type == null
        ? null
        : AtmosphereBlocks.SNOWY_PLANT
            .get()
            .defaultBlockState()
            .setValue(PLANT, type)
            .setValue(LAYERS, Math.max(1, Math.min(8, layers)));
  }

  public static BlockState withLayers(BlockState state, int layers) {
    return layers <= 0
        ? state.getValue(PLANT).block().defaultBlockState()
        : state.setValue(LAYERS, Math.min(8, layers));
  }

  @Override
  protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
    return state.getValue(PLANT).block().defaultBlockState().canSurvive(level, pos);
  }

  @Override
  protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
    return false;
  }

  @Override
  protected void randomTick(
      BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
    if (level.getBrightness(LightLayer.BLOCK, pos) > 11) {
      level.setBlockAndUpdate(pos, withLayers(state, 0));
    }
  }

  @Override
  protected ItemStack getCloneItemStack(
      LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
    return new ItemStack(state.getValue(PLANT).block());
  }
}
