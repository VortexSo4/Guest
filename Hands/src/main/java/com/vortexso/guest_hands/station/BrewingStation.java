package com.vortexso.guest_hands.station;

import com.vortexso.guest_hands.HandsConfig;
import com.vortexso.guest_hands.mixin.BrewingStandAccessor;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

final class BrewingStation implements Station {
  private static final int INGREDIENT = 3;

  private static final int[] INSERT_ORDER = {4, INGREDIENT, 0, 1, 2};

  private static final double[][] BOTTLES = {
    {13.5, 5.5, 8.0, 0.0}, {3.82, 5.5, 3.82, -45.0}, {3.82, 5.5, 12.18, 45.0}
  };

  private static final float BOTTLE_SCALE = 0.55F;

  private static final double MOUTH = 6.0 * BOTTLE_SCALE / 16.0;

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
    if (brewTime(stand) > 0) {
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

  private static int brewTime(BrewingStandBlockEntity stand) {
    return ((BrewingStandAccessor) stand)
        .guest_hands$data()
        .get(BrewingStandBlockEntity.DATA_BREW_TIME);
  }

  @Override
  public void sync(ServerLevel level, BlockEntity blockEntity) {
    if (!(blockEntity instanceof BrewingStandBlockEntity stand)) {
      return;
    }
    BlockPos pos = stand.getBlockPos();
    boolean enabled = enabled();
    ItemStack ingredient = enabled ? stand.getItem(INGREDIENT) : ItemStack.EMPTY;
    if (enabled && brewTime(stand) > 0) {
      bubble(level, stand);
    }
    int signature =
        Objects.hash(
            FurnaceStation.stackHash(stand.getItem(0)),
            FurnaceStation.stackHash(stand.getItem(1)),
            FurnaceStation.stackHash(stand.getItem(2)),
            FurnaceStation.stackHash(ingredient));
    Integer previous = mirrored.put(stand, signature);
    if (previous != null && previous == signature) {
      return;
    }
    Map<String, Display> displays = StationDisplays.byRole(level, pos, id());
    for (int slot = 0; slot < BOTTLES.length; slot++) {
      StationDisplays.setItem(
          level,
          displays,
          Stations.key(this, "bottle" + slot, pos),
          stand.getItem(slot),
          bottleCentre(pos, slot),
          StationDisplays.upright(BOTTLE_SCALE, (float) BOTTLES[slot][3]));
    }
    StationDisplays.setItem(
        level,
        displays,
        Stations.key(this, "ingredient", pos),
        ingredient,
        Vec3.atBottomCenterOf(pos).add(0.0, 0.95, 0.0),
        StationDisplays.upright(0.3F, 0.0F));
  }

  private static Vec3 bottleCentre(BlockPos pos, int slot) {
    double[] bottle = BOTTLES[slot];
    return Vec3.atLowerCornerOf(pos).add(bottle[0] / 16.0, bottle[1] / 16.0, bottle[2] / 16.0);
  }

  private static void bubble(ServerLevel level, BrewingStandBlockEntity stand) {
    ItemStack ingredient = stand.getItem(INGREDIENT);
    for (int slot = 0; slot < BOTTLES.length; slot++) {
      if (!level.potionBrewing().hasMix(stand.getItem(slot), ingredient)) {
        continue;
      }
      Vec3 mouth = bottleCentre(stand.getBlockPos(), slot).add(0.0, MOUTH, 0.0);
      level.sendParticles(
          ParticleTypes.BUBBLE_POP, mouth.x, mouth.y, mouth.z, 1, 0.02, 0.01, 0.02, 0.0);
    }
  }
}
