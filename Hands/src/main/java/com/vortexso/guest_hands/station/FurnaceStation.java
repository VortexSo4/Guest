package com.vortexso.guest_hands.station;

import com.vortexso.guest_hands.HandsConfig;
import com.vortexso.guest_hands.mixin.FurnaceAccessor;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.inventory.ContainerData;
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
import net.neoforged.neoforge.event.EventHooks;

/**
 * Furnace, smoker and blast furnace. The block entity stays the only storage; displays on top
 * mirror input/fuel/output and a label shows progress and remaining burn time.
 */
final class FurnaceStation implements Station {
  private static final int INPUT = 0;
  private static final int FUEL = 1;
  private static final int OUTPUT = 2;
  private static final String[] ROLES = {"input", "fuel", "output"};

  /** Last mirrored state per furnace; a disposable cache that only skips redundant entity work. */
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
    // Same bookkeeping as vanilla's furnace result slot: stats, recipe XP orbs, smelt event.
    output.onCraftedBy(player, output.getCount());
    furnace.awardUsedRecipesAndPopExperience(player);
    EventHooks.firePlayerSmeltedEvent(player, output, output.getCount());
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
    ContainerData data = ((FurnaceAccessor) furnace).guest_hands$data();
    int burnSeconds = (data.get(0) + 19) / 20;
    int cookTotal = data.get(3);
    int percent = cookTotal > 0 ? data.get(2) * 100 / cookTotal : 0;
    boolean shown = enabled();
    int signature =
        shown
            ? Objects.hash(
                stackHash(furnace.getItem(INPUT)),
                stackHash(furnace.getItem(FUEL)),
                stackHash(furnace.getItem(OUTPUT)),
                burnSeconds,
                percent)
            : 0;
    Integer previous = mirrored.put(furnace, signature);
    if (previous != null && previous == signature) {
      return;
    }
    BlockPos pos = furnace.getBlockPos();
    Map<String, Display> displays = StationDisplays.byRole(level, pos, id());
    if (!shown) {
      StationDisplays.clear(displays);
      return;
    }
    Direction facing = furnace.getBlockState().getValue(AbstractFurnaceBlock.FACING);
    Vec3 right = facing.getCounterClockWise().getUnitVec3();
    Vec3 top = Vec3.atCenterOf(pos).add(0.0, 0.501, 0.0);
    for (int slot = INPUT; slot <= OUTPUT; slot++) {
      StationDisplays.setItem(
          level,
          displays,
          Stations.key(this, ROLES[slot], pos),
          furnace.getItem(slot),
          top.add(right.scale((slot - 1) * 0.3)),
          0.28F,
          true);
    }
    boolean working = burnSeconds > 0 || data.get(2) > 0;
    StationDisplays.setText(
        level,
        displays,
        Stations.key(this, "status", pos),
        working ? Component.translatable("guest_hands.furnace.status", percent, burnSeconds) : null,
        top.add(0.0, 0.45, 0.0));
  }

  static int stackHash(ItemStack stack) {
    return ItemStack.hashItemAndComponents(stack) * 31 + stack.getCount();
  }
}
