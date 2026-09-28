package com.vortexso.guest_settlements.life;

import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.world.GuestWeather;
import com.vortexso.guest_settlements.GuestSettlements;
import com.vortexso.guest_settlements.SettlementsConfig;
import com.vortexso.guest_settlements.life.Errands.Errand;
import com.vortexso.guest_settlements.life.Errands.Step;
import com.vortexso.guest_settlements.life.VillageLife.Role;
import com.vortexso.guest_settlements.life.VillageLife.Village;
import com.vortexso.guest_settlements.memory.VillageMemory;
import com.vortexso.guest_settlements.village.VillageNode;
import com.vortexso.guest_settlements.village.VillagePopulationScanner;
import com.vortexso.guest_settlements.village.VillageWorldManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.jspecify.annotations.Nullable;

@EventBusSubscriber(modid = GuestSettlements.MODID)
public final class Rites {
  static final int FULL_MOON = 0;
  static final int NEW_MOON = 4;

  public static final long CALL = 11600;
  public static final long MOONRISE = 12800;
  public static final long END = 13600;
  private static final long[] FULL_MOON_RINGS = {
    CALL + 200, CALL + 400, CALL + 600, MOONRISE + 300, MOONRISE + 330, MOONRISE + 360, END - 80
  };
  private static final long MOURNING_TOLL = 300;
  private static final long RING_STALE = 200;
  private static final double SEVERE_ATTENDANCE = 0.35;

  private static final long AURORA_FROM = 14000;
  private static final long AURORA_TO = 22000;
  private static final int AURORA_WALK = 600;
  private static final int AURORA_SILENCE = 1200;

  private static final long STORM_LOOKAHEAD = 2000;

  private static final long STORM_TOLL_INTERVAL = 80;
  private static final int STORM_AWAY_DISTANCE = 24;
  private static final int HELP_RADIUS = 16;

  public static final Identifier STORM_HELP = GuestSettlements.id("storm_help");

  private static final class BellState {
    long lastRing = Long.MIN_VALUE;
    long auroraDay = Long.MIN_VALUE;
    long auroraEnd;
    boolean auroraForced;
  }

  public static void forceAurora(ServerLevel level, VillageNode node) {
    BellState state = bellState(level, node);
    state.auroraDay = Long.MIN_VALUE;
    state.auroraForced = true;
  }

  private static final Map<ServerLevel, Map<Long, BellState>> BELLS = new WeakHashMap<>();
  private static final Map<ServerLevel, Map<Long, Long>> HELPED = new WeakHashMap<>();

  private Rites() {}

  static boolean tick(Village v) {
    return rite(v) || storm(v) || aurora(v);
  }

  private static boolean rite(Village v) {
    boolean full = v.weekday() == FULL_MOON;
    if (v.bell() == null
        || !SettlementsConfig.enabled(SettlementsConfig.CEREMONIES)
        || !v.between(CALL, END)
        || (!full && v.weekday() != NEW_MOON)) {

      for (Villager villager : v.villagers()) {
        if (Errands.doing(villager, Role.RITE)
            || Errands.doing(villager, Role.MOURNING)
            || Errands.doing(villager, Role.RINGER)) {
          Errands.cancel(villager);
        }
      }
      return false;
    }
    BlockPos bell = v.bell();
    long end = v.at(END);
    Villager leader = leader(v);
    if (leader != null && !Errands.doing(leader, Role.RINGER)) {
      Errands.offer(
          v.level(),
          leader,
          new Errand(
              Role.RINGER,
              VillageLife.RITE,
              end,
              List.of(
                  new Step(
                      Errands.at(bell),
                      2,
                      0.55F,
                      Integer.MAX_VALUE,
                      null,
                      full ? ItemStack.EMPTY : new ItemStack(Items.CANDLE),
                      (level, villager, tick) ->
                          riteBell(level, v.node(), bell, villager, full)))));
    }
    List<Villager> attendees = new ArrayList<>();
    for (Villager villager : v.villagers()) {
      if (villager == leader) {
        continue;
      }
      if (v.weather().isSevere()
          && GuestHash.unit(v.hash(0x5EE7EL, villager.getUUID())) >= SEVERE_ATTENDANCE) {
        continue;
      }
      attendees.add(villager);
    }
    Role role = full ? Role.RITE : Role.MOURNING;
    for (int i = 0; i < attendees.size(); i++) {
      Villager villager = attendees.get(i);
      if (Errands.doing(villager, role)) {
        continue;
      }
      BlockPos spot = reachableSpot(v.level(), villager, bell, i, attendees.size());
      Errands.offer(
          v.level(),
          villager,
          new Errand(
              role,
              VillageLife.RITE,
              end,
              List.of(
                  new Step(
                      Errands.at(spot),
                      1,
                      0.5F,
                      Integer.MAX_VALUE,
                      Errands.at(bell),
                      full ? ItemStack.EMPTY : new ItemStack(Items.CANDLE),
                      (level, attendee, tick) ->
                          attend(level, attendee, bell, spot, full, tick)))));
    }
    return true;
  }

