package com.vortexso.guest_core.fabric;

import com.vortexso.guest_core.GuestCore;
import com.vortexso.guest_core.platform.Events;
import com.vortexso.guest_core.platform.Events.ChunkLoad;
import com.vortexso.guest_core.platform.Events.CommandRegistration;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.chunk.LevelChunk;

public final class GuestCoreFabric implements ModInitializer {
  @Override
  public void onInitialize() {
    GuestCore.init();
    ServerTickEvents.END_SERVER_TICK.register(
        server -> {
          for (Consumer<MinecraftServer> listener : Events.SERVER_TICK) {
            listener.accept(server);
          }
        });
    ServerTickEvents.END_LEVEL_TICK.register(
        level -> {
          for (Consumer<ServerLevel> listener : Events.LEVEL_TICK) {
            listener.accept(level);
          }
          if (!Events.PLAYER_TICK.isEmpty()) {
            for (ServerPlayer player : level.players()) {
              for (Consumer<ServerPlayer> listener : Events.PLAYER_TICK) {
                listener.accept(player);
              }
            }
          }
        });
    ServerChunkEvents.CHUNK_LOAD.register(
        (level, chunk, generated) -> {
          for (ChunkLoad listener : Events.CHUNK_LOAD) {
            listener.onLoad(level, chunk, generated);
          }
        });
    ServerChunkEvents.CHUNK_UNLOAD.register(
        (level, chunk) -> {
          for (BiConsumer<ServerLevel, LevelChunk> listener : Events.CHUNK_UNLOAD) {
            listener.accept(level, chunk);
          }
        });
    ServerEntityEvents.ALLOW_LOAD.register(
        (entity, level, reason, fromDisk) -> Events.allowJoin(entity, level, fromDisk));
    ServerEntityEvents.ENTITY_UNLOAD.register(
        (entity, level) -> {
          for (BiConsumer<Entity, ServerLevel> listener : Events.ENTITY_LEAVE) {
            listener.accept(entity, level);
          }
        });
    PlayerBlockBreakEvents.BEFORE.register(
        (level, player, pos, state, blockEntity) ->
            !(level instanceof ServerLevel server)
                || Events.allowBreak(server, player, pos, state));
    UseBlockCallback.EVENT.register(Events::useBlock);
    ServerLivingEntityEvents.AFTER_DEATH.register(
        (entity, source) -> {
          for (BiConsumer<LivingEntity, DamageSource> listener : Events.LIVING_DEATH) {
            listener.accept(entity, source);
          }
        });
    CommandRegistrationCallback.EVENT.register(
        (dispatcher, context, selection) -> {
          for (CommandRegistration listener : Events.COMMANDS) {
            listener.register(dispatcher, context, selection);
          }
        });
  }
}
