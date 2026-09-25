package com.vortexso.guest_hands.station;

import com.vortexso.guest_hands.HandsConfig;
import com.vortexso.guest_hands.mixin.BrewingStandAccessor;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Brewing stand. Bottles already render on the vanilla model; the ingredient is shown above the
 * stand and a label shows brewing progress and blaze fuel. The block entity stays the storage.
 */
final class BrewingStation implements Station {
  /** Vanilla brew duration in ticks (BrewingStandBlockEntity counts down from this). */
  private static final int BREW_TICKS = 400;

  private static final int INGREDIENT = 3;
  private static final int FUEL = 4;

  /** Fuel first, then ingredient, then bottles: the order vanilla's shift-click uses. */
  private static final int[] INSERT_ORDER = {FUEL, INGREDIENT, 0, 1, 2};

  private final Map<BlockEntity, Integer> mirrored = new WeakHashMap<>();

  @Override
  public String id() {
    return "brewing";
  }

  @Override
  public boolean enabled() {
    return HandsConfig.BREWING_ENABLED.get();
  }

  @Override
  public boolean accepts(BlockState state) {
    return state.is(Blocks.BREWING_STAND);
  }

  @Override
  public boolean storesItems() {
    return false;
  }

  @Override
  public boolean use(
      ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player, BlockHitResult hit) {
    if (player.isSecondaryUseActive()
        || !(level.getBlockEntity(pos) instanceof BrewingStandBlockEntity stand)) {
      return false;
    }
    ItemStack held = player.getMainHandItem();
    if (!held.isEmpty()) {
      for (int slot : INSERT_ORDER) {
        boolean bottle = slot < INGREDIENT;
        if (bottle && !stand.getItem(slot).isEmpty()) {
          continue;
        }
        int moved = Stations.insert(stand, slot, bottle ? held.copyWithCount(1) : held);
        if (moved > 0) {
          held.consume(moved, player);
          stand.setChanged();
          level.playSound(null, pos, SoundEvents.BOTTLE_FILL, SoundSource.BLOCKS, 0.5F, 1.2F);
          sync(level, stand);
          return true;
        }
      }
      return false;
    }
    if (((BrewingStandAccessor) stand).guest_hands$data().get(0) > 0) {
      return false;
    }
    boolean took = false;
    for (int slot = 0; slot < INGREDIENT; slot++) {
      ItemStack bottle = stand.getItem(slot);
      if (!bottle.isEmpty()) {
        stand.setItem(slot, ItemStack.EMPTY);
        Stations.give(player, bottle);
        took = true;
      }
    }
    if (took) {
      stand.setChanged();
      sync(level, stand);
    }
    return took;
  }

  @Override
  public void sync(ServerLevel level, BlockEntity blockEntity) {
    if (!(blockEntity instanceof BrewingStandBlockEntity stand)) {
      return;
    }
    ContainerData data = ((BrewingStandAccessor) stand).guest_hands$data();
    int brewTime = data.get(0);
    int fuel = data.get(1);
    ItemStack ingredient = stand.getItem(INGREDIENT);
    boolean shown = enabled();
    int signature =
        shown ? Objects.hash(FurnaceStation.stackHash(ingredient), brewTime / 20, fuel) : 0;
    Integer previous = mirrored.put(stand, signature);
    if (previous != null && previous == signature) {
      return;
    }
    BlockPos pos = stand.getBlockPos();
    Map<String, Display> displays = StationDisplays.byRole(level, pos, id());
    if (!shown) {
      StationDisplays.clear(displays);
      return;
    }
    Vec3 top = Vec3.atBottomCenterOf(pos).add(0.0, 0.95, 0.0);
    StationDisplays.setItem(
        level, displays, Stations.key(this, "ingredient", pos), ingredient, top, 0.3F, false);
    Component status = null;
    if (brewTime > 0) {
      int percent = (BREW_TICKS - brewTime) * 100 / BREW_TICKS;
      status = Component.translatable("guest_hands.brewing.status", percent, fuel);
    } else if (fuel > 0 || !ingredient.isEmpty()) {
      status = Component.translatable("guest_hands.brewing.idle", fuel);
    }
    StationDisplays.setText(
        level, displays, Stations.key(this, "status", pos), status, top.add(0.0, 0.5, 0.0));
  }
}