  private static void attend(
      ServerLevel level, Villager villager, BlockPos bell, BlockPos spot, boolean full, int tick) {
    long time = GuestTime.tickOfDay(GuestTime.gameTime(level));
    Vec3 eye = villager.getEyePosition();
    if (full && time >= MOONRISE) {

      double elevation = Math.toRadians(4.0 + (time - MOONRISE) * 0.02);
      villager
          .getBrain()
          .setMemory(
              MemoryModuleType.LOOK_TARGET,
              Errands.at(eye.add(40.0, 40.0 * Math.tan(elevation), 0.0)));
      if (time >= MOONRISE + 300 && time < MOONRISE + 500 && (tick + villager.getId()) % 60 == 0) {
        villager.getJumpControl().jump();
        level.sendParticles(
            ParticleTypes.HAPPY_VILLAGER,
            villager.getX(),
            villager.getEyeY() + 0.4,
            villager.getZ(),
            3,
            0.3,
            0.2,
            0.3,
            0.0);
      }
    } else if (!full) {
      Vec3 towardBell = Vec3.atBottomCenterOf(bell).subtract(villager.position()).normalize();
      villager
          .getBrain()
          .setMemory(
              MemoryModuleType.LOOK_TARGET,
              Errands.at(villager.position().add(towardBell.scale(1.2))));
      if (tick % 12 == 0) {
        candleFlame(level, villager);
      }
    }
  }

  private static void candleFlame(ServerLevel level, Villager villager) {
    double yaw = Math.toRadians(villager.yBodyRot);
    double scale = villager.isBaby() ? 0.5 : 1.0;
    level.sendParticles(
        ParticleTypes.SMALL_FLAME,
        villager.getX() - Math.sin(yaw) * 0.45 * scale,
        villager.getY() + 1.25 * scale,
        villager.getZ() + Math.cos(yaw) * 0.45 * scale,
        1,
        0.0,
        0.0,
        0.0,
        0.0);
  }

  private static void riteBell(
      ServerLevel level, VillageNode node, BlockPos bell, Villager leader, boolean full) {
    long clock = GuestTime.gameTime(level);
    long dayStart = clock - GuestTime.tickOfDay(clock);
    BellState state = bellState(level, node);
    if (full) {
      for (long ring : FULL_MOON_RINGS) {
        if (due(state, dayStart + ring, clock)) {
          ring(level, leader, bell, 1.0F);
          if (ring >= MOONRISE && ring < END - 80) {
            level.sendParticles(
                ParticleTypes.END_ROD,
                bell.getX() + 0.5,
                bell.getY() + 1.0,
                bell.getZ() + 0.5,
                12,
                0.3,
                0.6,
                0.3,
                0.02);
          }
          return;
        }
      }
    } else {
      long first = dayStart + CALL + 200;
      long slot = first + Math.max(0L, (clock - first) / MOURNING_TOLL) * MOURNING_TOLL;
      if (clock >= first && due(state, slot, clock)) {
        ring(level, leader, bell, 0.6F);
      }
      if (clock % 12 == 0) {
        candleFlame(level, leader);
      }
    }
  }

  private static boolean due(BellState state, long ringTime, long clock) {
    if (clock >= ringTime && clock - ringTime < RING_STALE && state.lastRing < ringTime) {
      state.lastRing = clock;
      return true;
    }
    return false;
  }

  private static @Nullable Villager leader(Village v) {
    return v.villagers().stream()
        .filter(
            villager ->
                !villager.isBaby()
                    && !villager.getVillagerData().profession().is(VillagerProfession.NITWIT))
        .min(
            Comparator.comparing(
                    (Villager villager) ->
                        !villager.getVillagerData().profession().is(VillagerProfession.CLERIC))
                .thenComparing(villager -> -villager.getVillagerXp())
                .thenComparing(Villager::getUUID))
        .orElse(null);
  }

