package com.vortexso.guest_atmosphere.trace;

import com.vortexso.guest_atmosphere.GuestAtmosphere;
import com.vortexso.guest_atmosphere.block.AtmosphereBlocks;
import com.vortexso.guest_atmosphere.block.Coating;
import com.vortexso.guest_atmosphere.block.SnowyPlantBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.jspecify.annotations.Nullable;

@EventBusSubscriber(modid = GuestAtmosphere.MODID)
public final class TraceInteractions {

  private static final int BRUSH_STROKES = 3;

  private TraceInteractions() {}

  @SubscribeEvent
  public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
    Level level = event.getLevel();
    BlockPos pos = event.getPos();
    BlockState state = level.getBlockState(pos);
    ItemStack stack = event.getItemStack();
    BlockState result = null;
    if (stack.is(Items.SNOW)) {
      if (state.is(AtmosphereBlocks.SNOWY_PLANT.get())) {
        int layers = state.getValue(SnowLayerBlock.LAYERS);
        result = layers < 8 ? state.setValue(SnowLayerBlock.LAYERS, layers + 1) : null;
      } else {
        result = SnowyPlantBlock.cover(state, 1);
      }
      if (result != null && !result.canSurvive(level, pos)) {
        result = null;
      }
    } else if (stack.is(Items.CLAY_BALL)) {
      Block intact = GrowthTraces.uncracked(state.getBlock());
      result = intact == null ? null : intact.withPropertiesOf(state);
    }
    if (result == null) {
      return;
    }
    if (!level.isClientSide()) {
      level.setBlockAndUpdate(pos, result);
      level.playSound(
          null,
          pos,
          stack.is(Items.SNOW) ? SoundEvents.SNOW_PLACE : SoundEvents.MUD_BRICKS_PLACE,
          SoundSource.BLOCKS,
          1.0F,
          1.0F);
      stack.consume(1, event.getEntity());
    }
    event.setCanceled(true);
    event.setCancellationResult(InteractionResult.SUCCESS);
  }

  @SubscribeEvent
  public static void onToolUse(BlockEvent.BlockToolModificationEvent event) {
    BlockState state = event.getState();
    if (event.getItemAbility() == ItemAbilities.SHEARS_TRIM) {
      Block clean = GrowthTraces.unmossed(state.getBlock());
      if (clean != null) {
        event.setFinalState(clean.withPropertiesOf(state));
        if (!event.isSimulated() && event.getLevel() instanceof ServerLevel level) {
          level.playSound(
              null, event.getPos(), SoundEvents.SHEEP_SHEAR, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
      }
    } else if (event.getItemAbility() == ItemAbilities.HOE_TILL
        && state.is(AtmosphereBlocks.SILT.get())
        && event.getLevel().getBlockState(event.getPos().above()).isAir()) {
      event.setFinalState(Blocks.FARMLAND.defaultBlockState());
    }
  }

  @SubscribeEvent
  public static void onUseTick(LivingEntityUseItemEvent.Tick event) {
    if (!(event.getEntity() instanceof Player player)
        || !(player.level() instanceof ServerLevel level)
        || !event.getItem().canPerformAction(ItemAbilities.BRUSH_BRUSH)) {
      return;
    }
    int elapsed = event.getItem().getUseDuration(player) - event.getDuration() + 1;
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
    event
        .getItem()
        .hurtAndBreak(
            1,
            player,
            player.getUsedItemHand() == net.minecraft.world.InteractionHand.MAIN_HAND
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
        || state.is(AtmosphereBlocks.ASH.get())) {
      int layers = state.getValue(SnowLayerBlock.LAYERS);
      return layers <= 1
          ? Blocks.AIR.defaultBlockState()
          : state.setValue(SnowLayerBlock.LAYERS, layers - 1);
    }
    return null;
  }
}
