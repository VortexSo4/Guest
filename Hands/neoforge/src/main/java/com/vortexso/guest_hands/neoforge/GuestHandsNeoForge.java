package com.vortexso.guest_hands.neoforge;

import com.vortexso.guest_hands.GuestHands;
import com.vortexso.guest_hands.HandsHooks;
import com.vortexso.guest_hands.grapple.Grapple;
import com.vortexso.guest_hands.sleep.SleepPass;
import com.vortexso.guest_hands.station.Stations;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.player.CanContinueSleepingEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.SleepFinishedTimeEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@Mod(GuestHands.MODID)
public final class GuestHandsNeoForge {
  public GuestHandsNeoForge(IEventBus modBus) {
    GuestHands.init();
    HandsHooks.craftingPlayer = CommonHooks::setCraftingPlayer;
    HandsHooks.smelted =
        (player, stack) -> EventHooks.firePlayerSmeltedEvent(player, stack, stack.getCount());
    modBus.addListener(GuestHandsNeoForge::registerPayloads);
    NeoForge.EVENT_BUS.addListener(GuestHandsNeoForge::onLevelTick);
    NeoForge.EVENT_BUS.addListener(GuestHandsNeoForge::onSleepFinished);
    NeoForge.EVENT_BUS.addListener(GuestHandsNeoForge::onCanContinueSleeping);
    NeoForge.EVENT_BUS.addListener(GuestHandsNeoForge::onLeftClick);
    NeoForge.EVENT_BUS.addListener(GuestHandsNeoForge::onLogout);
  }

  private static void registerPayloads(RegisterPayloadHandlersEvent event) {
    event
        .registrar("1")
        .playBidirectional(
            Grapple.GrapplePayload.TYPE,
            Grapple.GrapplePayload.CODEC,
            (payload, context) -> {
              if (context.player() instanceof ServerPlayer player) {
                Grapple.handleFromClient(payload, player);
              }
            });
  }

  private static void onLevelTick(LevelTickEvent.Pre event) {
    if (event.getLevel() instanceof ServerLevel level) {
      SleepPass.levelTick(level);
    }
  }

  private static void onSleepFinished(SleepFinishedTimeEvent event) {
    if (event.getLevel() instanceof ServerLevel level && !SleepPass.allowTimeJump(level)) {
      event.setCanceled(true);
    }
  }

  private static void onCanContinueSleeping(CanContinueSleepingEvent event) {
    if (event.getEntity() instanceof ServerPlayer player && SleepPass.keepSleeping(player)) {
      event.setContinueSleeping(true);
    }
  }

  private static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
    if (event.getAction() == PlayerInteractEvent.LeftClickBlock.Action.START
        && Stations.punch(event.getEntity(), event.getLevel(), event.getPos(), event.getFace())) {
      event.setCanceled(true);
    }
  }

  private static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
    Grapple.logout(event.getEntity());
  }
}
