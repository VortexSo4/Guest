package com.vortexso.guest_hands.station;

import com.vortexso.guest_hands.HandsConfig;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Display;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

final class AnvilStation implements Station {
  private static final String LEFT = "left";
  private static final String RIGHT = "right";
  private static final float ITEM_SCALE = 0.3F;

  @Override
  public String id() {
    return "anvil";
  }

  @Override
  public boolean enabled() {
    return HandsConfig.ANVIL_ENABLED.get();
  }

  @Override
  public boolean accepts(BlockState state) {
    return state.is(BlockTags.ANVIL);
  }

  @Override
  public boolean storesItems() {
    return true;
  }

  @Override
  public boolean use(
      ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player, BlockHitResult hit) {
    if (player.isSecondaryUseActive()) {
      return false;
    }
    Map<String, Display> displays = StationDisplays.byRole(level, pos, id());
    ItemStack left = StationDisplays.item(displays, LEFT);
    ItemStack right = StationDisplays.item(displays, RIGHT);
    ItemStack held = player.getMainHandItem();
    if (!held.isEmpty()) {
      if (left.isEmpty()) {
        left = held.copy();
      } else if (right.isEmpty()) {
        right = held.copy();
      } else if (ItemStack.isSameItemSameComponents(right, held)) {
        int moved = Math.min(held.getCount(), right.getMaxStackSize() - right.getCount());
        right = right.copyWithCount(right.getCount() + moved);
        held.consume(moved, player);
        show(level, pos, player, displays, left, right);
        return true;
      } else {
        return true;
      }
      held.consume(held.getCount(), player);
      level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.6F, 0.8F);
      show(level, pos, player, displays, left, right);
      return true;
    }
    if (left.isEmpty() && right.isEmpty()) {
      return false;
    }
    AnvilMenu menu = menu(level, pos, player, left, right);
    Slot result = menu.getSlot(AnvilMenu.RESULT_SLOT);
    if (!result.hasItem()) {
      return true;
    }
    if (!result.mayPickup(player)) {
      player.sendOverlayMessage(
          Component.translatable("guest_hands.anvil.need_levels", menu.getCost()));
      return true;
    }
    ItemStack crafted = result.getItem().copy();
    result.onTake(player, crafted);
    Stations.give(player, crafted);

    show(
        level,
        pos,
        player,
        displays,
        menu.getSlot(AnvilMenu.INPUT_SLOT).getItem(),
        menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).getItem());
    return true;
  }

  @Override
  public boolean punch(ServerLevel level, BlockPos pos, ServerPlayer player, Vec3 hit) {
    Map<String, Display> displays = StationDisplays.byRole(level, pos, id());
    ItemStack left = StationDisplays.item(displays, LEFT);
    ItemStack right = StationDisplays.item(displays, RIGHT);
    if (!right.isEmpty()) {
      Stations.give(player, right);
      right = ItemStack.EMPTY;
    } else if (!left.isEmpty()) {
      Stations.give(player, left);
      left = ItemStack.EMPTY;
    } else {
      return false;
    }
    level.playSound(null, pos, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 0.6F, 0.8F);
    show(level, pos, player, displays, left, right);
    return true;
  }

  private static AnvilMenu menu(
      ServerLevel level, BlockPos pos, ServerPlayer player, ItemStack left, ItemStack right) {
    AnvilMenu menu =
        new AnvilMenu(0, player.getInventory(), ContainerLevelAccess.create(level, pos));
    menu.getSlot(AnvilMenu.INPUT_SLOT).set(left.copy());
    menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(right.copy());

    if (left.has(DataComponents.CUSTOM_NAME)) {
      menu.setItemName(left.getHoverName().getString());
    }
    return menu;
  }

  private void show(
      ServerLevel level,
      BlockPos pos,
      ServerPlayer player,
      Map<String, Display> displays,
      ItemStack left,
      ItemStack right) {
    BlockState state = level.getBlockState(pos);
    Direction along =
        state.hasProperty(AnvilBlock.FACING)
            ? state.getValue(AnvilBlock.FACING).getClockWise()
            : Direction.EAST;
    Vec3 top = Vec3.atCenterOf(pos).add(0.0, 0.501, 0.0);
    Vec3 step = along.getUnitVec3().scale(0.22);
    Direction away = player.getDirection();
    StationDisplays.setItem(
        level,
        displays,
        Stations.key(this, LEFT, pos),
        left,
        top.subtract(step),
        StationDisplays.lying(left, ITEM_SCALE, ITEM_SCALE, away));
    StationDisplays.setItem(
        level,
        displays,
        Stations.key(this, RIGHT, pos),
        right,
        top.add(step),
        StationDisplays.lying(right, ITEM_SCALE, ITEM_SCALE, away));
    if (!left.isEmpty() && !right.isEmpty()) {
      AnvilMenu menu = menu(level, pos, player, left, right);
      if (menu.getSlot(AnvilMenu.RESULT_SLOT).hasItem()) {
        player.sendOverlayMessage(Component.translatable("container.repair.cost", menu.getCost()));
      } else if (menu.getCost() >= 40) {
        player.sendOverlayMessage(Component.translatable("container.repair.expensive"));
      }
    }
  }
}
