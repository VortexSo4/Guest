package com.vortexso.guest_hands.station;

import com.vortexso.guest_hands.GuestHands;
import com.vortexso.guest_hands.HandsConfig;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;

/** Routes clicks to physical workstations and keeps their displays consistent with the world. */
@EventBusSubscriber(modid = GuestHands.MODID)
public final class Stations {
  static final List<Station> ALL =
      List.of(
          new CraftingStation(),
          new FurnaceStation(),
          new AnvilStation(),
          new EnchantingStation(),
          new BrewingStation());

  /** Half a second: display refresh and orphan cleanup do not need per-tick precision. */
  private static final int SWEEP_INTERVAL = 10;

  /** Holding attack re-sends "start destroy" every tick; this keeps a punch = one item. */
  private static final int PUNCH_COOLDOWN = 5;

  private static final Map<UUID, Long> LAST_PUNCH = new HashMap<>();

  private Stations() {}

  static @Nullable Station of(BlockState state) {
    for (Station station : ALL) {
      if (station.enabled() && station.accepts(state)) {
        return station;
      }
    }
    return null;
  }

  private static @Nullable Station byId(String id) {
    for (Station station : ALL) {
      if (station.id().equals(id)) {
        return station;
      }
    }
    return null;
  }

  @SubscribeEvent
  static void rightClick(PlayerInteractEvent.RightClickBlock event) {
    Player player = event.getEntity();
    if (event.getHand() != InteractionHand.MAIN_HAND || player.isSpectator()) {
      return;
    }
    Level level = event.getLevel();
    BlockPos pos = event.getPos();
    BlockState state = level.getBlockState(pos);
    Station station = of(state);
    if (station == null) {
      return;
    }
    boolean consumed =
        level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer
            ? station.use(serverLevel, pos, state, serverPlayer, event.getHitVec())
            : station.claimsUse(player, event.getHitVec());
    if (consumed) {
      event.setCanceled(true);
      event.setCancellationResult(InteractionResult.SUCCESS);
    }
  }

