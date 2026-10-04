package com.vortexso.guest_atmosphere.trace;

import com.vortexso.guest_atmosphere.block.AtmosphereBlocks;
import com.vortexso.guest_atmosphere.block.Coating;
import com.vortexso.guest_atmosphere.block.CoveredPlantBlock;
import com.vortexso.guest_core.platform.Events;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

public final class TraceInteractions {

  private static final int BRUSH_STROKES = 3;

  private TraceInteractions() {}

  public enum Tool {
    SHEARS,
    HOE,
    SHOVEL
  }

  public static void init() {
    Events.USE_BLOCK.register(TraceInteractions::use);
    Events.BLOCK_BREAK.register(
        (level, player, pos, state) -> {
          ChunkTraces.setFixed(level, pos, false);
          return true;
        });
  }

  private static InteractionResult use(
      Player player, Level level, InteractionHand hand, BlockHitResult hit) {
    BlockPos pos = hit.getBlockPos();
    BlockState state = level.getBlockState(pos);
    ItemStack stack = player.getItemInHand(hand);
    BlockState result = null;
    Block cover = Block.byItem(stack.getItem());
    if (state.hasProperty(CoveredPlantBlock.HALF)
        && state.getValue(CoveredPlantBlock.HALF) == DoubleBlockHalf.UPPER) {
      pos = pos.below();
      state = level.getBlockState(pos);
    }
    if (state.getBlock() instanceof CoveredPlantBlock covered && covered.cover() == cover) {
      int layers = state.getValue(SnowLayerBlock.LAYERS);
      result = layers < 8 ? state.setValue(SnowLayerBlock.LAYERS, layers + 1) : null;
    } else if (cover instanceof SnowLayerBlock) {
      result = CoveredPlantBlock.cover(state, cover, 1);
      if (result != null && !result.canSurvive(level, pos)) {
        result = null;
      }
    } else if (stack.is(Items.CLAY_BALL)) {
      Block intact = GrowthTraces.uncracked(state.getBlock());
      result = intact == null ? null : intact.withPropertiesOf(state);
    }
    if (result == null) {
      return InteractionResult.PASS;
    }
    if (!level.isClientSide()) {
      level.setBlockAndUpdate(pos, result);
      level.playSound(
          null,
          pos,
          stack.is(Items.CLAY_BALL)
              ? SoundEvents.MUD_BRICKS_PLACE
              : result.getSoundType().getPlaceSound(),
          SoundSource.BLOCKS,
          1.0F,
          1.0F);
      stack.consume(1, player);
    }
    return InteractionResult.SUCCESS;
  }

  public static @Nullable BlockState toolModified(
      Tool tool, Level level, BlockPos pos, BlockState state, boolean simulate) {
    switch (tool) {
      case SHEARS -> {
        Block clean = GrowthTraces.unmossed(state.getBlock());
        if (clean == null) {
          return null;
        }
        if (!simulate && level instanceof ServerLevel server) {
          server.playSound(null, pos, SoundEvents.SHEEP_SHEAR, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        return clean.withPropertiesOf(state);
      }
      case HOE -> {
        return state.is(AtmosphereBlocks.SILT.get()) && level.getBlockState(pos.above()).isAir()
            ? Blocks.FARMLAND.defaultBlockState()
            : null;
      }
      case SHOVEL -> {
        return Coating.uncoat(state);
      }
    }
    return null;
  }

  public static InteractionResult useTool(
      Player player, Level level, InteractionHand hand, BlockHitResult hit) {
    ItemStack stack = player.getItemInHand(hand);
    Tool tool =
        stack.is(Items.SHEARS)
            ? Tool.SHEARS
            : stack.is(ItemTags.HOES) ? Tool.HOE : stack.is(ItemTags.SHOVELS) ? Tool.SHOVEL : null;
    if (tool == null) {
      return InteractionResult.PASS;
    }
    BlockPos pos = hit.getBlockPos();
    BlockState result = toolModified(tool, level, pos, level.getBlockState(pos), false);
    if (result == null) {
      return InteractionResult.PASS;
    }
    if (!level.isClientSide()) {
      if (tool != Tool.SHEARS) {
        level.playSound(
            null,
            pos,
            tool == Tool.HOE ? SoundEvents.HOE_TILL : SoundEvents.SHOVEL_FLATTEN,
            SoundSource.BLOCKS,
            1.0F,
            1.0F);
      }
      level.setBlockAndUpdate(pos, result);
      stack.hurtAndBreak(1, player, hand.asEquipmentSlot());
    }
    return InteractionResult.SUCCESS;
  }

  public static void brushTick(LivingEntity entity, ItemStack stack, int remaining) {
    if (!(entity instanceof Player player) || !(player.level() instanceof ServerLevel level)) {
      return;
    }
    int elapsed = stack.getUseDuration(player) - remaining + 1;
    if (elapsed < BRUSH_STROKES * 10 || elapsed % 10 != 5) {
      return;
    }
    HitResult hit = player.pick(player.blockInteractionRange(), 0.0F, false);
    if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
      return;
    }
    BlockPos pos = blockHit.getBlockPos();
    BlockState state = level.getBlockState(pos);
    BlockState clean = brushed(state);
    if (clean == null) {
      return;
    }
    level.sendParticles(
        new BlockParticleOption(ParticleTypes.FALLING_DUST, state),
        pos.getX() + 0.5,
        pos.getY() + 0.5,
        pos.getZ() + 0.5,
        12,
        0.4,
        0.4,
        0.4,
        0.0);
    level.setBlockAndUpdate(pos, clean);
    stack.hurtAndBreak(
        1,
        player,
        player.getUsedItemHand() == InteractionHand.MAIN_HAND
            ? EquipmentSlot.MAINHAND
            : EquipmentSlot.OFFHAND);
  }

  private static @Nullable BlockState brushed(BlockState state) {
    BlockState uncoated = Coating.uncoat(state);
    if (uncoated != null) {
      return uncoated;
    }
    if (state.is(AtmosphereBlocks.FROSTY_GLASS.get())) {
      return Blocks.GLASS.defaultBlockState();
    }
    if (state.is(AtmosphereBlocks.FROSTY_GLASS_PANE.get())) {
      return Blocks.GLASS_PANE.withPropertiesOf(state);
    }
    if (state.is(AtmosphereBlocks.FROST.get())
        || state.is(AtmosphereBlocks.SOOT.get())
        || state.is(AtmosphereBlocks.LICHEN.get())) {
      return Blocks.AIR.defaultBlockState();
    }
    if (state.is(AtmosphereBlocks.SAND_PILE.get())
        || state.is(AtmosphereBlocks.RED_SAND_PILE.get())
        || state.is(AtmosphereBlocks.ASH.get())
        || state.is(AtmosphereBlocks.SANDY_PLANT.get())
        || state.is(AtmosphereBlocks.RED_SANDY_PLANT.get())) {
      return CoveredPlantBlock.withLayers(state, state.getValue(SnowLayerBlock.LAYERS) - 1);
    }
    return null;
  }
}
