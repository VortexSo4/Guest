package com.vortexso.guest_architects.neoforge;

import com.vortexso.guest_architects.ArchitectsEvents;
import com.vortexso.guest_architects.GuestArchitects;
import com.vortexso.guest_architects.block.ArchitectPortalBlockEntity;
import com.vortexso.guest_architects.entity.Architect;
import com.vortexso.guest_architects.entity.Cure;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.VanillaGameEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEnchantItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

@Mod(GuestArchitects.MODID)
public final class GuestArchitectsNeoForge {
  public GuestArchitectsNeoForge(IEventBus modBus) {
    GuestArchitects.init(block -> new BlockEntityType<>(ArchitectPortalBlockEntity::new, block));
    Cure.converted = EventHooks::onLivingConvert;
    modBus.addListener(GuestArchitectsNeoForge::attributes);
    IEventBus bus = NeoForge.EVENT_BUS;
    bus.addListener(
        ServerAboutToStartEvent.class,
        event -> ArchitectsEvents.onServerAboutToStart(event.getServer()));
    bus.addListener(GuestArchitectsNeoForge::onPlace);
    bus.addListener(
        PlayerEnchantItemEvent.class, event -> ArchitectsEvents.onEnchant(event.getEntity()));
    bus.addListener(GuestArchitectsNeoForge::onGameEvent);
    bus.addListener(
        PlayerInteractEvent.RightClickItem.class,
        event -> {
          if (ArchitectsEvents.isHeld(event.getEntity())) {
            event.setCanceled(true);
          }
        });
    bus.addListener(
        PlayerInteractEvent.LeftClickBlock.class,
        event -> {
          if (ArchitectsEvents.isHeld(event.getEntity())) {
            event.setCanceled(true);
          }
        });
    bus.addListener(
        AttackEntityEvent.class,
        event -> {
          if (ArchitectsEvents.isHeld(event.getEntity())) {
            event.setCanceled(true);
          }
        });
    bus.addListener(GuestArchitectsNeoForge::onInteractEntity);
    bus.addListener(
        EntityTickEvent.Pre.class, event -> ArchitectsEvents.onEntityTick(event.getEntity()));
    bus.addListener(GuestArchitectsNeoForge::onConversion);
    bus.addListener(GuestArchitectsNeoForge::onFinalizeSpawn);
  }

  private static void attributes(EntityAttributeCreationEvent event) {
    event.put(GuestArchitects.ARCHITECT.get(), Architect.createAttributes().build());
  }

  private static void onPlace(BlockEvent.EntityPlaceEvent event) {
    if (event.getLevel() instanceof ServerLevel level
        && event.getEntity() instanceof ServerPlayer player) {
      ArchitectsEvents.onPlace(level, player, event.getPos(), event.getPlacedBlock());
    }
  }

  private static void onGameEvent(VanillaGameEvent event) {
    if (event.getLevel() instanceof ServerLevel level
        && event.getCause() != null
        && ArchitectsEvents.cancelGameEvent(
            level, event.getCause(), event.getVanillaEvent(), event.getEventPosition())) {
      event.setCanceled(true);
    }
  }

  private static void onInteractEntity(PlayerInteractEvent.EntityInteract event) {
    InteractionResult result =
        ArchitectsEvents.interactEntity(event.getEntity(), event.getTarget(), event.getItemStack());
    if (result != InteractionResult.PASS) {
      event.setCanceled(true);
      event.setCancellationResult(result);
    }
  }

  private static void onConversion(LivingConversionEvent.Pre event) {
    if (ArchitectsEvents.delayConversion(
        event.getEntity(), event.getOutcome(), event::setConversionTimer)) {
      event.setCanceled(true);
    }
  }

  private static void onFinalizeSpawn(FinalizeSpawnEvent event) {
    ArchitectsEvents.onFinalizeSpawn(
        event.getEntity(), event.getLevel().getLevel(), event.getSpawnType());
  }
}
