package com.vortexso.guest_settlements.neoforge;

import com.vortexso.guest_settlements.GuestSettlements;
import com.vortexso.guest_settlements.illager.IllagerCamps;
import com.vortexso.guest_settlements.illager.IllagerRhythm;
import com.vortexso.guest_settlements.life.Rites;
import com.vortexso.guest_settlements.memory.VillageMemory;
import com.vortexso.guest_settlements.village.VillageWorldEvents;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ModifyCustomSpawnersEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

@Mod(GuestSettlements.MODID)
public final class GuestSettlementsNeoForge {
  public GuestSettlementsNeoForge() {
    GuestSettlements.init();
    NeoForge.EVENT_BUS.addListener(GuestSettlementsNeoForge::onTarget);
    NeoForge.EVENT_BUS.addListener(GuestSettlementsNeoForge::onSpawnCheck);
    NeoForge.EVENT_BUS.addListener(GuestSettlementsNeoForge::onSpawners);
    NeoForge.EVENT_BUS.addListener(GuestSettlementsNeoForge::onConversion);
    NeoForge.EVENT_BUS.addListener(GuestSettlementsNeoForge::onEntityInteract);
    NeoForge.EVENT_BUS.addListener(GuestSettlementsNeoForge::onBlockChanged);
    NeoForge.EVENT_BUS.addListener(GuestSettlementsNeoForge::onTrample);
    NeoForge.EVENT_BUS.addListener(GuestSettlementsNeoForge::onToolModification);
    NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, GuestSettlementsNeoForge::onRightClick);
    NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, GuestSettlementsNeoForge::onPlace);
    NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, GuestSettlementsNeoForge::onBreak);
  }

  private static void onTarget(LivingChangeTargetEvent event) {
    if (!GuestSettlements.allowTarget(event.getEntity(), event.getNewAboutToBeSetTarget())) {
      event.setCanceled(true);
    }
  }

  private static void onSpawnCheck(MobSpawnEvent.SpawnPlacementCheck event) {
    if (!IllagerCamps.allowSpawn(
        event.getEntityType(), event.getLevel(), event.getSpawnType(), event.getPos())) {
      event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
    }
  }

  private static void onSpawners(ModifyCustomSpawnersEvent event) {
    IllagerRhythm.modifySpawners(event.getCustomSpawners());
  }

  private static void onConversion(LivingConversionEvent.Post event) {
    VillageMemory.onConversion(event.getEntity(), event.getOutcome());
  }

  private static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
    VillageMemory.onInteract(event.getEntity(), event.getTarget(), event.getItemStack());
  }

  private static void onBlockChanged(BlockEvent.EntityPlaceEvent event) {
    if (event.getLevel() instanceof Level level) {
      VillageWorldEvents.blockChanged(level, event.getPos());
    }
  }

  private static void onTrample(BlockEvent.FarmlandTrampleEvent event) {
    if (event.getLevel() instanceof Level level) {
      VillageWorldEvents.blockChanged(level, event.getPos());
    }
  }

  private static void onToolModification(BlockEvent.BlockToolModificationEvent event) {
    if (event.getLevel() instanceof Level level) {
      VillageWorldEvents.blockChanged(level, event.getPos());
    }
  }

  private static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
    Rites.onRightClick(event.getEntity(), event.getLevel(), event.getPos(), event.getItemStack());
  }

  private static void onPlace(BlockEvent.EntityPlaceEvent event) {
    if (event.getLevel() instanceof Level level) {
      Rites.onPlace(level, event.getEntity(), event.getPos(), event.getPlacedBlock());
    }
  }

  private static void onBreak(BreakBlockEvent event) {
    if (event.getLevel() instanceof Level level) {
      VillageMemory.onBreak(level, event.getPlayer(), event.getPos(), event.getState());
    }
  }
}
