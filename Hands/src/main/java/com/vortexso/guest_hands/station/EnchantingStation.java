package com.vortexso.guest_hands.station;

import com.vortexso.guest_hands.HandsConfig;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.EnchantingTableBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;

final class EnchantingStation implements Station {
  private static final String ID = "enchanting";
  private static final String ITEM = "item";
  private static final String LAPIS = "lapis";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public boolean enabled() {
    return HandsConfig.ENCHANTING_ENABLED.get();
  }

  @Override
  public boolean accepts(BlockState state) {
    return state.is(Blocks.ENCHANTING_TABLE);
  }

  @Override
  public boolean storesItems() {
    return true;
  }

  @Override
  public boolean use(
      ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player, BlockHitResult hit) {
    ItemStack held = player.getMainHandItem();
    if (!held.isEmpty() && player.isSecondaryUseActive()) {

      return false;
    }
    if (!held.isEmpty() && place(level, pos, player, held)) {
      return true;
    }
    return open(level, pos, player);
  }

  private static boolean place(
      ServerLevel level, BlockPos pos, ServerPlayer player, ItemStack held) {
    Map<String, Display> displays = StationDisplays.byRole(level, pos, ID);
    ItemStack item = StationDisplays.item(displays, ITEM);
    ItemStack lapis = StationDisplays.item(displays, LAPIS);
    if (held.is(Tags.Items.ENCHANTING_FUELS)
        && (lapis.isEmpty() || ItemStack.isSameItemSameComponents(lapis, held))) {
      int moved = Math.min(held.getCount(), held.getMaxStackSize() - lapis.getCount());
      if (moved <= 0) {
        return false;
      }
      lapis = held.copyWithCount(lapis.getCount() + moved);
      held.consume(moved, player);
    } else if (item.isEmpty() && held.isEnchantable()) {
      item = held.copyWithCount(1);
      held.consume(1, player);
    } else {
      return false;
    }
    level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.6F, 1.4F);
    show(level, pos, displays, item, lapis, player.getDirection());
    return true;
  }

  private static boolean open(ServerLevel level, BlockPos pos, ServerPlayer player) {
    if (!(level.getBlockEntity(pos) instanceof EnchantingTableBlockEntity table)) {
      return false;
    }
    player.openMenu(
        new SimpleMenuProvider(
            (id, inventory, opener) -> {
              Map<String, Display> displays = StationDisplays.byRole(level, pos, ID);
              TableMenu menu =
                  new TableMenu(
                      id,
                      inventory,
                      level,
                      pos,
                      StationDisplays.item(displays, ITEM),
                      StationDisplays.item(displays, LAPIS));
              StationDisplays.clear(displays);
              return menu;
            },
            table.getDisplayName()));
    return true;
  }

  @Override
  public boolean punch(ServerLevel level, BlockPos pos, ServerPlayer player, Vec3 hit) {
    Map<String, Display> displays = StationDisplays.byRole(level, pos, ID);
    ItemStack item = StationDisplays.item(displays, ITEM);
    ItemStack lapis = StationDisplays.item(displays, LAPIS);
    if (!item.isEmpty()) {
      Stations.give(player, item);
      item = ItemStack.EMPTY;
    } else if (!lapis.isEmpty()) {
      Stations.give(player, lapis);
      lapis = ItemStack.EMPTY;
    } else {
      return false;
    }
    level.playSound(null, pos, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 0.6F, 1.4F);
    show(level, pos, displays, item, lapis, player.getDirection());
    return true;
  }

  private static void show(
      ServerLevel level,
      BlockPos pos,
      Map<String, Display> displays,
      ItemStack item,
      ItemStack lapis,
      Direction away) {
    Vec3 top = Vec3.atBottomCenterOf(pos).add(0.0, 0.751, 0.0);
    StationDisplays.setItem(
        level,
        displays,
        new StationDisplays.Key(ID, ITEM, pos),
        item,
        top,
        StationDisplays.lying(item, 0.4F, 0.4F, away));
    StationDisplays.setItem(
        level,
        displays,
        new StationDisplays.Key(ID, LAPIS, pos),
        lapis,
        top.add(0.3, 0.0, 0.3),
        StationDisplays.lying(lapis, 0.22F, 0.22F, away));
  }

  static final class TableMenu extends EnchantmentMenu {
    private final ServerLevel level;
    private final BlockPos pos;

    TableMenu(
        int id,
        Inventory inventory,
        ServerLevel level,
        BlockPos pos,
        ItemStack item,
        ItemStack lapis) {
      super(id, inventory, ContainerLevelAccess.create(level, pos));
      this.level = level;
      this.pos = pos;
      getSlot(1).set(lapis.copy());
      getSlot(0).set(item.copy());
    }

    @Override
    public void removed(Player player) {
      if (level.isLoaded(pos) && level.getBlockState(pos).is(Blocks.ENCHANTING_TABLE)) {
        Map<String, Display> displays = StationDisplays.byRole(level, pos, ID);
        ItemStack item = StationDisplays.item(displays, ITEM);
        ItemStack lapis = StationDisplays.item(displays, LAPIS);
        Slot itemSlot = getSlot(0);
        if (item.isEmpty() && itemSlot.hasItem()) {
          item = itemSlot.container.removeItemNoUpdate(itemSlot.getContainerSlot());
        }
        ItemStack back = getSlot(1).getItem();
        if (!back.isEmpty()
            && (lapis.isEmpty() || ItemStack.isSameItemSameComponents(lapis, back))) {
          int moved = Math.min(back.getCount(), back.getMaxStackSize() - lapis.getCount());
          lapis = back.copyWithCount(lapis.getCount() + moved);
          back.shrink(moved);
        }
        show(level, pos, displays, item, lapis, player.getDirection());
      }
      super.removed(player);
    }
  }
}
