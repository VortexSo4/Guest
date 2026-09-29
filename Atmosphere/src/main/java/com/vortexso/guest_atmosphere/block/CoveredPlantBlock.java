package com.vortexso.guest_atmosphere.block;

import com.vortexso.guest_atmosphere.trace.ChunkTraces;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

public class CoveredPlantBlock extends SnowLayerBlock {
  public static final EnumProperty<Plant> PLANT = EnumProperty.create("plant", Plant.class);
  public static final EnumProperty<DoubleBlockHalf> HALF = DoublePlantBlock.HALF;

  private static final VoxelShape UPPER_SHAPE = Block.column(12.0, 0.0, 13.0);

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
    TORCHFLOWER(Blocks.TORCHFLOWER),
    TALL_GRASS(Blocks.TALL_GRASS),
    LARGE_FERN(Blocks.LARGE_FERN),
    SUNFLOWER(Blocks.SUNFLOWER),
    LILAC(Blocks.LILAC),
    ROSE_BUSH(Blocks.ROSE_BUSH),
    PEONY(Blocks.PEONY),
    PITCHER_PLANT(Blocks.PITCHER_PLANT);

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

    public boolean tall() {
      return block instanceof DoublePlantBlock;
    }

    public static @Nullable Plant of(BlockState state) {
      if (state.is(AtmosphereBlocks.FROSTY_GRASS.get())) {
        return SHORT_GRASS;
      }
      if (state.is(AtmosphereBlocks.FROSTY_FERN.get())) {
        return FERN;
      }
      if (state.hasProperty(HALF) && state.getValue(HALF) == DoubleBlockHalf.UPPER) {
        return null;
      }
      return BY_BLOCK.get(state.getBlock());
    }

    @Override
    public String getSerializedName() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  private final Supplier<? extends Block> cover;

  public CoveredPlantBlock(BlockBehaviour.Properties properties, Supplier<? extends Block> cover) {
    super(properties);
    this.cover = cover;
    registerDefaultState(
        stateDefinition
            .any()
            .setValue(LAYERS, 1)
            .setValue(PLANT, Plant.SHORT_GRASS)
            .setValue(HALF, DoubleBlockHalf.LOWER));
  }

  public Block cover() {
    return cover.get();
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    super.createBlockStateDefinition(builder);
    builder.add(PLANT, HALF);
  }

  public static @Nullable BlockState cover(BlockState plant, Block cover, int layers) {
    Plant type = Plant.of(plant);
    if (type == null) {
      return null;
    }
    for (CoveredPlantBlock block : AtmosphereBlocks.coveredPlants()) {
      if (block.cover() == cover) {
        return block
            .defaultBlockState()
            .setValue(PLANT, type)
            .setValue(LAYERS, Math.max(1, Math.min(8, layers)));
      }
    }
    return null;
  }

  public static BlockState withLayers(BlockState state, int layers) {
    if (layers > 0) {
      return state.setValue(LAYERS, Math.min(8, layers));
    }
    return state.getBlock() instanceof CoveredPlantBlock
        ? state.getValue(PLANT).block().defaultBlockState()
        : Blocks.AIR.defaultBlockState();
  }

  private static boolean upper(BlockState state) {
    return state.getValue(HALF) == DoubleBlockHalf.UPPER;
  }

  @Override
  protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
    if (upper(state)) {
      BlockState below = level.getBlockState(pos.below());
      return below.is(this) && !upper(below) && below.getValue(PLANT) == state.getValue(PLANT);
    }
    return state.getValue(PLANT).block().defaultBlockState().canSurvive(level, pos);
  }

  @Override
  protected void onPlace(
      BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
    Plant plant = state.getValue(PLANT);
    if (plant.tall() && !upper(state) && !level.isClientSide()) {
      BlockState above = level.getBlockState(pos.above());
      if (above.is(plant.block()) && above.getValue(HALF) == DoubleBlockHalf.UPPER) {
        level.setBlock(
            pos.above(),
            state.setValue(HALF, DoubleBlockHalf.UPPER).setValue(LAYERS, 1),
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
      }
    }
  }

  @Override
  protected BlockState updateShape(
      BlockState state,
      LevelReader level,
      ScheduledTickAccess ticks,
      BlockPos pos,
      Direction directionToNeighbour,
      BlockPos neighbourPos,
      BlockState neighbourState,
      RandomSource random) {
    Plant plant = state.getValue(PLANT);
    if (upper(state)) {
      if (directionToNeighbour != Direction.DOWN) {
        return state;
      }
      if (neighbourState.is(plant.block())) {
        return plant.block().defaultBlockState().setValue(HALF, DoubleBlockHalf.UPPER);
      }
      return canSurvive(state, level, pos) ? state : Blocks.AIR.defaultBlockState();
    }
    if (plant.tall()
        && directionToNeighbour == Direction.UP
        && !(neighbourState.is(this) && upper(neighbourState))) {
      return Blocks.AIR.defaultBlockState();
    }
    return super.updateShape(
        state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random);
  }

  @Override
  public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
    if (upper(state) && !level.isClientSide() && player.preventsBlockDrops()) {
      level.setBlock(
          pos.below(),
          Blocks.AIR.defaultBlockState(),
          Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
    }
    return super.playerWillDestroy(level, pos, state, player);
  }

  @Override
  protected VoxelShape getShape(
      BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
    return upper(state) ? UPPER_SHAPE : super.getShape(state, level, pos, context);
  }

  @Override
  protected VoxelShape getCollisionShape(
      BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
    return upper(state) ? Shapes.empty() : super.getCollisionShape(state, level, pos, context);
  }

  @Override
  protected VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
    return upper(state) ? Shapes.empty() : super.getBlockSupportShape(state, level, pos);
  }

  @Override
  protected VoxelShape getVisualShape(
      BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
    return upper(state) ? Shapes.empty() : super.getVisualShape(state, level, pos, context);
  }

  @Override
  protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
    return false;
  }

  @Override
  protected boolean isRandomlyTicking(BlockState state) {
    return cover() == Blocks.SNOW && !upper(state);
  }

  @Override
  protected void randomTick(
      BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
    if (level.getBrightness(LightLayer.BLOCK, pos) > 11 && !ChunkTraces.isFixed(level, pos)) {
      level.setBlockAndUpdate(pos, withLayers(state, 0));
    }
  }

  @Override
  protected ItemStack getCloneItemStack(
      LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
    return new ItemStack(state.getValue(PLANT).block());
  }
}
