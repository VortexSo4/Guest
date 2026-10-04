package com.vortexso.guest_hands.station;

import com.vortexso.guest_hands.HandsConfig;
import com.vortexso.guest_hands.HandsHooks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

final class CraftingStation implements Station {

  private static final float CUBE_SCALE = 0.3F;

  private static final float FLAT_SCALE = (float) CraftingGrid.PITCH;

  @Override
  public String id() {
    return "crafting";
  }

  @Override
  public boolean enabled() {
    return HandsConfig.CRAFTING_ENABLED.get();
  }

  @Override
  public boolean accepts(BlockState state) {
    return state.is(Blocks.CRAFTING_TABLE);
  }

  @Override
  public boolean storesItems() {
    return true;
  }

  @Override
  public boolean claimsUse(Player player, BlockHitResult hit) {
    return hit.getDirection() == Direction.UP;
  }

  @Override
  public boolean use(
      ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player, BlockHitResult hit) {
    if (hit.getDirection() != Direction.UP) {
      return false;
    }
    Map<String, Display> displays = StationDisplays.byRole(level, pos, id());
    ItemStack[] cells = new ItemStack[9];
    boolean empty = true;
    for (int cell = 0; cell < 9; cell++) {
      cells[cell] = StationDisplays.item(displays, role(cell)).copy();
      empty &= cells[cell].isEmpty();
    }
    ItemStack held = player.getMainHandItem();
    if (!held.isEmpty()) {
      place(level, pos, player, cells, held, cellUnder(pos, hit.getLocation()));
    } else if (empty) {
      return false;
    } else {
      int max = player.isSecondaryUseActive() ? HandsConfig.MAX_BATCH_CRAFTS.get() : 1;

      craft(level, pos, player, cells, max);
    }
    layout(level, pos, player, displays, cells);
    return true;
  }

  private static void place(
      ServerLevel level,
      BlockPos pos,
      ServerPlayer player,
      ItemStack[] cells,
      ItemStack held,
      int cell) {
    ItemStack current = cells[cell];
    int room;
    if (current.isEmpty()) {
      room = held.getMaxStackSize();
    } else if (ItemStack.isSameItemSameComponents(current, held)) {
      room = current.getMaxStackSize() - current.getCount();
    } else {
      return;
    }
    int moved = Math.min(player.isSecondaryUseActive() ? held.getCount() : 1, room);
    if (moved <= 0) {
      return;
    }
    cells[cell] =
        current.isEmpty()
            ? held.copyWithCount(moved)
            : current.copyWithCount(current.getCount() + moved);
    held.consume(moved, player);
    level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.6F, 1.2F);
  }

  private static void craft(
      ServerLevel level, BlockPos pos, ServerPlayer player, ItemStack[] cells, int max) {
    int facing = player.getDirection().get2DDataValue();
    List<ItemStack> grid = new ArrayList<>(9);
    for (int index = 0; index < 9; index++) {
      grid.add(cells[CraftingGrid.worldCell(facing, index)]);
    }
    List<ItemStack> results = new ArrayList<>();
    List<ItemStack> leftovers = new ArrayList<>();
    int crafted = 0;
    while (crafted < max) {
      CraftingInput.Positioned positioned = CraftingInput.ofPositioned(3, 3, grid);
      CraftingInput input = positioned.input();
      Optional<RecipeHolder<CraftingRecipe>> recipe =
          level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level);
      if (recipe.isEmpty()) {
        break;
      }
      ItemStack result = recipe.get().value().assemble(input);
      if (result.isEmpty() || !result.isItemEnabled(level.enabledFeatures())) {
        break;
      }
      HandsHooks.craftingPlayer.accept(player);
      NonNullList<ItemStack> remaining = recipe.get().value().getRemainingItems(input);
      HandsHooks.craftingPlayer.accept(null);
      player.triggerRecipeCrafted(recipe.get(), input.items());
      player.awardRecipes(List.of(recipe.get()));
      for (int y = 0; y < input.height(); y++) {
        for (int x = 0; x < input.width(); x++) {
          int index = x + positioned.left() + (y + positioned.top()) * 3;
          ItemStack stack = grid.get(index);
          stack.shrink(1);
          ItemStack replacement = remaining.get(x + y * input.width());
          if (replacement.isEmpty()) {
            continue;
          }
          if (stack.isEmpty()) {
            grid.set(index, replacement);
          } else if (ItemStack.isSameItemSameComponents(stack, replacement)) {
            stack.grow(replacement.getCount());
          } else {
            leftovers.add(replacement);
          }
        }
      }
      result.onCraftedBy(player, result.getCount());
      merge(results, result);
      crafted++;
    }
    if (crafted == 0) {
      return;
    }
    for (int index = 0; index < 9; index++) {
      cells[CraftingGrid.worldCell(facing, index)] = grid.get(index);
    }
    results.forEach(stack -> Stations.dropOnTop(level, pos, 1.05, stack));
    leftovers.forEach(stack -> Stations.dropOnTop(level, pos, 1.05, stack));
    level.playSound(null, pos, SoundEvents.WOOD_HIT, SoundSource.BLOCKS, 0.8F, 1.0F);
  }

  private static void merge(List<ItemStack> stacks, ItemStack stack) {
    for (ItemStack existing : stacks) {
      if (ItemStack.isSameItemSameComponents(existing, stack)
          && existing.getCount() < existing.getMaxStackSize()) {
        int moved = Math.min(stack.getCount(), existing.getMaxStackSize() - existing.getCount());
        existing.grow(moved);
        stack.shrink(moved);
        if (stack.isEmpty()) {
          return;
        }
      }
    }
    stacks.add(stack);
  }

  @Override
  public boolean punchFace(Direction face) {
    return face == Direction.UP;
  }

  @Override
  public boolean punch(ServerLevel level, BlockPos pos, ServerPlayer player, Vec3 hit) {
    Map<String, Display> displays = StationDisplays.byRole(level, pos, id());
    int cell = cellUnder(pos, hit);
    ItemStack stack = StationDisplays.item(displays, role(cell));
    if (stack.isEmpty()) {
      return false;
    }
    ItemStack[] cells = new ItemStack[9];
    for (int index = 0; index < 9; index++) {
      cells[index] = index == cell ? ItemStack.EMPTY : StationDisplays.item(displays, role(index));
    }
    layout(level, pos, player, displays, cells);
    Stations.give(player, stack);
    level.playSound(null, pos, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 0.6F, 1.2F);
    return true;
  }

  private static int cellUnder(BlockPos pos, Vec3 hit) {
    return CraftingGrid.cellAt(hit.x - pos.getX(), hit.z - pos.getZ());
  }

  private void layout(
      ServerLevel level,
      BlockPos pos,
      ServerPlayer player,
      Map<String, Display> displays,
      ItemStack[] cells) {
    Direction away = player.getDirection();
    for (int cell = 0; cell < 9; cell++) {
      Vec3 at =
          new Vec3(
              pos.getX() + CraftingGrid.cellCenter(cell % 3),
              pos.getY() + 1.001,
              pos.getZ() + CraftingGrid.cellCenter(cell / 3));
      StationDisplays.setItem(
          level,
          displays,
          Stations.key(this, role(cell), pos),
          cells[cell],
          at,
          StationDisplays.lying(cells[cell], CUBE_SCALE, FLAT_SCALE, away));
    }
  }

  private static String role(int cell) {
    return "cell" + cell;
  }
}
