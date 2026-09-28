package com.vortexso.guest_settlements.life;

import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_settlements.SettlementsConfig;
import com.vortexso.guest_settlements.life.Errands.Errand;
import com.vortexso.guest_settlements.life.Errands.Step;
import com.vortexso.guest_settlements.life.VillageLife.Role;
import com.vortexso.guest_settlements.life.VillageLife.Village;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

final class Company {
  static final long WORK_START = 2000;
  static final long WORK_END = 9000;

  private static final long MORNING_PLAY_START = 3000;
  private static final long MORNING_PLAY_END = 6000;
  private static final long EVENING_START = 10000;
  private static final long EVENING_END = Rites.CALL;
  private static final double PLAY_MOOD = 0.6;
  private static final int PLAYMATES = 3;
  private static final int PLAYGROUND = 24;
  private static final int CLOUDS_TICKS = 1200;

  private static final class TagGame {
    final List<Villager> players = new ArrayList<>();
    Villager it;
    long lastTag;
  }

  private static final Map<Villager, TagGame> GAMES = new WeakHashMap<>();

  private Company() {}

  static void tick(Village v) {
    if (!SettlementsConfig.enabled(SettlementsConfig.HELPERS) || v.weather().isSevere()) {
      return;
    }
    if (v.between(WORK_START, WORK_END)) {
      helpers(v);
    }
    boolean morning = v.between(MORNING_PLAY_START, MORNING_PLAY_END);
    boolean evening = v.between(EVENING_START, EVENING_END);
    if (evening) {
      reading(v);
    }
    for (Villager nitwit : v.adults(VillagerProfession.NITWIT)) {
      if (Errands.busy(nitwit)) {
        continue;
      }
      boolean playing =
          (morning || (evening && plays(v, nitwit)))
              && tag(v, nitwit, v.at(morning ? MORNING_PLAY_END : EVENING_END), morning);
      if (!playing && v.between(MORNING_PLAY_START, WORK_END)) {
        clouds(v, nitwit);
      }
    }
  }

  private static boolean plays(Village v, Villager villager) {
    return GuestHash.unit(v.hash(0x9A7L, villager.getUUID())) < PLAY_MOOD;
  }

  private static void helpers(Village v) {
    List<Villager> unemployed = new ArrayList<>();
    List<Villager> masters = new ArrayList<>();
    for (Villager villager : v.villagers()) {
      if (villager.isBaby()) {
        continue;
      }
      Holder<VillagerProfession> profession = villager.getVillagerData().profession();
      if (profession.is(VillagerProfession.NONE)) {
        unemployed.add(villager);
      } else if (!profession.is(VillagerProfession.NITWIT)
          && !Errands.doing(villager, Role.TRAVEL)) {
        masters.add(villager);
      }
    }
    unemployed.sort(Comparator.comparingLong(villager -> v.hash(0x4E1FL, villager.getUUID())));
    masters.sort(
        Comparator.comparingInt((Villager master) -> -need(v, master))
            .thenComparingLong(master -> v.hash(0x3A57L, master.getUUID())));
    long end = v.at(WORK_END);
    for (int i = 0; i < unemployed.size() && i < masters.size(); i++) {
      Villager helper = unemployed.get(i);
      Villager master = masters.get(i);
      Errand current = Errands.current(helper);
      if (current != null && current.role() == Role.HELPER) {
        if (current.step() != null
            && current.step().where()
                instanceof net.minecraft.world.entity.ai.behavior.EntityTracker tracker
            && tracker.getEntity() == master) {
          continue;
        }
        Errands.cancel(helper);
      }
      boolean friendly =
          GuestHash.unit(
                  GuestHash.hash(v.level().getSeed(), master.getUUID().getLeastSignificantBits()))
              < 0.6;
      Errands.offer(
          v.level(),
          helper,
          new Errand(
              Role.HELPER,
              VillageLife.SOCIAL,
              end,
              List.of(
                  new Step(
                      Errands.at(master),
                      2,
                      0.5F,
                      Integer.MAX_VALUE,
                      null,
                      new ItemStack(helperItem(master)),
                      (level, villager, tick) ->
                          mentor(level, master, villager, friendly, tick)))));
    }
  }