  private static boolean storm(Village v) {
    if (!SettlementsConfig.enabled(SettlementsConfig.STORM_SHELTER) || !v.between(0, CALL)) {
      return false;
    }
    boolean severe = v.weather().isSevere();
    if (!severe && !stormAhead(v)) {
      return false;
    }
    long deadline = v.gameTime() + VillageLife.INTERVAL * 3L;
    List<BlockPos> beds = v.manager().homes(v.node());
    boolean someoneAway = false;
    for (Villager villager : v.villagers()) {
      Errand errand = Errands.current(villager);
      if (errand != null && errand.role() == Role.SHELTER) {
        errand.extend(deadline);
        continue;
      }
      if (villager.isSleeping() || !v.level().canSeeSky(villager.blockPosition().above())) {
        continue;
      }
      if (v.bell() != null
          && !v.bell().closerToCenterThan(villager.position(), STORM_AWAY_DISTANCE)) {
        someoneAway = true;
      }

      BlockPos home =
          villager
              .getBrain()
              .getMemory(MemoryModuleType.HOME)
              .map(GlobalPos::pos)
              .orElseGet(
                  () ->
                      beds.stream()
                          .min(
                              Comparator.comparingDouble(
                                  bed -> villager.distanceToSqr(Vec3.atCenterOf(bed))))
                          .orElse(v.center()));
      Errands.offer(
          v.level(),
          villager,
          new Errand(
              Role.SHELTER,
              VillageLife.STORM,
              deadline,
              List.of(
                  Step.walk(home, 1, 0.7F),
                  Step.wait(Integer.MAX_VALUE, null, Errands.Action.NONE))));
    }
    if (severe
        && someoneAway
        && v.bell() != null
        && SettlementsConfig.enabled(SettlementsConfig.STORM_BELL)) {
      stormBell(v, deadline);
    }
    return true;
  }

  private static boolean stormAhead(Village v) {
    for (long ahead = 500; ahead <= STORM_LOOKAHEAD; ahead += 500) {
      if (GuestWeather.get(v.level(), v.center(), v.clock() + ahead).isSevere()) {
        return true;
      }
    }
    return false;
  }

  private static void stormBell(Village v, long deadline) {
    Villager ringer = leader(v);
    if (ringer == null) {
      return;
    }
    Errand errand = Errands.current(ringer);
    if (errand != null && errand.role() == Role.STORM) {
      errand.extend(deadline);
      return;
    }
    BlockPos bell = v.bell();
    Errands.offer(
        v.level(),
        ringer,
        new Errand(
            Role.STORM,
            VillageLife.STORM + 1,
            deadline,
            List.of(
                new Step(
                    Errands.at(bell),
                    2,
                    0.7F,
                    Integer.MAX_VALUE,
                    null,
                    ItemStack.EMPTY,
                    (level, villager, tick) -> {
                      if (level.getGameTime() % STORM_TOLL_INTERVAL == 0) {
                        ring(level, villager, bell, 1.0F);
                      }
                    }))));
  }

  private static boolean aurora(Village v) {
    if (v.bell() == null || !SettlementsConfig.enabled(SettlementsConfig.AURORA)) {
      return false;
    }
    BellState state = bellState(v.level(), v.node());
    if (state.auroraDay != v.day()) {
      boolean forced = state.auroraForced;
      if (!forced && (!v.weather().aurora() || !v.between(AURORA_FROM, AURORA_TO))) {
        return false;
      }
      state.auroraForced = false;
      state.auroraDay = v.day();
      state.auroraEnd = v.gameTime() + AURORA_WALK + AURORA_SILENCE;
    }
    if (v.gameTime() >= state.auroraEnd) {
      return false;
    }
    if (v.gameTime() > state.auroraEnd - AURORA_SILENCE) {

      return true;
    }
    BlockPos bell = v.bell();
    Villager leader = leader(v);
    List<Villager> watchers = v.villagers();
    for (int i = 0; i < watchers.size(); i++) {
      Villager villager = watchers.get(i);
      if (Errands.doing(villager, Role.AURORA)) {
        continue;
      }
      boolean ringer = villager == leader;
      BlockPos spot = ringer ? bell : reachableSpot(v.level(), villager, bell, i, watchers.size());
      Errands.offer(
          v.level(),
          villager,
          new Errand(
              Role.AURORA,
              VillageLife.AURORA,
              state.auroraEnd,
              List.of(
                  new Step(
                      Errands.at(spot),
                      ringer ? 2 : 1,
                      0.5F,
                      Integer.MAX_VALUE,
                      Errands.at(villager.getEyePosition().add(2.0, 30.0, 0.0)),
                      ItemStack.EMPTY,
                      (level, watcher, tick) -> {
                        if (ringer && (tick == 0 || tick == 30 || tick == 60)) {
                          ring(level, watcher, bell, 1.0F);
                        }
                        watcher
                            .getBrain()
                            .setMemory(
                                MemoryModuleType.LOOK_TARGET,
                                Errands.at(watcher.getEyePosition().add(2.0, 30.0, 0.0)));
                      }))));
    }
    return true;
  }

