package com.vortexso.guest_wilds.neoforge;

import com.vortexso.guest_wilds.GuestWilds;
import com.vortexso.guest_wilds.WildsEvents;
import net.minecraft.world.entity.Mob;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;

@Mod(GuestWilds.MODID)
public final class GuestWildsNeoForge {
  public GuestWildsNeoForge() {
    GuestWilds.spawnReason = Mob::getSpawnType;
    GuestWilds.init();
    NeoForge.EVENT_BUS.addListener(GuestWildsNeoForge::onIncomingDamage);
    NeoForge.EVENT_BUS.addListener(GuestWildsNeoForge::onConversion);
    NeoForge.EVENT_BUS.addListener(GuestWildsNeoForge::onFinalizeSpawn);
    NeoForge.EVENT_BUS.addListener(GuestWildsNeoForge::onFished);
  }

  private static void onIncomingDamage(LivingIncomingDamageEvent event) {
    if (!WildsEvents.allowDamage(event.getEntity(), event.getSource())) {
      event.setCanceled(true);
    }
  }

  private static void onConversion(LivingConversionEvent.Post event) {
    WildsEvents.onConversion(event.getEntity(), event.getOutcome());
  }

  private static void onFinalizeSpawn(FinalizeSpawnEvent event) {
    Mob mob = event.getEntity();
    switch (WildsEvents.onFinalizeSpawn(mob, event.getLevel().getLevel(), event.getSpawnType())) {
      case SKIP_FINALIZE -> event.setCanceled(true);
      case CANCEL -> event.setSpawnCancelled(true);
      case KEEP -> {}
    }
  }

  private static void onFished(ItemFishedEvent event) {
    if (event.getEntity() != null
        && WildsEvents.onFished(event.getHookEntity(), event.getEntity())) {
      event.setCanceled(true);
    }
  }
}
