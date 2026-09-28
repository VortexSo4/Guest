package com.vortexso.guest_atmosphere.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class IcicleBlock extends Block implements Fallable {
  public static final BooleanProperty TIP = BooleanProperty.create("tip");

  private static final VoxelShape TIP_SHAPE = Block.column(6.0, 3.0, 16.0);
  private static final VoxelShape BODY_SHAPE = Block.column(8.0, 0.0, 16.0);

  public IcicleBlock(BlockBehaviour.Properties properties) {
    super(properties);
    registerDefaultState(stateDefinition.any().setValue(TIP, true));
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(TIP);
  }

  @Override
  protected VoxelShape getShape(
      BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
    return state.getValue(TIP) ? TIP_SHAPE : BODY_SHAPE;
  }

  @Override
  protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
    BlockPos above = pos.above();
    BlockState support = level.getBlockState(above);
    return support.is(this) || support.isFaceSturdy(level, above, Direction.DOWN);
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
    if (directionToNeighbour == Direction.UP && !canSurvive(state, level, pos)) {
      ticks.scheduleTick(pos, this, 2);
    } else if (directionToNeighbour == Direction.DOWN) {
      return state.setValue(TIP, !neighbourState.is(this));
    }
    return state;
  }

  @Override
  protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
    if (!canSurvive(state, level, pos)) {
      fall(level, pos, state);
    }
  }

  @Override
  protected void onProjectileHit(
      Level level, BlockState state, BlockHitResult hit, Projectile projectile) {
    BlockPos pos = hit.getBlockPos();
    if (level instanceof ServerLevel server
        && projectile.mayInteract(server, pos)
        && projectile.getDeltaMovement().length() > 0.6) {
      fall(server, pos, state);
    }
  }

  public static void fall(ServerLevel level, BlockPos pos, BlockState state) {
    FallingBlockEntity falling = FallingBlockEntity.fall(level, pos, state);

    falling.setHurtsEntities(1.5F, 8);
    falling.disableDrop();
  }

  @Override
  public void onBrokenAfterFall(Level level, BlockPos pos, FallingBlockEntity entity) {
    if (!entity.isSilent()) {
      level.levelEvent(
          LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(entity.getBlockState()));
      level.playSound(null, pos, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 0.8F, 1.4F);
    }
  }

  @Override
  public DamageSource getFallDamageSource(Entity entity) {
    return entity.damageSources().fallingStalactite(entity);
  }

  @Override
  public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
    if (state.getValue(TIP) && level.isBrightOutside() && random.nextInt(6) == 0) {
      level.addParticle(
          ParticleTypes.DRIPPING_WATER,
          pos.getX() + 0.5,
          pos.getY() + 0.15,
          pos.getZ() + 0.5,
          0,
          0,
          0);
    }
  }
}
