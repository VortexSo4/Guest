package com.vortexso.guest_wilds;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.vortexso.guest_core.api.event.RouteTrafficEvent;
import com.vortexso.guest_core.api.world.GuestWildlife;
import com.vortexso.guest_core.debug.GuestDebug;
import com.vortexso.guest_core.platform.Attachment;
import com.vortexso.guest_core.platform.Events;
import com.vortexso.guest_core.platform.Platform;
import com.vortexso.guest_wilds.flora.Spread;
import com.vortexso.guest_wilds.lair.Lairs;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public final class GuestWilds {
  public static final String MODID = "guest_wilds";
  public static final Logger LOGGER = LogUtils.getLogger();

  public static final String DEBUG_LAIRS = "wilds_lairs";
  public static final String DEBUG_PATHS = "wilds_paths";
  public static final String DEBUG_HERDS = "wilds_herds";
  public static final String DEBUG_FISH = "wilds_fish";

  public static final Attachment<Membership> MEMBERSHIP =
      Platform.INSTANCE.attachment(
          MODID, "membership", () -> Membership.NONE, Membership.MAP_CODEC, null, null);

  public static final Attachment<Long> SPREAD =
      Platform.INSTANCE.attachment(
          MODID, "forest_spread", () -> Spread.NEVER, Codec.LONG.fieldOf("period"), null, null);

  public static final Attachment<String> SUMMER_COAT =
      Platform.INSTANCE.attachment(
          MODID,
          "summer_coat",
          () -> "",
          Codec.STRING.fieldOf("variant"),
          coat -> !coat.isEmpty(),
          null);

  public static Function<Mob, @Nullable EntitySpawnReason> spawnReason = mob -> null;

  private GuestWilds() {}

  public static boolean loaded(ServerLevel level, BlockPos pos) {
    return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
  }

  public static int updateFlags(ServerLevel level, BlockPos pos) {
    for (Direction direction : Direction.Plane.HORIZONTAL) {
      if (!loaded(level, pos.relative(direction))) {
        return Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
      }
    }
    return Block.UPDATE_ALL;
  }

  public static void init() {
    Platform.INSTANCE.registerConfig(MODID, Platform.ConfigType.SERVER, WildsConfig.SPEC, null);
    GuestDebug.register(DEBUG_LAIRS);
    GuestDebug.register(DEBUG_PATHS);
    GuestDebug.register(DEBUG_HERDS);
    GuestDebug.register(DEBUG_FISH);
    GuestWildlife.registerHostilePressure(Lairs::hostilePressure);
    Events.ENTITY_JOIN.register(WildsEvents::onJoin);
    Events.ENTITY_LEAVE.register(WildsEvents::onLeave);
    Events.CHUNK_LOAD.register(WildsEvents::onChunkLoad);
    Events.BLOCK_BREAK.register(WildsEvents::onBreak);
    Events.LEVEL_TICK.register(WildsEvents::onLevelTick);
    Events.COMMANDS.register(WildsCommands::register);
    RouteTrafficEvent.LISTENERS.register(WildsEvents::onRouteTraffic);
  }
}
