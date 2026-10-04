package com.vortexso.guest_hands.station;

import com.vortexso.guest_hands.HandsConfig;
import com.vortexso.guest_hands.HandsHooks;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipePropertySet;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

final class FurnaceStation implements Station {
  private static final int INPUT = 0;
  private static final int FUEL = 1;
  private static final int OUTPUT = 2;
  private static final float ITEM_SCALE = 0.28F;

  private final Map<BlockEntity, Integer> mirrored = new WeakHashMap<>();

  @Override
  public String id() {
    return "furnace";
  }

  @Override
  public boolean enabled() {
    return HandsConfig.FURNACE_ENABLED.get();
  }

  @Override
  public boolean accepts(BlockState state) {
    return state.getBlock() instanceof AbstractFurnaceBlock;
  }

  @Override
  public boolean storesItems() {
    return false;
  }

  @Override
  public boolean use(
      ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player, BlockHitResult hit) {
    if (player.isSecondaryUseActive()
        || !(level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) {
      return false;
    }
    ItemStack held = player.getMainHandItem();
    if (!held.isEmpty()) {
      int slot = smeltable(level, furnace, held) ? INPUT : FUEL;
      int moved = Stations.insert(furnace, slot, held);
      if (moved == 0) {
        return false;
      }
      held.consume(moved, player);
      furnace.setChanged();
      level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.6F, 1.0F);
      sync(level, furnace);
      return true;
    }
    ItemStack output = furnace.getItem(OUTPUT);
    if (output.isEmpty()) {
      return false;
    }
    furnace.setItem(OUTPUT, ItemStack.EMPTY);

    output.onCraftedBy(player, output.getCount());
    furnace.awardUsedRecipesAndPopExperience(player);
    HandsHooks.smelted.accept(player, output);
    Stations.give(player, output);
    furnace.setChanged();
    sync(level, furnace);
    return true;
  }

  private static boolean smeltable(
      ServerLevel level, AbstractFurnaceBlockEntity furnace, ItemStack stack) {
    ResourceKey<RecipePropertySet> inputs =
        furnace instanceof BlastFurnaceBlockEntity
            ? RecipePropertySet.BLAST_FURNACE_INPUT
            : furnace instanceof SmokerBlockEntity
                ? RecipePropertySet.SMOKER_INPUT
                : RecipePropertySet.FURNACE_INPUT;
    return level.recipeAccess().propertySet(inputs).test(stack);
  }

  @Override
  public void sync(ServerLevel level, BlockEntity blockEntity) {
    if (!(blockEntity instanceof AbstractFurnaceBlockEntity furnace)) {
      return;
    }
    ItemStack input = furnace.getItem(INPUT);
    ItemStack output = furnace.getItem(OUTPUT);
    int signature = enabled() ? Objects.hash(stackHash(input), stackHash(output)) : 0;
    Integer previous = mirrored.put(furnace, signature);
    if (previous != null && previous == signature) {
      return;
    }
    BlockPos pos = furnace.getBlockPos();
    Map<String, Display> displays = StationDisplays.byRole(level, pos, id());
    if (!enabled()) {
      StationDisplays.clear(displays);
      return;
    }

    Direction front = furnace.getBlockState().getValue(AbstractFurnaceBlock.FACING);
    Vec3 right = front.getCounterClockWise().getUnitVec3();
    Vec3 top = Vec3.atCenterOf(pos).add(0.0, 0.501, 0.0);
    show(level, displays, pos, "input", input, top.subtract(right.scale(0.2)), front);
    show(level, displays, pos, "output", output, top.add(right.scale(0.2)), front);
  }

  private void show(
      ServerLevel level,
      Map<String, Display> displays,
      BlockPos pos,
      String role,
      ItemStack stack,
      Vec3 at,
      Direction front) {
    StationDisplays.setItem(
        level,
        displays,
        Stations.key(this, role, pos),
        stack,
        at,
        StationDisplays.lying(stack, ITEM_SCALE, ITEM_SCALE, front.getOpposite()));
  }

  static int stackHash(ItemStack stack) {
    return ItemStack.hashItemAndComponents(stack) * 31 + stack.getCount();
  }
}
