package com.vortexso.guest_atmosphere.block;

import com.vortexso.guest_atmosphere.block.Coating.Coat;
import com.vortexso.guest_atmosphere.trace.ChunkTraces;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

public class FixationWandItem extends Item {
  public FixationWandItem(Item.Properties properties) {
    super(properties);
  }

  @Override
  public InteractionResult useOn(UseOnContext context) {
    Level level = context.getLevel();
    if (level.isClientSide()) {
      return InteractionResult.SUCCESS;
    }
    BlockPos pos = context.getClickedPos();
    Player player = context.getPlayer();
    ItemStack material =
        player == null
            ? ItemStack.EMPTY
            : player.getItemInHand(
                context.getHand() == InteractionHand.MAIN_HAND
                    ? InteractionHand.OFF_HAND
                    : InteractionHand.MAIN_HAND);
    BlockState coated = coated(level.getBlockState(pos), material);
    boolean fixed = coated != null || !ChunkTraces.isFixed(level, pos);
    if (coated != null) {
      level.setBlockAndUpdate(pos, coated);
      level.playSound(
          null, pos, coated.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 1.0F);
      material.consume(1, player);
    }
    ChunkTraces.setFixed(level, pos, fixed);
    level.levelEvent(
        null, fixed ? LevelEvent.PARTICLES_AND_SOUND_WAX_ON : LevelEvent.PARTICLES_WAX_OFF, pos, 0);
    level.playSound(
        null,
        pos,
        SoundEvents.AMETHYST_BLOCK_CHIME,
        SoundSource.PLAYERS,
        1.0F,
        fixed ? 1.4F : 0.7F);
    if (player != null) {
      player.sendOverlayMessage(
          Component.translatable(
              fixed
                  ? "item.guest_atmosphere.fixation_wand.fixed"
                  : "item.guest_atmosphere.fixation_wand.released"));
    }
    return InteractionResult.SUCCESS;
  }

  private static @Nullable BlockState coated(BlockState state, ItemStack material) {
    Block cover = material.is(Items.SNOWBALL) ? Blocks.SNOW : Block.byItem(material.getItem());
    if (cover != Blocks.SNOW
        && cover != AtmosphereBlocks.SAND_PILE.get()
        && cover != AtmosphereBlocks.RED_SAND_PILE.get()) {
      return null;
    }
    BlockState coated = Coating.coat(state, cover == Blocks.SNOW ? Coat.SNOWY : Coat.SANDY);
    return coated != null ? coated : CoveredPlantBlock.cover(state, cover, 1);
  }

  @Override
  public void appendHoverText(
      ItemStack stack,
      Item.TooltipContext context,
      TooltipDisplay display,
      Consumer<Component> builder,
      TooltipFlag flag) {
    builder.accept(
        Component.translatable("item.guest_atmosphere.fixation_wand.tooltip.fix")
            .withStyle(ChatFormatting.GRAY));
    builder.accept(
        Component.translatable("item.guest_atmosphere.fixation_wand.tooltip.coat")
            .withStyle(ChatFormatting.GRAY));
  }
}
