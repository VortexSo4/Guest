package com.vortexso.guest_wilds.client;

import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.client.GuestGizmos;
import com.vortexso.guest_core.debug.GuestDebug;
import com.vortexso.guest_wilds.GuestWilds;
import com.vortexso.guest_wilds.fish.Shoals;
import com.vortexso.guest_wilds.herd.Herds;
import com.vortexso.guest_wilds.lair.LairSpecies;
import com.vortexso.guest_wilds.lair.Lairs;
import com.vortexso.guest_wilds.path.PathWear;
import com.vortexso.guest_wilds.path.WearMap;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class WildsDebugRenderer {
  private static final int LAIR_RANGE = 128;
  private static final int WEAR_RANGE = 24;
  private static final double LINE = 0.25;
  private static final int TEXT = 0xFFFFFFFF;
  private static final int LAIR_COLOR = 0xFFAA55FF;
  private static final int MOUTH_COLOR = 0xFFFF55FF;
  private static final int HERD_COLOR = 0xFFFFCC55;
  private static final int FISH_COLOR = 0xFF55AAFF;
  private static final int[] STAGE_COLORS = {0xFF88FF88, 0xFFFFFF55, 0xFFFFAA33, 0xFFFF5533};

  private WildsDebugRenderer() {}

  public static void render() {
    boolean lairs = GuestDebug.isEnabled(GuestWilds.DEBUG_LAIRS);
    boolean paths = GuestDebug.isEnabled(GuestWilds.DEBUG_PATHS);
    boolean herds = GuestDebug.isEnabled(GuestWilds.DEBUG_HERDS);
    boolean fish = GuestDebug.isEnabled(GuestWilds.DEBUG_FISH);
    if (!lairs && !paths && !herds && !fish) {
      return;
    }
    Minecraft minecraft = Minecraft.getInstance();
    MinecraftServer server = minecraft.getSingleplayerServer();
    if (server == null
        || minecraft.level == null
        || minecraft.level.dimension() != Level.OVERWORLD) {
      return;
    }
    ServerLevel level = server.overworld();
    Vec3 camera = minecraft.gameRenderer.getMainCamera().position();
    BlockPos center = BlockPos.containing(camera);
    try (var ignored = minecraft.levelRenderer.collectPerFrameGizmos()) {
      if (lairs) {
        renderLairs(level, center);
      }
      if (paths) {
        renderWear(level, center);
      }
      if (herds) {
        renderHerds(level, center);
      }
      if (fish) {
        renderShoals(level, center);
      }
    } catch (RuntimeException ignored) {

    }
  }

  private static void renderLairs(ServerLevel level, BlockPos center) {
    for (Lairs.Node node : Lairs.get(level).nodesNear(center, LAIR_RANGE)) {
      BlockPos den = node.pos;
      GuestGizmos.box(new AABB(den).inflate(1, 0, 1).expandTowards(0, 2, 0), LAIR_COLOR);
      BlockPos entrance = node.entrance();
      if (node.mouth != null) {

        GuestGizmos.box(new AABB(entrance).expandTowards(0, 1, 0), MOUTH_COLOR);
        GuestGizmos.line(entrance.getCenter(), den.getCenter(), LAIR_COLOR);
        if (node.outside != null) {
          GuestGizmos.line(
              node.outside.getCenter(), node.outside.getCenter().add(0, 12, 0), MOUTH_COLOR);
        }
      }
      Vec3 label = entrance.getCenter().add(0, 3.5, 0);
      int line = 0;
      text(
          Component.translatable(
              "guest_wilds.debug.lair",
              format(node.openness),
              node.depth(),
              node.size,
              format(Lairs.pressure(level, entrance, 64, false))),
          label,
          line++,
          TEXT);
      for (LairSpecies species : LairSpecies.VALUES) {
        if (node.population(species) <= 0.0 && node.emigrants(species) <= 0.0) {
          continue;
        }
        text(
            Component.translatable(
                "guest_wilds.command.lair.species",
                Component.translatable("guest_wilds.species." + species.id()),
                format(node.population(species)),
                node.concrete(species),
                format(node.emigrants(species))),
            label,
            line++,
            species.color);
      }
      if (node.ridersEstablished()) {
        text(Component.translatable("guest_wilds.debug.riders"), label, line, TEXT);
      }
    }
  }

  private static void renderWear(ServerLevel level, BlockPos center) {
    PathWear wear = PathWear.get(level);
    WearMap map = wear.map();
    long now = GuestTime.gameTime(level);
    int cx = center.getX() >> 4;
    int cz = center.getZ() >> 4;
    int chunks = (WEAR_RANGE >> 4) + 1;
    for (int x = cx - chunks; x <= cx + chunks; x++) {
      for (int z = cz - chunks; z <= cz + chunks; z++) {
        WearMap.Column[] columns = map.chunk(ChunkPos.pack(x, z));
        if (columns == null) {
          continue;
        }
        for (WearMap.Column column : columns) {
          if (column == null) {
            continue;
          }
          if (column.y == WearMap.NO_Y) {
            continue;
          }
          int y = column.y;
          Vec3 top = new Vec3(column.x + 0.5, y + 1.1, column.z + 0.5);
          if (top.distanceToSqr(center.getCenter()) > WEAR_RANGE * WEAR_RANGE) {
            continue;
          }
          int color = STAGE_COLORS[Math.min(column.stage, STAGE_COLORS.length - 1)];
          GuestGizmos.point(top, color, 3.0F);
          GuestGizmos.text(format(map.wearAt(column, now)), top.add(0, 0.3, 0), color, 0.2F);
        }
      }
    }
  }

  private static void renderHerds(ServerLevel level, BlockPos center) {
    Herds herds = Herds.get(level);
    long now = GuestTime.gameTime(level);
    for (Herds.Herd herd : herds.herds()) {
      if (!near(herd.center(), center)) {
        continue;
      }
      BlockPos at = surface(level, herd.center());
      int cellX = (herd.center().getX() >> Herds.CELL_SHIFT) << Herds.CELL_SHIFT;
      int cellZ = (herd.center().getZ() >> Herds.CELL_SHIFT) << Herds.CELL_SHIFT;
      int size = 1 << Herds.CELL_SHIFT;
      GuestGizmos.box(
          new AABB(cellX, at.getY() - 1, cellZ, cellX + size, at.getY() + 3, cellZ + size),
          HERD_COLOR);
      BlockPos previous = near(herd.previous(), center) ? surface(level, herd.previous()) : at;
      if (!previous.equals(at)) {
        GuestGizmos.arrow(previous.getCenter(), at.getCenter(), HERD_COLOR);
      }
      BlockPos water = herd.water();
      if (water != null) {
        GuestGizmos.line(at.getCenter(), water.getCenter(), FISH_COLOR);
      }
      if (herd.leaderId() != null
          && level.getEntity(herd.leaderId()) instanceof Mob leader
          && leader.isAlive()) {

        Vec3 head = leader.position().add(0, leader.getBbHeight() + 0.6, 0);
        GuestGizmos.point(head, HERD_COLOR, 6.0F);
        GuestGizmos.line(head, at.getCenter(), HERD_COLOR);
      }
      Vec3 label = at.getCenter().add(0, 3, 0);
      text(
          Component.translatable(
              "guest_wilds.debug.herd",
              Component.translatable(
                  BuiltInRegistries.ENTITY_TYPE
                      .getValue(Identifier.parse(herd.species))
                      .getDescriptionId()),
              format(herd.animals()),
              herd.concrete(),
              format(herds.vegetationAt(herd.center(), now))),
          label,
          0,
          TEXT);
    }
  }

  private static void renderShoals(ServerLevel level, BlockPos center) {
    long now = GuestTime.gameTime(level);
    for (Shoals.Shoal shoal : Shoals.get(level).shoals()) {
      if (!near(shoal.center(), center)) {
        continue;
      }
      BlockPos at = surface(level, shoal.center());
      GuestGizmos.text(
          Component.translatable(
              "guest_wilds.debug.shoal",
              Component.translatable(
                  "guest_wilds.water." + shoal.water.name().toLowerCase(Locale.ROOT)),
              format(shoal.fish()),
              format(Shoals.capacity(shoal, now)),
              format(Shoals.salmonShare(shoal, now))),
          at.getCenter().add(0, 1.5, 0),
          FISH_COLOR);
    }
  }

  private static boolean near(BlockPos pos, BlockPos center) {
    long dx = pos.getX() - center.getX();
    long dz = pos.getZ() - center.getZ();
    return dx * dx + dz * dz <= (long) LAIR_RANGE * LAIR_RANGE;
  }

  private static BlockPos surface(ServerLevel level, BlockPos pos) {
    return new BlockPos(
        pos.getX(),
        level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ()),
        pos.getZ());
  }

  private static void text(Component text, Vec3 base, int line, int color) {
    GuestGizmos.text(text, base.add(0, -line * LINE, 0), color);
  }

  private static String format(double value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }
}