  private static int need(Village v, Villager master) {
    Holder<VillagerProfession> profession = master.getVillagerData().profession();
    if (profession.is(VillagerProfession.FARMER)
        && (v.season() == com.vortexso.guest_core.api.Season.AUTUMN
            || v.state().farmland()
                > v.adults(VillagerProfession.FARMER).size()
                    * SettlementsConfig.parameters().farmlandPerFarmer())) {
      return 3;
    }
    if (profession.is(VillagerProfession.MASON) && Construction.active(v.level(), v.node())) {
      return 3;
    }
    return Errands.busy(master) ? 1 : 0;
  }

  private static void mentor(
      ServerLevel level, Villager master, Villager helper, boolean friendly, int tick) {
    if (tick % 400 != 200 || master.distanceToSqr(helper) > 9.0) {
      return;
    }
    if (friendly) {
      master.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, Errands.at(helper));
      master.playSound(SoundEvents.VILLAGER_AMBIENT, 0.6F, 1.0F);
    } else {
      master.playSound(SoundEvents.VILLAGER_NO, 0.6F, 1.0F);
    }
  }

  static Item helperItem(Villager master) {
    Holder<VillagerProfession> p = master.getVillagerData().profession();
    if (p.is(VillagerProfession.FARMER)) return Items.WHEAT_SEEDS;
    if (p.is(VillagerProfession.FISHERMAN)) return Items.COD;
    if (p.is(VillagerProfession.LIBRARIAN)) return Items.BOOK;
    if (p.is(VillagerProfession.MASON)) return Items.COBBLESTONE;
    if (p.is(VillagerProfession.SHEPHERD)) return Items.WHITE_WOOL;
    if (p.is(VillagerProfession.BUTCHER)) return Items.WHEAT;
    if (p.is(VillagerProfession.CLERIC)) return Items.GLASS_BOTTLE;
    if (p.is(VillagerProfession.FLETCHER)) return Items.STICK;
    if (p.is(VillagerProfession.LEATHERWORKER)) return Items.LEATHER;
    if (p.is(VillagerProfession.CARTOGRAPHER)) return Items.PAPER;
    return Items.COAL;
  }

  private static boolean tag(Village v, Villager nitwit, long end, boolean morning) {
    List<Villager> kids =
        v.children().stream()
            .filter(
                child ->
                    !Errands.busy(child)
                        && (morning || plays(v, child))
                        && child.distanceToSqr(nitwit) < PLAYGROUND * PLAYGROUND)
            .sorted(Comparator.comparingDouble(nitwit::distanceToSqr))
            .limit(PLAYMATES)
            .toList();
    if (kids.isEmpty()) {
      return false;
    }
    TagGame game = new TagGame();
    game.players.add(nitwit);
    game.players.addAll(kids);
    game.it = nitwit;
    for (Villager player : game.players) {
      GAMES.put(player, game);
      Errands.offer(
          v.level(),
          player,
          new Errand(
                  Role.TAG,
                  VillageLife.SOCIAL,
                  end,
                  List.of(
                      Step.wait(
                          Integer.MAX_VALUE,
                          null,
                          (level, villager, tick) -> play(level, game, villager, tick))))
              .onEnd(() -> GAMES.remove(player)));
    }
    return true;
  }

  private static void play(ServerLevel level, TagGame game, Villager me, int tick) {
    game.players.removeIf(player -> !player.isAlive() || GAMES.get(player) != game);
    if (game.players.size() < 2) {
      Errands.cancel(me);
      return;
    }
    if (!game.players.contains(game.it)) {
      game.it = game.players.get(0);
    }
    var brain = me.getBrain();
    if (me == game.it) {
      Villager target =
          game.players.stream()
              .filter(player -> player != me)
              .min(Comparator.comparingDouble(me::distanceToSqr))
              .orElse(null);
      if (target == null) {
        return;
      }
      if (tick % 10 == 0) {
        brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, 0.6F, 0));
      }
      brain.setMemory(MemoryModuleType.LOOK_TARGET, Errands.at(target));
      long now = level.getGameTime();
      if (me.distanceToSqr(target) < 1.6 * 1.6 && now - game.lastTag > 40) {
        game.it = target;
        game.lastTag = now;
        target.getJumpControl().jump();
        me.getJumpControl().jump();
        target.playSound(SoundEvents.VILLAGER_YES, 0.8F, target.isBaby() ? 1.5F : 1.0F);
        level.sendParticles(
            ParticleTypes.HAPPY_VILLAGER,
            target.getX(),
            target.getEyeY(),
            target.getZ(),
            4,
            0.3,
            0.3,
            0.3,
            0.0);
      }
    } else {
      brain.setMemory(MemoryModuleType.LOOK_TARGET, Errands.at(game.it));

      if (tick % 20 == 0 && me.distanceToSqr(game.it) < 7 * 7) {
        Vec3 away = LandRandomPos.getPosAway(me, 8, 4, game.it.position());
        if (away != null && level.isVillage(BlockPos.containing(away))) {
          brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(away, 0.6F, 0));
        }
      }
      if (me.isBaby() && me.getRandom().nextInt(40) == 0) {
        me.getJumpControl().jump();
      }
    }
  }

  private static void clouds(Village v, Villager nitwit) {
    BlockPos spot =
        Rites.ringSpot(
            v.level(),
            v.center(),
            (int) Math.floorMod(v.hash(0xC10DL, nitwit.getUUID()), 8L),
            8,
            6.0);
    if (spot == null || !v.level().canSeeSky(spot)) {
      return;
    }
    Errands.offer(
        v.level(),
        nitwit,
        new Errand(
            Role.CLOUDS,
            VillageLife.SOCIAL,
            v.gameTime() + CLOUDS_TICKS + 600,
            List.of(
                Step.at(spot, 1, CLOUDS_TICKS, ItemStack.EMPTY, Errands.Action.NONE)
                    .looking(Errands.at(Vec3.atCenterOf(spot).add(3.0, 25.0, 1.0))))));
  }

  private static void reading(Village v) {
    List<Villager> librarians = v.adults(VillagerProfession.LIBRARIAN);
    if (librarians.isEmpty()) {
      return;
    }
    Villager librarian = librarians.get(0);
    BlockPos lectern =
        librarian.getBrain().getMemory(MemoryModuleType.JOB_SITE).map(GlobalPos::pos).orElse(null);
    BlockPos seat = lectern == null ? null : Places.beside(v.level(), lectern);
    if (seat == null) {
      seat = Rites.ringSpot(v.level(), v.center(), 0, 1);
    }
    long end = v.at(EVENING_END);
    if (!Errands.doing(librarian, Role.READING)) {
      Errands.offer(
          v.level(),
          librarian,
          new Errand(
              Role.READING,
              VillageLife.SOCIAL + 1,
              end,
              List.of(
                  Step.at(
                      seat,
                      1,
                      Integer.MAX_VALUE,
                      new ItemStack(Items.WRITABLE_BOOK),
                      (level, villager, tick) -> {
                        if (tick % 60 == 0) {
                          villager.playSound(SoundEvents.BOOK_PAGE_TURN, 1.0F, 1.0F);
                        }
                        if (tick % 140 == 70) {
                          villager.playSound(SoundEvents.VILLAGER_AMBIENT, 0.7F, 0.9F);
                          level.sendParticles(
                              ParticleTypes.ENCHANT,
                              villager.getX(),
                              villager.getEyeY() + 0.3,
                              villager.getZ(),
                              6,
                              0.4,
                              0.3,
                              0.4,
                              0.2);
                        }
                      }))));
    }
    List<Villager> listeners = new ArrayList<>();
    for (Villager villager : v.villagers()) {
      boolean child = villager.isBaby();
      boolean nitwit = villager.getVillagerData().profession().is(VillagerProfession.NITWIT);
      if ((child || nitwit) && !plays(v, villager) && !Errands.busy(villager)) {
        listeners.add(villager);
      }
    }
    BlockPos center = seat;
    for (int i = 0; i < listeners.size(); i++) {
      BlockPos spot = Rites.ringSpot(v.level(), center, i, Math.max(6, listeners.size()), 2.5);
      Villager listener = listeners.get(i);
      Errands.offer(
          v.level(),
          listener,
          new Errand(
              Role.LISTENING,
              VillageLife.SOCIAL,
              end,
              List.of(
                  Step.at(spot, 1, Integer.MAX_VALUE, ItemStack.EMPTY, Errands.Action.NONE)
                      .looking(Errands.at(librarian)))));
    }
  }
}