  static BlockPos reachableSpot(
      ServerLevel level, Villager villager, BlockPos bell, int index, int count) {
    for (int k = 0; k < 4; k++) {
      BlockPos spot = ringSpot(level, bell, index + k * Math.max(1, count / 4), count);
      Path path = villager.getNavigation().createPath(spot, 1);
      if (path != null && path.canReach()) {
        return spot;
      }
    }
    Path path = villager.getNavigation().createPath(Places.ground(level, bell), 2);
    if (path == null || path.getEndNode() == null) {
      return ringSpot(level, bell, index, count);
    }

    return ringSpot(
        level, path.getEndNode().asBlockPos(), index, count, Math.min(4.0, 1.5 + count * 0.15));
  }

  static BlockPos ringSpot(ServerLevel level, BlockPos bell, int index, int count) {
    return ringSpot(level, bell, index, count, Math.min(7.0, 3.0 + count * 0.25));
  }

  static BlockPos ringSpot(ServerLevel level, BlockPos bell, int index, int count, double radius) {
    BlockPos ground = Places.ground(level, bell);
    double angle = 2.0 * Math.PI * index / Math.max(1, count) + 0.4;

    for (double r : new double[] {radius, radius + 1.5, radius - 1.0, radius + 3.0}) {
      for (double turn : new double[] {0.0, 0.25, -0.25}) {
        BlockPos around =
            ground.offset(
                (int) Math.round(Math.cos(angle + turn) * r),
                0,
                (int) Math.round(Math.sin(angle + turn) * r));
        BlockPos spot = Places.standable(level, around, 3);
        if (spot != null) {
          return spot;
        }
      }
    }
    return ground;
  }

  static void ring(ServerLevel level, Villager ringer, BlockPos bell, float pitch) {
    BlockState state = level.getBlockState(bell);
    if (!(state.getBlock() instanceof BellBlock)
        || !bell.closerToCenterThan(ringer.position(), 4.5)) {
      return;
    }
    Direction facing = state.getValue(BellBlock.FACING);
    level
        .getServer()
        .getPlayerList()
        .broadcast(
            null,
            bell.getX(),
            bell.getY(),
            bell.getZ(),
            64.0,
            level.dimension(),
            new ClientboundBlockEventPacket(bell, state.getBlock(), 1, facing.get3DDataValue()));
    level.playSound(null, bell, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 2.0F, pitch);
    level.gameEvent(ringer, GameEvent.BLOCK_CHANGE, bell);
  }

  private static BellState bellState(ServerLevel level, VillageNode node) {
    return BELLS
        .computeIfAbsent(level, ignored -> new HashMap<>())
        .computeIfAbsent(node.id(), ignored -> new BellState());
  }

  @SubscribeEvent(priority = EventPriority.LOWEST)
  public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
    if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) {
      return;
    }
    boolean bell = level.getBlockState(event.getPos()).getBlock() instanceof BellBlock;
    ItemStack item = event.getItemStack();
    boolean fire = item.is(Items.FLINT_AND_STEEL) || item.is(Items.FIRE_CHARGE);
    if (bell || fire) {
      helped(level, event.getEntity(), event.getPos());
    }
  }

  @SubscribeEvent(priority = EventPriority.LOWEST)
  public static void onPlace(BlockEvent.EntityPlaceEvent event) {
    if (!event.isCanceled()
        && event.getLevel() instanceof ServerLevel level
        && event.getEntity() instanceof Player player
        && event.getPlacedBlock().getLightEmission(level, event.getPos()) > 0) {
      helped(level, player, event.getPos());
    }
  }

  private static void helped(ServerLevel level, Player player, BlockPos pos) {
    VillageWorldManager manager = VillageWorldManager.get(level);
    VillageNode node = manager.villageAt(pos);
    if (node == null
        || node.bell() == null
        || node.structureBox() == null
        || !node.bell().closerThan(pos, HELP_RADIUS)
        || !GuestWeather.get(level, node.bell(), GuestTime.gameTime(level)).isSevere()) {
      return;
    }
    List<Villager> villagers = VillagePopulationScanner.findVillagers(level, node.structureBox());
    int out = Cartographers.away(level, node);
    for (Villager villager : villagers) {
      if (level.canSeeSky(villager.blockPosition().above())
          && !node.bell().closerToCenterThan(villager.position(), STORM_AWAY_DISTANCE)) {
        out++;
      }
    }
    long day = GuestTime.day(GuestTime.gameTime(level));
    Map<Long, Long> helped = HELPED.computeIfAbsent(level, ignored -> new HashMap<>());
    long key = GuestHash.hash(node.id(), player.getUUID().getMostSignificantBits());
    if (out == 0 || helped.getOrDefault(key, Long.MIN_VALUE) == day) {
      return;
    }
    helped.put(key, day);
    for (Villager villager : villagers) {
      villager
          .getGossips()
          .add(player.getUUID(), GossipType.MINOR_POSITIVE, GossipType.REPUTATION_CHANGE_PER_EVENT);
    }
    VillageMemory.record(level, pos, STORM_HELP, player, null, out);
  }
}
