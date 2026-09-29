package com.vortexso.guest_atmosphere.block;

import com.vortexso.guest_atmosphere.AtmosphereConfig;
import com.vortexso.guest_atmosphere.trace.ChunkTraces;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class TraceBlocks {
  private TraceBlocks() {}

  public static class LayerBlock extends SnowLayerBlock {
    public LayerBlock(BlockBehaviour.Properties properties) {
      super(properties);
    }

    @Override
    protected void randomTick(
        BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {}
  }

  public static class ThinIceBlock extends IceBlock {
    public static final IntegerProperty CRACKS = IntegerProperty.create("cracks", 0, 2);
    private static final VoxelShape SHAPE = Block.box(0.0, 12.0, 0.0, 16.0, 16.0, 16.0);

    public ThinIceBlock(BlockBehaviour.Properties properties) {
      super(properties);
      registerDefaultState(stateDefinition.any().setValue(CRACKS, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
      builder.add(CRACKS);
    }

    @Override
    protected VoxelShape getShape(
        BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
      return SHAPE;
    }

    @Override
    protected boolean useShapeForLightOcclusion(BlockState state) {
      return true;
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
      return Fluids.WATER.getSource(false);
    }

    @Override
    public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
      if (!level.isClientSide()
          && heavy(level, pos, entity)
          && !entity.isShiftKeyDown()
          && (entity.getX() != entity.xo || entity.getZ() != entity.zo)
          && level.getRandom().nextInt(20) == 0) {
        crack(level, pos, state);
      }
      super.stepOn(level, pos, state, entity);
    }

    @Override
    public void fallOn(
        Level level, BlockState state, BlockPos pos, Entity entity, double fallDistance) {
      if (!level.isClientSide() && heavy(level, pos, entity) && fallDistance > 1.5) {
        shatter(level, pos);
      }
      super.fallOn(level, state, pos, entity, fallDistance);
    }

    private static boolean heavy(Level level, BlockPos pos, Entity entity) {
      return AtmosphereConfig.THIN_ICE_CRACKS.get()
          && !ChunkTraces.isFixed(level, pos)
          && entity instanceof LivingEntity
          && entity.getBbWidth() * entity.getBbWidth() * entity.getBbHeight() > 0.5F;
    }

    private static void crack(Level level, BlockPos pos, BlockState state) {
      int cracks = state.getValue(CRACKS);
      if (cracks >= 2) {
        shatter(level, pos);
        return;
      }
      level.setBlockAndUpdate(pos, state.setValue(CRACKS, cracks + 1));
      level.playSound(null, pos, SoundEvents.GLASS_HIT, SoundSource.BLOCKS, 1.0F, 0.6F);
    }

    private static void shatter(Level level, BlockPos pos) {
      level.levelEvent(
          LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(level.getBlockState(pos)));
      level.playSound(null, pos, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 1.0F, 1.2F);
      level.setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState());
    }
  }

  public static class CharredLogBlock extends RotatedPillarBlock {
    public static final BooleanProperty SMOLDERING = BooleanProperty.create("smoldering");

    public CharredLogBlock(BlockBehaviour.Properties properties) {
      super(properties);
      registerDefaultState(defaultBlockState().setValue(SMOLDERING, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
      super.createBlockStateDefinition(builder);
      builder.add(SMOLDERING);
    }

    @Override
    protected void onPlace(
        BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
      if (state.getValue(SMOLDERING) && !level.isClientSide()) {
        level.scheduleTick(pos, this, 1_200 + level.getRandom().nextInt(2_400));
      }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
      if (state.getValue(SMOLDERING)) {
        level.setBlockAndUpdate(pos, state.setValue(SMOLDERING, false));
      }
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
      if (state.getValue(SMOLDERING)) {
        if (random.nextInt(3) == 0) {
          level.addParticle(
              ParticleTypes.SMOKE,
              pos.getX() + random.nextDouble(),
              pos.getY() + random.nextDouble(),
              pos.getZ() + random.nextDouble(),
              0.0,
              0.03,
              0.0);
        }
        if (random.nextInt(16) == 0) {
          level.addParticle(
              ParticleTypes.SMALL_FLAME,
              pos.getX() + random.nextDouble(),
              pos.getY() + random.nextDouble(),
              pos.getZ() + random.nextDouble(),
              0.0,
              0.0,
              0.0);
        }
        if (random.nextInt(40) == 0) {
          level.playLocalSound(
              pos, SoundEvents.FIRE_AMBIENT, SoundSource.BLOCKS, 0.3F, 0.8F, false);
        }
      } else {
        dropAsh(level, pos, random, 40);
      }
    }
  }

  public static class BurntLeavesBlock extends Block {
    public BurntLeavesBlock(BlockBehaviour.Properties properties) {
      super(properties);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
      dropAsh(level, pos, random, 20);
    }
  }

  private static void dropAsh(Level level, BlockPos pos, RandomSource random, int rarity) {
    if (random.nextInt(rarity) == 0 && FallingBlock.isFree(level.getBlockState(pos.below()))) {
      level.addParticle(
          ParticleTypes.ASH,
          pos.getX() + random.nextDouble(),
          pos.getY() - 0.05,
          pos.getZ() + random.nextDouble(),
          0.0,
          0.0,
          0.0);
    }
  }
}
