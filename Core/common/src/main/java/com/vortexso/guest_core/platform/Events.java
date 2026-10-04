package com.vortexso.guest_core.platform;

import com.mojang.brigadier.CommandDispatcher;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;

public final class Events {
  public static final Listeners<Consumer<MinecraftServer>> SERVER_TICK = new Listeners<>();
  public static final Listeners<Consumer<ServerLevel>> LEVEL_TICK = new Listeners<>();
  public static final Listeners<Consumer<ServerPlayer>> PLAYER_TICK = new Listeners<>();
  public static final Listeners<ChunkLoad> CHUNK_LOAD = new Listeners<>();
  public static final Listeners<BiConsumer<ServerLevel, LevelChunk>> CHUNK_UNLOAD =
      new Listeners<>();
  public static final Listeners<EntityJoin> ENTITY_JOIN = new Listeners<>();
  public static final Listeners<BiConsumer<Entity, ServerLevel>> ENTITY_LEAVE = new Listeners<>();
  public static final Listeners<BlockBreak> BLOCK_BREAK = new Listeners<>();
  public static final Listeners<UseBlock> USE_BLOCK = new Listeners<>();
  public static final Listeners<BiConsumer<LivingEntity, DamageSource>> LIVING_DEATH =
      new Listeners<>();
  public static final Listeners<CommandRegistration> COMMANDS = new Listeners<>();
  public static final Listeners<Runnable> CLIENT_TICK = new Listeners<>();
  public static final Listeners<Runnable> DEBUG_RENDER = new Listeners<>();

  private Events() {}

  public static boolean allowJoin(Entity entity, ServerLevel level, boolean fromDisk) {
    for (EntityJoin listener : ENTITY_JOIN) {
      if (!listener.onJoin(entity, level, fromDisk)) {
        return false;
      }
    }
    return true;
  }

  public static boolean allowBreak(
      ServerLevel level, Player player, BlockPos pos, BlockState state) {
    for (BlockBreak listener : BLOCK_BREAK) {
      if (!listener.allow(level, player, pos, state)) {
        return false;
      }
    }
    return true;
  }

  public static InteractionResult useBlock(
      Player player, Level level, InteractionHand hand, BlockHitResult hit) {
    for (UseBlock listener : USE_BLOCK) {
      InteractionResult result = listener.use(player, level, hand, hit);
      if (result != InteractionResult.PASS) {
        return result;
      }
    }
    return InteractionResult.PASS;
  }

  @FunctionalInterface
  public interface ChunkLoad {
    void onLoad(ServerLevel level, LevelChunk chunk, boolean newChunk);
  }

  @FunctionalInterface
  public interface EntityJoin {
    boolean onJoin(Entity entity, ServerLevel level, boolean fromDisk);
  }

  @FunctionalInterface
  public interface BlockBreak {
    boolean allow(ServerLevel level, Player player, BlockPos pos, BlockState state);
  }

  @FunctionalInterface
  public interface UseBlock {
    InteractionResult use(Player player, Level level, InteractionHand hand, BlockHitResult hit);
  }

  @FunctionalInterface
  public interface CommandRegistration {
    void register(
        CommandDispatcher<CommandSourceStack> dispatcher,
        CommandBuildContext context,
        Commands.CommandSelection selection);
  }
}