  @SubscribeEvent
  static void punch(PlayerInteractEvent.LeftClickBlock event) {
    if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) {
      return;
    }
    Player player = event.getEntity();
    Level level = event.getLevel();
    BlockPos pos = event.getPos();
    Station station = of(level.getBlockState(pos));
    if (station == null
        || !station.storesItems()
        || !station.punchFace(event.getFace())
        || player.isSpectator()) {
      return;
    }
    if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer server)) {
      // Client: do not start cracking a block whose items the server will pop off instead.
      if (!level
          .getEntitiesOfClass(Display.ItemDisplay.class, new AABB(pos).expandTowards(0, 0.5, 0))
          .isEmpty()) {
        event.setCanceled(true);
      }
      return;
    }
    // The event fires before vanilla's reach check.
    if (!server.isWithinBlockInteractionRange(pos, 1.0)) {
      return;
    }
    long now = serverLevel.getGameTime();
    Long last = LAST_PUNCH.get(server.getUUID());
    boolean coolingDown = last != null && now - last < PUNCH_COOLDOWN && now >= last;
    if (coolingDown) {
      if (!StationDisplays.byRole(serverLevel, pos, station.id()).isEmpty()) {
        event.setCanceled(true);
      }
      return;
    }
    Vec3 hit = server.pick(server.blockInteractionRange(), 1.0F, false).getLocation();
    if (station.punch(serverLevel, pos, server, hit)) {
      LAST_PUNCH.put(server.getUUID(), now);
      event.setCanceled(true);
    }
  }

  /**
   * One sweep keeps every display honest: mirrors furnace/brewing state near players and removes
   * displays whose block is gone (broken, exploded, pushed, replaced), dropping stored items. One
   * mechanism instead of hooking every way a block can disappear.
   */
  @SubscribeEvent
  static void sweep(ServerTickEvent.Post event) {
    if (event.getServer().getTickCount() % SWEEP_INTERVAL != 0) {
      return;
    }
    int radius = HandsConfig.DISPLAY_RADIUS.get();
    for (ServerLevel level : event.getServer().getAllLevels()) {
      if (level.players().isEmpty()) {
        continue;
      }
      Set<BlockPos> synced = new HashSet<>();
      Set<Display> checked = new HashSet<>();
      for (ServerPlayer player : level.players()) {
        syncBlockEntities(level, player, radius, synced);
        cleanOrphans(level, player, radius, checked);
      }
    }
  }

  private static void syncBlockEntities(
      ServerLevel level, ServerPlayer player, int radius, Set<BlockPos> synced) {
    ChunkPos center = player.chunkPosition();
    int chunkRadius = (radius >> 4) + 1;
    double radiusSqr = (double) radius * radius;
    for (int cx = center.x() - chunkRadius; cx <= center.x() + chunkRadius; cx++) {
      for (int cz = center.z() - chunkRadius; cz <= center.z() + chunkRadius; cz++) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) {
          continue;
        }
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
          BlockPos pos = blockEntity.getBlockPos();
          if (pos.distToCenterSqr(player.position()) > radiusSqr || !synced.add(pos)) {
            continue;
          }
          for (Station station : ALL) {
            if (station.accepts(blockEntity.getBlockState())) {
              station.sync(level, blockEntity);
            }
          }
        }
      }
    }
  }

  private static void cleanOrphans(
      ServerLevel level, ServerPlayer player, int radius, Set<Display> checked) {
    for (Display display :
        level.getEntitiesOfClass(
            Display.class,
            player.getBoundingBox().inflate(radius),
            display -> display.entityTags().contains(StationDisplays.MARKER))) {
      if (!checked.add(display)) {
        continue;
      }
      StationDisplays.Key key = StationDisplays.Key.of(display);
      Station station = key == null ? null : byId(key.station());
      if (station != null && station.accepts(level.getBlockState(key.pos()))) {
        continue;
      }
      if (station != null && station.storesItems() && display instanceof Display.ItemDisplay item) {
        Block.popResource(
            level,
            key.pos(),
            ((com.vortexso.guest_hands.mixin.ItemDisplayAccessor) item).guest_hands$getItem());
      }
      display.discard();
    }
  }

  /** Inserts as much of {@code stack} as the slot accepts; returns the amount moved. */
  static int insert(Container container, int slot, ItemStack stack) {
    if (stack.isEmpty() || !container.canPlaceItem(slot, stack)) {
      return 0;
    }
    ItemStack current = container.getItem(slot);
    int limit = Math.min(container.getMaxStackSize(stack), stack.getMaxStackSize());
    if (current.isEmpty()) {
      int moved = Math.min(limit, stack.getCount());
      container.setItem(slot, stack.copyWithCount(moved));
      return moved;
    }
    if (!ItemStack.isSameItemSameComponents(current, stack)) {
      return 0;
    }
    int moved = Math.min(limit - current.getCount(), stack.getCount());
    if (moved <= 0) {
      return 0;
    }
    container.setItem(slot, current.copyWithCount(current.getCount() + moved));
    return moved;
  }

  /** Takes an item into the (empty-handed) player's hand, else inventory, else drops it. */
  static void give(ServerPlayer player, ItemStack stack) {
    if (stack.isEmpty()) {
      return;
    }
    if (player.getMainHandItem().isEmpty()) {
      player.setItemInHand(InteractionHand.MAIN_HAND, stack);
    } else {
      player.getInventory().placeItemBackInInventory(stack);
    }
  }

  /** A crafted result appears resting on the workstation, like a dropped item. */
  static void dropOnTop(ServerLevel level, BlockPos pos, double height, ItemStack stack) {
    ItemEntity item =
        new ItemEntity(
            level, pos.getX() + 0.5, pos.getY() + height, pos.getZ() + 0.5, stack, 0.0, 0.05, 0.0);
    item.setDefaultPickUpDelay();
    level.addFreshEntity(item);
  }

  static StationDisplays.Key key(Station station, String role, BlockPos pos) {
    return new StationDisplays.Key(station.id(), role, pos);
  }
}
