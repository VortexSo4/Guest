package com.vortexso.guest_core.neoforge;

import com.vortexso.guest_core.GuestCore;
import com.vortexso.guest_core.platform.Events;
import com.vortexso.guest_core.platform.Events.ChunkLoad;
import com.vortexso.guest_core.platform.Events.CommandRegistration;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(GuestCore.MODID)
public final class GuestCoreNeoForge {
  public GuestCoreNeoForge() {
    GuestCore.init();
    NeoForge.EVENT_BUS.addListener(GuestCoreNeoForge::onServerTick);
    NeoForge.EVENT_BUS.addListener(GuestCoreNeoForge::onLevelTick);
    NeoForge.EVENT_BUS.addListener(GuestCoreNeoForge::onPlayerTick);
    NeoForge.EVENT_BUS.addListener(GuestCoreNeoForge::onChunkLoad);
    NeoForge.EVENT_BUS.addListener(GuestCoreNeoForge::onChunkUnload);
    NeoForge.EVENT_BUS.addListener(GuestCoreNeoForge::onEntityJoin);
    NeoForge.EVENT_BUS.addListener(GuestCoreNeoForge::onEntityLeave);
    NeoForge.EVENT_BUS.addListener(GuestCoreNeoForge::onBreakBlock);
    NeoForge.EVENT_BUS.addListener(GuestCoreNeoForge::onUseBlock);
    NeoForge.EVENT_BUS.addListener(GuestCoreNeoForge::onLivingDeath);
    NeoForge.EVENT_BUS.addListener(GuestCoreNeoForge::onRegisterCommands);
  }

  private static void onServerTick(ServerTickEvent.Post event) {
    for (Consumer<net.minecraft.server.MinecraftServer> listener : Events.SERVER_TICK) {
      listener.accept(event.getServer());
    }
  }

  private static void onLevelTick(LevelTickEvent.Post event) {
    if (event.getLevel() instanceof ServerLevel level) {
      for (Consumer<ServerLevel> listener : Events.LEVEL_TICK) {
        listener.accept(level);
      }
    }
  }

  private static void onPlayerTick(PlayerTickEvent.Post event) {
    if (event.getEntity() instanceof ServerPlayer player) {
      for (Consumer<ServerPlayer> listener : Events.PLAYER_TICK) {
        listener.accept(player);
      }
    }
  }

  private static void onChunkLoad(ChunkEvent.Load event) {
    if (event.getLevel() instanceof ServerLevel level
        && event.getChunk() instanceof LevelChunk chunk) {
      for (ChunkLoad listener : Events.CHUNK_LOAD) {
        listener.onLoad(level, chunk, event.isNewChunk());
      }
    }
  }

  private static void onChunkUnload(ChunkEvent.Unload event) {
    if (event.getLevel() instanceof ServerLevel level
        && event.getChunk() instanceof LevelChunk chunk) {
      for (BiConsumer<ServerLevel, LevelChunk> listener : Events.CHUNK_UNLOAD) {
        listener.accept(level, chunk);
      }
    }
  }

  private static void onEntityJoin(EntityJoinLevelEvent event) {
    if (event.getLevel() instanceof ServerLevel level
        && !Events.allowJoin(event.getEntity(), level, event.loadedFromDisk())) {
      event.setCanceled(true);
    }
  }

  private static void onEntityLeave(EntityLeaveLevelEvent event) {
    if (event.getLevel() instanceof ServerLevel level) {
      for (BiConsumer<Entity, ServerLevel> listener : Events.ENTITY_LEAVE) {
        listener.accept(event.getEntity(), level);
      }
    }
  }

  private static void onBreakBlock(BreakBlockEvent event) {
    if (!event.isCanceled()
        && event.getLevel() instanceof ServerLevel level
        && !Events.allowBreak(level, event.getPlayer(), event.getPos(), event.getState())) {
      event.setCanceled(true);
    }
  }

  private static void onUseBlock(PlayerInteractEvent.RightClickBlock event) {
    InteractionResult result =
        Events.useBlock(event.getEntity(), event.getLevel(), event.getHand(), event.getHitVec());
    if (result != InteractionResult.PASS) {
      event.setCanceled(true);
      event.setCancellationResult(result);
    }
  }

  private static void onLivingDeath(LivingDeathEvent event) {
    if (!event.getEntity().level().isClientSide()) {
      for (BiConsumer<LivingEntity, net.minecraft.world.damagesource.DamageSource> listener :
          Events.LIVING_DEATH) {
        listener.accept(event.getEntity(), event.getSource());
      }
    }
  }

  private static void onRegisterCommands(RegisterCommandsEvent event) {
    for (CommandRegistration listener : Events.COMMANDS) {
      listener.register(
          event.getDispatcher(), event.getBuildContext(), event.getCommandSelection());
    }
  }
}
