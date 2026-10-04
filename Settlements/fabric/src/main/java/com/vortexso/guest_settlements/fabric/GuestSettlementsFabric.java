package com.vortexso.guest_settlements.fabric;

import com.vortexso.guest_settlements.GuestSettlements;
import com.vortexso.guest_settlements.life.Rites;
import com.vortexso.guest_settlements.memory.VillageMemory;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.world.InteractionResult;

public final class GuestSettlementsFabric implements ModInitializer {
  @Override
  public void onInitialize() {
    GuestSettlements.init();
    ServerLivingEntityEvents.MOB_CONVERSION.register(
        (previous, converted, params) -> VillageMemory.onConversion(previous, converted));
    UseEntityCallback.EVENT.register(
        (player, level, hand, entity, hit) -> {
          VillageMemory.onInteract(player, entity, player.getItemInHand(hand));
          return InteractionResult.PASS;
        });
    UseBlockCallback.EVENT.register(
        (player, level, hand, hit) -> {
          Rites.onRightClick(player, level, hit.getBlockPos(), player.getItemInHand(hand));
          return InteractionResult.PASS;
        });
    PlayerBlockBreakEvents.AFTER.register(
        (level, player, pos, state, blockEntity) ->
            VillageMemory.onBreak(level, player, pos, state));
  }
}
