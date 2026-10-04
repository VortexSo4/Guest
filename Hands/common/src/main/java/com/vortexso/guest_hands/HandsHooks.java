package com.vortexso.guest_hands;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class HandsHooks {
  public static Consumer<Player> craftingPlayer = player -> {};
  public static BiConsumer<Player, ItemStack> smelted = (player, stack) -> {};

  private HandsHooks() {}
}
