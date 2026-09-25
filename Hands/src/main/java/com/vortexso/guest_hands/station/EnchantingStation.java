package com.vortexso.guest_hands.station;

import com.vortexso.guest_hands.HandsConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;

/**
 * Enchanting table with the item and lapis lying on it and the three offers floating above, written
 * in the enchanting (standard galactic) font plus the vanilla clue. Offers, costs, bookshelf power
 * and the enchant itself come from a never-opened vanilla {@link EnchantmentMenu}, seeded by the
 * player who uses the table, exactly as in the screen.
 */
final class EnchantingStation implements Station {
  private static final String ITEM = "item";
  private static final String LAPIS = "lapis";
  private static final FontDescription GLYPHS =
      new FontDescription.Resource(Identifier.withDefaultNamespace("alt"));

  /** Highlighted offer per player; UI state only, losing it just resets the highlight. */
  private final Map<UUID, Integer> selected = new HashMap<>();

  @Override
  public String id() {
    return "enchanting";
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
    Map<String, Display> displays = StationDisplays.byRole(level, pos, id());
    ItemStack item = StationDisplays.item(displays, ITEM);
    ItemStack lapis = StationDisplays.item(displays, LAPIS);
    ItemStack held = player.getMainHandItem();
    if (!held.isEmpty()) {
      if (player.isSecondaryUseActive()) {
        return false;
      }
      if (held.is(Tags.Items.ENCHANTING_FUELS)) {
        if (!lapis.isEmpty() && !ItemStack.isSameItemSameComponents(lapis, held)) {
          return true;
        }
        int moved = Math.min(held.getCount(), held.getMaxStackSize() - lapis.getCount());
        lapis = held.copyWithCount(lapis.getCount() + moved);
        held.consume(moved, player);
      } else if (item.isEmpty() && held.isEnchantable()) {
        item = held.copyWithCount(1);
        held.consume(1, player);
        selected.remove(player.getUUID());
      } else {
        return false;
      }
      level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.6F, 1.4F);
      show(level, pos, player, displays, item, lapis);
      return true;
    }
    if (item.isEmpty()) {
      return false;
    }
    EnchantmentMenu menu = menu(level, pos, player, item, lapis);
    int offer = selected.getOrDefault(player.getUUID(), -1);
    if (!player.isSecondaryUseActive()) {
      selected.put(player.getUUID(), nextOffer(menu, offer));
      level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.8F, 1.0F);
      show(level, pos, player, displays, item, lapis);
      return true;
    }
    if (offer < 0 || menu.costs[offer] <= 0) {
      return true;
    }
    if (!menu.clickMenuButton(player, offer)) {
      player.sendOverlayMessage(
          Component.translatable("guest_hands.enchanting.cannot", menu.costs[offer], offer + 1));
      return true;
    }
    selected.remove(player.getUUID());
    show(level, pos, player, displays, menu.getSlot(0).getItem(), menu.getSlot(1).getItem());
    return true;
  }

  private static int nextOffer(EnchantmentMenu menu, int current) {
    for (int step = 1; step <= 3; step++) {
      int offer = (current + step + 3) % 3;
      if (menu.costs[offer] > 0) {
        return offer;
      }
    }
    return -1;
  }

  @Override
  public boolean punch(ServerLevel level, BlockPos pos, ServerPlayer player, Vec3 hit) {
    Map<String, Display> displays = StationDisplays.byRole(level, pos, id());
    ItemStack item = StationDisplays.item(displays, ITEM);
    ItemStack lapis = StationDisplays.item(displays, LAPIS);
    if (!item.isEmpty()) {
      Stations.give(player, item);
      item = ItemStack.EMPTY;
      selected.remove(player.getUUID());
    } else if (!lapis.isEmpty()) {
      Stations.give(player, lapis);
      lapis = ItemStack.EMPTY;
    } else {
      return false;
    }
    level.playSound(null, pos, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 0.6F, 1.4F);
    show(level, pos, player, displays, item, lapis);
    return true;
  }

  private static EnchantmentMenu menu(
      ServerLevel level, BlockPos pos, ServerPlayer player, ItemStack item, ItemStack lapis) {
    EnchantmentMenu menu =
        new EnchantmentMenu(0, player.getInventory(), ContainerLevelAccess.create(level, pos));
    menu.getSlot(1).set(lapis.copy());
    menu.getSlot(0).set(item.copy());
    return menu;
  }

  private void show(
      ServerLevel level,
      BlockPos pos,
      ServerPlayer player,
      Map<String, Display> displays,
      ItemStack item,
      ItemStack lapis) {
    Vec3 top = Vec3.atBottomCenterOf(pos).add(0.0, 0.751, 0.0);
    StationDisplays.setItem(level, displays, Stations.key(this, ITEM, pos), item, top, 0.4F, true);
    StationDisplays.setItem(
        level,
        displays,
        Stations.key(this, LAPIS, pos),
        lapis,
        top.add(0.3, 0.0, 0.3),
        0.22F,
        true);
    StationDisplays.setText(
        level,
        displays,
        Stations.key(this, "offers", pos),
        item.isEmpty() ? null : offers(level, menu(level, pos, player, item, lapis), player),
        top.add(0.0, 0.75, 0.0));
  }

  private MutableComponent offers(ServerLevel level, EnchantmentMenu menu, ServerPlayer player) {
    IdMap<Holder<Enchantment>> enchantments =
        level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).asHolderIdMap();
    int highlight = selected.getOrDefault(player.getUUID(), -1);
    MutableComponent text = null;
    for (int offer = 0; offer < 3; offer++) {
      if (menu.costs[offer] <= 0) {
        continue;
      }
      Holder<Enchantment> clue =
          menu.enchantClue[offer] >= 0 ? enchantments.byId(menu.enchantClue[offer]) : null;
      Component glyphs =
          clue == null
              ? Component.literal("?")
              : clue.value().description().copy().withStyle(style -> style.withFont(GLYPHS));
      Component hint =
          clue == null
              ? Component.literal("?")
              : Component.translatable(
                  "container.enchant.clue", Enchantment.getFullname(clue, menu.levelClue[offer]));
      MutableComponent line =
          Component.translatable(
              "guest_hands.enchanting.offer", glyphs, menu.costs[offer], offer + 1, hint);
      line.withStyle(offer == highlight ? ChatFormatting.YELLOW : ChatFormatting.GRAY);
      text = text == null ? line : text.append("\n").append(line);
    }
    return text == null ? Component.translatable("guest_hands.enchanting.none") : text;
  }
}
