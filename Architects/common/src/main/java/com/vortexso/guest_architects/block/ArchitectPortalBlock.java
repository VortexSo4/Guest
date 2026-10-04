package com.vortexso.guest_architects.block;

import com.mojang.serialization.MapCodec;
import com.vortexso.guest_architects.city.Escalation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class ArchitectPortalBlock extends BaseEntityBlock {
  public static final MapCodec<ArchitectPortalBlock> CODEC = simpleCodec(ArchitectPortalBlock::new);
  private static final VoxelShape SHAPE = Block.column(16.0, 6.0, 12.0);
  private static final int LIFETIME = 100;

  public ArchitectPortalBlock(BlockBehaviour.Properties properties) {
    super(properties);
  }

  @Override
  protected MapCodec<ArchitectPortalBlock> codec() {
    return CODEC;
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
    return new ArchitectPortalBlockEntity(pos, state);
  }

  @Override
  protected VoxelShape getShape(
      BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
    return SHAPE;
  }

  @Override
  protected VoxelShape getEntityInsideCollisionShape(
      BlockState state, BlockGetter level, BlockPos pos, Entity entity) {
    return state.getShape(level, pos);
  }

  @Override
  protected void onPlace(
      BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
    level.scheduleTick(pos, this, LIFETIME);
  }

  @Override
  protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
    level.removeBlock(pos, false);
    level.sendParticles(
        ParticleTypes.REVERSE_PORTAL,
        pos.getX() + 0.5,
        pos.getY() + 0.5,
        pos.getZ() + 0.5,
        8,
        0.4,
        0.1,
        0.4,
        0.01);
  }

  @Override
  protected void entityInside(
      BlockState state,
      Level level,
      BlockPos pos,
      Entity entity,
      InsideBlockEffectApplier effectApplier,
      boolean isPrecise) {
    if (entity instanceof ServerPlayer player) {
      Escalation.enterPortal(player);
    }
  }

  @Override
  public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
    level.addParticle(
        ParticleTypes.REVERSE_PORTAL,
        pos.getX() + random.nextDouble(),
        pos.getY() + 0.8,
        pos.getZ() + random.nextDouble(),
        0.0,
        0.02,
        0.0);
  }

  @Override
  protected ItemStack getCloneItemStack(
      LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
    return ItemStack.EMPTY;
  }

  @Override
  protected boolean canBeReplaced(BlockState state, Fluid fluid) {
    return false;
  }

  @Override
  protected RenderShape getRenderShape(BlockState state) {
    return RenderShape.INVISIBLE;
  }
}
