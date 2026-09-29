package com.vortexso.guest_settlements.life;

import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.Season;
import com.vortexso.guest_settlements.GuestSettlements;
import com.vortexso.guest_settlements.SettlementsConfig;
import com.vortexso.guest_settlements.life.Errands.Action;
import com.vortexso.guest_settlements.life.Errands.Errand;
import com.vortexso.guest_settlements.life.Errands.Step;
import com.vortexso.guest_settlements.life.VillageLife.Role;
import com.vortexso.guest_settlements.life.VillageLife.Village;
import com.vortexso.guest_settlements.village.VillageFarmRegion;
import com.vortexso.guest_settlements.village.VillageSimulationParameters;
import com.vortexso.guest_settlements.village.VillageSimulator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

final class Work {
  static final long WORK_START = Company.WORK_START;
  static final long WORK_END = Company.WORK_END;
  private static final long FISHING_END = 6500;
  private static final long EVENING_START = 10000;
  private static final long EVENING_END = Rites.CALL;
  private static final int SHORE_RADIUS = 48;
  private static final int FIELD_MARGIN = 3;
  private static final int RESTORE_PER_FARMER = 8;
  private static final int PASTURE = 32;
  private static final int HERD = 16;
  private static final int EXCHANGE_TIMEOUT = 1200;
  private static final int DRIFT_TRIES = 8;
  private static final int DRIFT_RETRY = 1200;

  private record TradeRoute(
      ResourceKey<VillagerProfession> supplier,
      ResourceKey<VillagerProfession> consumer,
      Item item) {}

  private static final List<TradeRoute> ROUTES =
      List.of(
          new TradeRoute(VillagerProfession.FARMER, VillagerProfession.BUTCHER, Items.WHEAT),
          new TradeRoute(VillagerProfession.MASON, VillagerProfession.TOOLSMITH, Items.STONE),
          new TradeRoute(VillagerProfession.MASON, VillagerProfession.ARMORER, Items.COAL),
          new TradeRoute(VillagerProfession.MASON, VillagerProfession.WEAPONSMITH, Items.COAL),
          new TradeRoute(VillagerProfession.FISHERMAN, VillagerProfession.LIBRARIAN, Items.INK_SAC),
          new TradeRoute(VillagerProfession.CLERIC, VillagerProfession.LIBRARIAN, Items.CANDLE),
          new TradeRoute(
              VillagerProfession.BUTCHER, VillagerProfession.LEATHERWORKER, Items.LEATHER));

  private static final long EVENT_TRADE = 0x7EADL;

  private static final Map<ServerLevel, Map<Long, Long>> NO_SHORE = new WeakHashMap<>();

  private static final Map<Villager, Long> LIVESTOCK_DAY = new WeakHashMap<>();

  private static final Map<ServerLevel, Map<Long, long[]>> TILLED = new WeakHashMap<>();

  private static final Map<Villager, Long> NO_DRIFT = new WeakHashMap<>();

  private Work() {}

  static void tick(Village v) {
    if (!SettlementsConfig.enabled(SettlementsConfig.PROFESSION_DAYS)) {
      return;
    }
    if (v.between(WORK_START, WORK_END)) {
      fishing(v);
      tilling(v);
      livestock(v);
      shearing(v);
      herbs(v);
      sticks(v);
      smiths(v);
      repairs(v);
      if (SettlementsConfig.enabled(SettlementsConfig.TRADE_SCENES)) {
        trades(v);
      }
      snow(v);
    } else if (v.between(WORK_END, EVENING_START)) {
      backlog(v);
    } else if (v.between(EVENING_START, EVENING_END)) {
      bait(v);
      herding(v);
    }
  }

  private static boolean idle(Villager villager) {
    return !Errands.busy(villager) && Errands.available(villager) && !villager.isSleeping();
  }

  private static @Nullable BlockPos jobSite(Villager villager) {
    return villager
        .getBrain()
        .getMemory(MemoryModuleType.JOB_SITE)
        .map(GlobalPos::pos)
        .orElse(null);
  }

  private static void fishing(Village v) {
    if (!v.between(WORK_START, FISHING_END - 1200)) {
      return;
    }
    for (Villager fisher : v.adults(VillagerProfession.FISHERMAN)) {
      BlockPos[] shore = idle(fisher) ? shore(v, fisher) : null;
      if (shore == null) {
        continue;
      }
      BlockPos stand = shore[0];
      BlockPos water = shore[1];
      Direction out =
          Direction.getApproximateNearest(
              water.getX() - stand.getX(), 0.0, water.getZ() - stand.getZ());
      BlockPos bobber =
          Places.isWater(v.level(), water.relative(out, 2)) ? water.relative(out, 2) : water;
      Item fish = fish(v, bobber, fisher);
      double bitesPerTick = biteRate(v, bobber);
      int[] caught = {0};
      int fishingTicks = (int) Math.max(1200, v.at(FISHING_END) - v.gameTime());
      List<Villager> butchers = v.adults(VillagerProfession.BUTCHER);
      Villager butcher = butchers.isEmpty() ? null : butchers.get(0);
      Errands.offer(
          v.level(),
          fisher,
          new Errand(
                  Role.FISHING,
                  VillageLife.WORK,
                  v.gameTime() + fishingTicks + 3600,
                  List.of(
                      new Step(
                          Errands.at(stand),
                          0,
                          0.5F,
                          fishingTicks,
                          Errands.at(Vec3.atCenterOf(bobber)),
                          new ItemStack(Items.FISHING_ROD),
                          (level, villager, tick) ->
                              fish(level, villager, bobber, fish, bitesPerTick, caught, tick))))
              .onEnd(
                  () -> {
                    if (caught[0] > 0 && fisher.isAlive()) {
                      ItemStack catchStack = new ItemStack(fish, Math.min(caught[0], 64));
                      if (butcher != null && butcher.isAlive()) {
                        handOver(v.level(), fisher, butcher, catchStack, Role.FISHING, false);
                      } else {
                        carryTo(v.level(), fisher, v.center(), catchStack, Role.FISHING);
                      }
                    }
                  }));
    }
  }

  private static BlockPos @Nullable [] shore(Village v, Villager fisher) {
    Map<Long, Long> none = NO_SHORE.computeIfAbsent(v.level(), ignored -> new HashMap<>());
    if (none.getOrDefault(v.node().id(), Long.MIN_VALUE) == v.day()) {
      return null;
    }
    BlockPos[] shore =
        Places.shore(
            v.level(),
            v.center(),
            SHORE_RADIUS,
            VillageLife.reach(v.box()),
            fisher.getUUID().getMostSignificantBits());
    if (shore == null) {
      none.put(v.node().id(), v.day());
    }
    return shore;
  }

  private static void fish(
      ServerLevel level,
      Villager fisher,
      BlockPos bobber,
      Item fish,
      double bitesPerTick,
      int[] caught,
      int tick) {
    Vec3 at = Vec3.atCenterOf(bobber).add(0.0, 0.45, 0.0);
    if (tick == 0) {
      level.playSound(
          null, fisher, SoundEvents.FISHING_BOBBER_THROW, SoundSource.NEUTRAL, 0.5F, 0.4F);
    }
    if (tick % 15 == 0) {
      level.sendParticles(ParticleTypes.FISHING, at.x, at.y, at.z, 1, 0.05, 0.0, 0.05, 0.0);
    }
    long bite = GuestHash.hash(level.getSeed(), fisher.getId(), level.getGameTime() / 20);
    boolean biting = GuestHash.unit(bite) < bitesPerTick * 20.0;
    if (level.getGameTime() % 20 == 0 && biting) {
      caught[0]++;
      level.playSound(
          null, bobber, SoundEvents.FISHING_BOBBER_SPLASH, SoundSource.NEUTRAL, 0.35F, 1.0F);
      level.sendParticles(ParticleTypes.SPLASH, at.x, at.y, at.z, 12, 0.3, 0.1, 0.3, 0.0);
      level.sendParticles(ParticleTypes.BUBBLE, at.x, at.y - 0.4, at.z, 6, 0.2, 0.1, 0.2, 0.05);
      level.playSound(
          null, fisher, SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.NEUTRAL, 0.5F, 1.0F);
    }

    if (biting && level.getGameTime() % 20 < 10) {
      Errands.hold(fisher, new ItemStack(fish));
    }
  }

  private static double biteRate(Village v, BlockPos water) {
    double rate = 1.0 / 700.0;
    rate *=
        switch (v.season()) {
          case SPRING, AUTUMN -> 1.5;
          case SUMMER -> 1.0;
          case WINTER -> 0.5;
        };
    if (v.weather().type().isRain()) {
      rate *= 1.25;
    }
    return rate;
  }

  private static Item fish(Village v, BlockPos water, Villager fisher) {
    var biome = v.level().getBiome(water);
    double roll = GuestHash.unit(v.hash(0xF15L, fisher.getUUID()));
    if (roll < 0.05) {
      return Items.PUFFERFISH;
    }
    if (biome.is(BiomeTags.IS_RIVER)
        || biome.value().coldEnoughToSnow(water, v.level().getSeaLevel())) {
      return Items.SALMON;
    }
    if (biome.is(BiomeTags.IS_OCEAN) && biome.value().getBaseTemperature() > 0.8F && roll < 0.35) {
      return Items.TROPICAL_FISH;
    }
    return Items.COD;
  }

  private static void bait(Village v) {
    if (v.weather().isSevere()) {
      return;
    }
    for (Villager fisher : v.adults(VillagerProfession.FISHERMAN)) {
      BlockPos[] shore = idle(fisher) ? shore(v, fisher) : null;
      if (shore == null) {
        continue;
      }
      BlockPos ground = shore[0].below();
      BlockState soil = v.level().getBlockState(ground);
      Errands.offer(
          v.level(),
          fisher,
          new Errand(
              Role.BAIT,
              VillageLife.WORK,
              v.at(EVENING_END),
              List.of(
                  Step.at(
                          shore[0],
                          1,
                          240,
                          new ItemStack(Items.WOODEN_SHOVEL),
                          (level, villager, tick) -> {
                            if (tick % 40 == 20) {
                              level.playSound(
                                  null,
                                  ground,
                                  SoundEvents.ROOTED_DIRT_BREAK,
                                  SoundSource.NEUTRAL,
                                  0.6F,
                                  1.0F);
                              level.sendParticles(
                                  new BlockParticleOption(ParticleTypes.BLOCK, soil),
                                  ground.getX() + 0.5,
                                  ground.getY() + 1.0,
                                  ground.getZ() + 0.5,
                                  8,
                                  0.3,
                                  0.1,
                                  0.3,
                                  0.1);
                            }
                          })
                      .looking(Errands.at(Vec3.atCenterOf(ground))))));
    }
  }

  private static void tilling(Village v) {
    if (!SettlementsConfig.enabled(SettlementsConfig.FARMER_TILLING)) {
      return;
    }
    List<Villager> farmers = v.adults(VillagerProfession.FARMER);
    if (farmers.isEmpty()) {
      return;
    }
    long[] today =
        TILLED
            .computeIfAbsent(v.level(), ignored -> new HashMap<>())
            .computeIfAbsent(v.node().id(), ignored -> new long[] {Long.MIN_VALUE, 0, 0});
    if (today[0] != v.day()) {
      today[0] = v.day();
      today[1] = 0;
      today[2] = 0;
    }
    int extendQuota =
        needsMoreFields(v) && v.season() != Season.WINTER
            ? (int)
                Math.round(
                    farmers.size() * SettlementsConfig.value(SettlementsConfig.TILL_PER_FARMER))
            : 0;
    java.util.Set<BlockPos> claimed = new java.util.HashSet<>();
    for (Villager farmer : farmers) {
      Errand busy = Errands.current(farmer);
      if (busy != null
          && busy.role() == Role.TILLING
          && busy.step() != null
          && busy.step().where() != null) {
        claimed.add(busy.step().where().currentBlockPosition());
      }
    }
    for (Villager farmer : farmers) {
      if (!idle(farmer)) {
        continue;
      }
      BlockPos soil = null;
      boolean extension = false;
      for (VillageFarmRegion region : v.node().farmRegions()) {
        if (region.farmBox() == null) {
          continue;
        }
        if (today[1] < (long) RESTORE_PER_FARMER * farmers.size()) {
          soil = tillable(v.level(), region.pieceBox(), region.pieceBox(), farmer, claimed, 1);
        }
        if (soil == null && today[2] < extendQuota) {

          soil =
              tillable(
                  v.level(),
                  region.pieceBox().inflatedBy(FIELD_MARGIN, 0, FIELD_MARGIN),
                  region.pieceBox(),
                  farmer,
                  claimed,
                  2);
          extension = soil != null;
        }
        if (soil != null) {
          break;
        }
      }
      if (soil == null) {
        return;
      }
      today[extension ? 2 : 1]++;
      claimed.add(soil);
      BlockPos target = soil;
      BlockState crop = cropNextTo(v.level(), target);
      Errands.offer(
          v.level(),
          farmer,
          new Errand(
              Role.TILLING,
              VillageLife.WORK,
              v.gameTime() + 1800,
              List.of(
                  Step.at(
                          target,
                          1,
                          50,
                          new ItemStack(Items.IRON_HOE),
                          (level, villager, tick) -> till(level, v, target, crop, tick))
                      .looking(Errands.at(Vec3.atCenterOf(target))))));
    }
  }

  private static boolean needsMoreFields(Village v) {
    VillageSimulationParameters parameters = SettlementsConfig.parameters();
    int farmers = v.state().villagePopulation().professionCount(VillageSimulator.FARMER);
    VillageSimulator.Rates rates = VillageSimulator.rates(v.state(), 0.0, 1.0, v.day(), parameters);
    return v.state().farmland() < farmers * parameters.farmlandPerFarmer()
        || rates.production() < 2.0 * rates.consumption();
  }

  private static @Nullable BlockPos tillable(
      ServerLevel level,
      BoundingBox area,
      BoundingBox field,
      Villager farmer,
      java.util.Set<BlockPos> claimed,
      int reach) {
    BlockPos best = null;
    double bestDistance = Double.MAX_VALUE;
    for (int x = area.minX(); x <= area.maxX(); x++) {
      for (int z = area.minZ(); z <= area.maxZ(); z++) {
        BlockPos top = Places.top(level, x, z);
        if (top == null || top.getY() < field.minY() - 1 || top.getY() > field.maxY()) {
          continue;
        }
        BlockState state = level.getBlockState(top);
        if (!(state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK))
            || claimed.contains(top)
            || Errands.unreachable(level, top)
            || !level.getBlockState(top.above()).canBeReplaced()
            || !nearFarmland(level, top, reach)
            || !moist(level, top)) {
          continue;
        }
        double distance = farmer.distanceToSqr(Vec3.atCenterOf(top));
        if (distance < bestDistance) {
          bestDistance = distance;
          best = top;
        }
      }
    }
    return best;
  }

  private static boolean nearFarmland(ServerLevel level, BlockPos pos, int reach) {
    for (BlockPos near :
        BlockPos.betweenClosed(pos.offset(-reach, 0, -reach), pos.offset(reach, 0, reach))) {
      if (level.getBlockState(near).is(Blocks.FARMLAND)) {
        return true;
      }
    }
    return false;
  }

  private static boolean moist(ServerLevel level, BlockPos pos) {
    for (BlockPos near : BlockPos.betweenClosed(pos.offset(-4, 0, -4), pos.offset(4, 1, 4))) {
      if (Places.isWater(level, near)) {
        return true;
      }
    }
    return false;
  }

  private static BlockState cropNextTo(ServerLevel level, BlockPos soil) {
    for (Direction side : Direction.Plane.HORIZONTAL) {
      BlockState above = level.getBlockState(soil.relative(side).above());
      if (above.getBlock() instanceof CropBlock crop) {
        return crop.defaultBlockState();
      }
    }
    return Blocks.WHEAT.defaultBlockState();
  }

  private static void till(ServerLevel level, Village v, BlockPos soil, BlockState crop, int tick) {
    if (tick == 20) {
      BlockState state = level.getBlockState(soil);
      if (!(state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK))) {
        return;
      }
      if (!level.getBlockState(soil.above()).isAir()) {
        level.destroyBlock(soil.above(), false);
      }
      level.setBlockAndUpdate(
          soil,
          Blocks.FARMLAND
              .defaultBlockState()
              .setValue(FarmlandBlock.MOISTURE, FarmlandBlock.MAX_MOISTURE));
      level.playSound(null, soil, SoundEvents.HOE_TILL, SoundSource.BLOCKS, 1.0F, 1.0F);
      level.sendParticles(
          new BlockParticleOption(ParticleTypes.BLOCK, state),
          soil.getX() + 0.5,
          soil.getY() + 1.0,
          soil.getZ() + 0.5,
          10,
          0.3,
          0.1,
          0.3,
          0.1);
      v.manager().handleBlockChange(soil);
    } else if (tick == 40
        && level.getBlockState(soil).is(Blocks.FARMLAND)
        && level.getBlockState(soil.above()).isAir()) {
      level.setBlockAndUpdate(soil.above(), crop);
      level.playSound(null, soil, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1.0F, 1.0F);
    }
  }

  private static void livestock(Village v) {
    for (Villager butcher : v.adults(VillagerProfession.BUTCHER)) {
      if (!idle(butcher) || LIVESTOCK_DAY.getOrDefault(butcher, Long.MIN_VALUE) == v.day()) {
        continue;
      }
      List<Animal> herd = herd(v);
      int capacity = Math.max(4, v.state().adults());
      long phase = Math.floorMod(v.day() + butcher.getUUID().getLeastSignificantBits(), 4L);
      BlockPos smoker = jobSite(butcher);
      if (phase == 0
          && herd.size() > capacity
          && SettlementsConfig.enabled(SettlementsConfig.LIVESTOCK_CULLING)) {
        Animal animal =
            herd.stream()
                .filter(a -> !a.isBaby())
                .min(Comparator.comparingDouble(butcher::distanceToSqr))
                .orElse(null);
        if (animal == null) {
          continue;
        }
        ItemStack[] meat = {ItemStack.EMPTY};
        List<Step> steps = new ArrayList<>();
        steps.add(
            new Step(
                Errands.at(animal),
                1,
                0.5F,
                30,
                null,
                new ItemStack(Items.IRON_AXE),
                (level, villager, tick) -> {
                  if (tick == 25 && animal.isAlive() && animal.distanceToSqr(villager) < 9.0) {
                    animal.hurtServer(level, level.damageSources().mobAttack(villager), 1000.0F);
                  }
                }));

        steps.add(
            Step.wait(
                20,
                null,
                (level, villager, tick) -> {
                  if (tick == 19) {
                    meat[0] = collectDrops(level, villager);
                  }
                }));
        if (smoker != null) {
          steps.add(
              Step.at(
                  smoker,
                  1,
                  40,
                  ItemStack.EMPTY,
                  (level, villager, tick) -> {
                    if (tick == 0 && !meat[0].isEmpty()) {
                      Errands.hold(villager, meat[0]);
                    }
                  }));
        }
        LIVESTOCK_DAY.put(butcher, v.day());
        Errands.offer(
            v.level(),
            butcher,
            new Errand(Role.CULLING, VillageLife.WORK, v.gameTime() + 2400, steps));
      } else if (phase % 2 == 1 && herd.size() < capacity) {
        List<Animal> pair = breedingPair(herd);
        if (pair == null) {
          continue;
        }
        List<Step> steps = new ArrayList<>();
        for (Animal animal : pair) {
          steps.add(
              new Step(
                  Errands.at(animal),
                  1,
                  0.5F,
                  30,
                  null,
                  new ItemStack(feed(animal)),
                  (level, villager, tick) -> {
                    if (tick == 20 && animal.isAlive() && animal.canFallInLove()) {
                      animal.setInLove(null);
                      level.playSound(
                          null,
                          animal,
                          SoundEvents.GENERIC_EAT.value(),
                          SoundSource.NEUTRAL,
                          0.6F,
                          1.0F);
                    }
                  }));
        }
        LIVESTOCK_DAY.put(butcher, v.day());
        Errands.offer(
            v.level(),
            butcher,
            new Errand(Role.FEEDING, VillageLife.WORK, v.gameTime() + 2400, steps));
      }
    }
  }

  private static ItemStack collectDrops(ServerLevel level, Villager villager) {
    ItemStack shown = ItemStack.EMPTY;
    for (net.minecraft.world.entity.item.ItemEntity drop :
        level.getEntitiesOfClass(
            net.minecraft.world.entity.item.ItemEntity.class,
            villager.getBoundingBox().inflate(3.0))) {
      if (shown.isEmpty() && drop.getItem().has(net.minecraft.core.component.DataComponents.FOOD)) {
        shown = drop.getItem().copyWithCount(1);
      }
      villager.take(drop, drop.getItem().getCount());
      drop.discard();
    }
    return shown;
  }

  private static List<Animal> herd(Village v) {
    return v.level()
        .getEntitiesOfClass(
            Animal.class,
            VillageLife.reach(v.box()),
            animal ->
                animal instanceof Cow
                    || animal instanceof Pig
                    || animal instanceof Sheep
                    || animal instanceof Chicken);
  }

  private static @Nullable List<Animal> breedingPair(List<Animal> herd) {
    Map<Class<?>, List<Animal>> byKind = new HashMap<>();
    for (Animal animal : herd) {
      if (!animal.isBaby() && animal.canFallInLove() && animal.getAge() == 0) {
        byKind.computeIfAbsent(animal.getClass(), ignored -> new ArrayList<>()).add(animal);
      }
    }
    for (List<Animal> kind : byKind.values()) {
      if (kind.size() >= 2) {
        return List.of(kind.get(0), kind.get(1));
      }
    }
    return null;
  }

  private static Item feed(Animal animal) {
    if (animal instanceof Pig) {
      return Items.CARROT;
    }
    return animal instanceof Chicken ? Items.WHEAT_SEEDS : Items.WHEAT;
  }

  private static void shearing(Village v) {
    for (Villager shepherd : v.adults(VillagerProfession.SHEPHERD)) {
      if (!idle(shepherd)) {
        continue;
      }
      Sheep sheep =
          v
              .level()
              .getEntitiesOfClass(Sheep.class, VillageLife.reach(v.box()), Sheep::readyForShearing)
              .stream()
              .min(Comparator.comparingDouble(shepherd::distanceToSqr))
              .orElse(null);
      if (sheep == null) {
        continue;
      }
      BlockPos loom = jobSite(shepherd);
      ItemStack wool = new ItemStack(wool(v.level(), sheep));
      List<Step> steps = new ArrayList<>();
      steps.add(
          new Step(
              Errands.at(sheep),
              1,
              0.5F,
              40,
              null,
              new ItemStack(Items.SHEARS),
              (level, villager, tick) -> {
                if (tick == 30 && sheep.isAlive() && sheep.readyForShearing()) {
                  sheep.setSheared(true);
                  level.playSound(
                      null, sheep, SoundEvents.SHEEP_SHEAR, SoundSource.NEUTRAL, 1.0F, 1.0F);
                }
              }));
      if (loom != null) {
        steps.add(Step.at(loom, 1, 40, wool, Action.NONE));
      }
      Errands.offer(
          v.level(),
          shepherd,
          new Errand(Role.SHEARING, VillageLife.WORK, v.gameTime() + 2400, steps));
    }
  }

  private static Item wool(ServerLevel level, Sheep sheep) {
    return net.minecraft.core.registries.BuiltInRegistries.ITEM
        .getOptional(
            net.minecraft.resources.Identifier.withDefaultNamespace(
                sheep.getColor().getName() + "_wool"))
        .orElse(Items.WHITE_WOOL);
  }

  private static void herding(Village v) {
    if (v.weather().isSevere()) {
      return;
    }
    for (Villager shepherd : v.adults(VillagerProfession.SHEPHERD)) {
      if (!idle(shepherd)) {
        continue;
      }
      List<Sheep> flock =
          v.level()
              .getEntitiesOfClass(
                  Sheep.class, VillageLife.reach(v.box()).inflate(PASTURE, 0, PASTURE));
      BlockPos pen = jobSite(shepherd);
      if (flock.isEmpty() || pen == null) {
        continue;
      }
      Sheep lead =
          flock.stream()
              .max(Comparator.comparingDouble(sheep -> sheep.distanceToSqr(Vec3.atCenterOf(pen))))
              .orElseThrow();
      if (lead.distanceToSqr(Vec3.atCenterOf(pen)) < HERD * HERD / 4.0) {
        continue;
      }
      Errands.offer(
          v.level(),
          shepherd,
          new Errand(
              Role.HERDING,
              VillageLife.WORK,
              v.at(EVENING_END),
              List.of(
                  new Step(
                      Errands.at(lead), 2, 0.5F, 20, null, new ItemStack(Items.WHEAT), Action.NONE),
                  new Step(
                      Errands.at(pen),
                      2,
                      0.4F,
                      200,
                      null,
                      null,
                      (level, villager, tick) -> follow(level, villager)))));
    }
  }

  private static void follow(ServerLevel level, Villager shepherd) {
    if (level.getGameTime() % 20 != 0) {
      return;
    }
    for (Sheep sheep :
        level.getEntitiesOfClass(Sheep.class, shepherd.getBoundingBox().inflate(HERD, 6, HERD))) {
      if (sheep.distanceToSqr(shepherd) > 9.0) {
        sheep.getNavigation().moveTo(shepherd, 1.0);
      }
    }
  }

  private static void herbs(Village v) {
    if (v.weekday() == Rites.FULL_MOON
        || v.weekday() == Rites.NEW_MOON
        || !v.between(WORK_START, 6000)) {
      return;
    }
    for (Villager cleric : v.adults(VillagerProfession.CLERIC)) {
      BlockPos stand = jobSite(cleric);
      if (!idle(cleric) || stand == null) {
        continue;
      }
      var reach = VillageLife.reach(v.box());
      List<BlockPos> plants =
          Places.ring(
              v.level(),
              v.center(),
              8,
              radius(v.box()) + 24,
              3,
              cleric.getUUID().getMostSignificantBits() ^ v.day(),
              1,
              top ->
                  Places.inside(reach, top)
                      && !v.box().isInside(top)
                      && (v.level().getBlockState(top.above()).is(BlockTags.FLOWERS)
                          || v.level().getBlockState(top.above()).is(Blocks.SHORT_GRASS)));
      if (plants.isEmpty()) {
        continue;
      }
      BlockPos plant = plants.get(0).above();
      BlockState herb = v.level().getBlockState(plant);
      ItemStack picked =
          new ItemStack(
              herb.getBlock().asItem() == Items.AIR ? Items.SHORT_GRASS : herb.getBlock().asItem());
      Errands.offer(
          v.level(),
          cleric,
          new Errand(
              Role.HERBS,
              VillageLife.WORK,
              v.gameTime() + 3600,
              List.of(
                  Step.at(
                          plant,
                          1,
                          80,
                          ItemStack.EMPTY,
                          (level, villager, tick) -> {
                            if (tick % 20 == 10) {
                              level.playSound(
                                  null,
                                  plant,
                                  SoundEvents.GRASS_BREAK,
                                  SoundSource.BLOCKS,
                                  0.5F,
                                  1.2F);
                              level.sendParticles(
                                  new BlockParticleOption(ParticleTypes.BLOCK, herb),
                                  plant.getX() + 0.5,
                                  plant.getY() + 0.4,
                                  plant.getZ() + 0.5,
                                  5,
                                  0.2,
                                  0.2,
                                  0.2,
                                  0.05);
                            }
                          })
                      .looking(Errands.at(Vec3.atCenterOf(plant))),
                  Step.at(
                      stand,
                      1,
                      80,
                      picked,
                      (level, villager, tick) -> {
                        if (tick == 40) {
                          level.playSound(
                              null,
                              stand,
                              SoundEvents.BREWING_STAND_BREW,
                              SoundSource.BLOCKS,
                              0.8F,
                              1.0F);
                        }
                      }))));
    }
  }

  private static void sticks(Village v) {
    if (!v.between(WORK_START, 6000)) {
      return;
    }
    for (Villager fletcher : v.adults(VillagerProfession.FLETCHER)) {
      BlockPos table = jobSite(fletcher);
      if (!idle(fletcher) || table == null || Math.floorMod(v.day() + fletcher.getId(), 2) != 0) {
        continue;
      }
      BlockPos log =
          Places.tree(
              v.level(),
              v.center(),
              radius(v.box()) + 24,
              VillageLife.reach(v.box()),
              v.box(),
              fletcher.getUUID().getLeastSignificantBits() ^ v.day());
      if (log == null) {
        continue;
      }
      Errands.offer(
          v.level(),
          fletcher,
          new Errand(
              Role.GATHERING,
              VillageLife.WORK,
              v.gameTime() + 3600,
              List.of(
                  Step.at(
                          log,
                          2,
                          60,
                          ItemStack.EMPTY,
                          (level, villager, tick) -> {
                            if (tick % 20 == 5) {
                              BlockPos leaves = log.above(3);
                              level.playSound(
                                  null,
                                  leaves,
                                  SoundEvents.AZALEA_LEAVES_BREAK,
                                  SoundSource.BLOCKS,
                                  0.6F,
                                  1.0F);
                              level.sendParticles(
                                  new BlockParticleOption(
                                      ParticleTypes.BLOCK,
                                      level.getBlockState(leaves).isAir()
                                          ? Blocks.OAK_LEAVES.defaultBlockState()
                                          : level.getBlockState(leaves)),
                                  leaves.getX() + 0.5,
                                  leaves.getY(),
                                  leaves.getZ() + 0.5,
                                  8,
                                  0.8,
                                  0.4,
                                  0.8,
                                  0.05);
                            }
                          })
                      .looking(Errands.at(Vec3.atCenterOf(log.above(3)))),
                  Step.at(table, 1, 60, new ItemStack(Items.STICK), Action.NONE))));
    }
  }

  private static void smiths(Village v) {
    if (v.weekday() != Rites.FULL_MOON) {
      return;
    }
    for (Villager smith : smithsOf(v)) {
      if (!idle(smith)) {
        continue;
      }
      BlockPos spot =
          Rites.ringSpot(v.level(), v.center(), (int) Math.floorMod(smith.getId(), 8), 8);
      Errands.offer(
          v.level(),
          smith,
          new Errand(
              Role.SERVICE,
              VillageLife.WORK,
              v.at(WORK_END),
              List.of(Step.at(spot, 2, Integer.MAX_VALUE, ItemStack.EMPTY, Action.NONE))));
    }
  }

  private static void backlog(Village v) {
    if (v.weekday() != Rites.FULL_MOON + 1 || v.weather().isSevere()) {
      return;
    }
    for (Villager smith : smithsOf(v)) {
      BlockPos forge = jobSite(smith);
      if (!idle(smith) || forge == null) {
        continue;
      }
      boolean armorer = smith.getVillagerData().profession().is(VillagerProfession.ARMORER);
      Errands.offer(
          v.level(),
          smith,
          new Errand(
              Role.BACKLOG,
              VillageLife.WORK,
              v.at(EVENING_START),
              List.of(
                  Step.at(
                      forge,
                      1,
                      Integer.MAX_VALUE,
                      new ItemStack(tool(smith)),
                      (level, villager, tick) -> {
                        if (tick % 50 == 0) {
                          level.playSound(
                              null,
                              forge,
                              armorer ? SoundEvents.ANVIL_USE : SoundEvents.GRINDSTONE_USE,
                              SoundSource.BLOCKS,
                              0.5F,
                              1.0F);
                          level.sendParticles(
                              ParticleTypes.CRIT,
                              forge.getX() + 0.5,
                              forge.getY() + 1.1,
                              forge.getZ() + 0.5,
                              4,
                              0.2,
                              0.1,
                              0.2,
                              0.1);
                        }
                      }))));
    }
  }

  private static List<Villager> smithsOf(Village v) {
    List<Villager> smiths = new ArrayList<>(v.adults(VillagerProfession.ARMORER));
    smiths.addAll(v.adults(VillagerProfession.WEAPONSMITH));
    return smiths;
  }

  private static void repairs(Village v) {
    List<Villager> toolsmiths = v.adults(VillagerProfession.TOOLSMITH);
    if (toolsmiths.isEmpty()) {
      return;
    }
    long hash = GuestHash.hash(v.level().getSeed(), v.node().id(), v.day(), 0x7001L);
    long start =
        WORK_START + (long) (GuestHash.unit(hash) * (WORK_END - WORK_START - EXCHANGE_TIMEOUT));
    if (start <= v.tickOfDay() - VillageLife.INTERVAL || start > v.tickOfDay()) {
      return;
    }
    List<Villager> owners = new ArrayList<>();
    for (Villager villager : v.villagers()) {
      Holder<VillagerProfession> p = villager.getVillagerData().profession();
      if (!villager.isBaby()
          && idle(villager)
          && (p.is(VillagerProfession.FARMER)
              || p.is(VillagerProfession.MASON)
              || p.is(VillagerProfession.SHEPHERD)
              || p.is(VillagerProfession.BUTCHER)
              || p.is(VillagerProfession.FISHERMAN))) {
        owners.add(villager);
      }
    }
    if (owners.isEmpty()) {
      return;
    }
    Villager owner = owners.get((int) Math.floorMod(hash >>> 8, (long) owners.size()));
    handOver(v.level(), owner, toolsmiths.get(0), new ItemStack(tool(owner)), Role.REPAIR, true);
  }

  private static void trades(Village v) {
    VillageSimulationParameters parameters = SettlementsConfig.parameters();
    double perPair = SettlementsConfig.value(SettlementsConfig.TRADES_PER_PAIR);
    for (int route = 0; route < ROUTES.size(); route++) {
      TradeRoute trade = ROUTES.get(route);
      int suppliers = count(v, trade.supplier());
      int consumers = count(v, trade.consumer());
      double expected =
          perPair * Math.min(suppliers, consumers) * supply(trade.supplier(), v, parameters);
      long hash = GuestHash.hash(v.level().getSeed(), v.node().id(), v.day(), EVENT_TRADE + route);
      int trades = (int) expected + (GuestHash.unit(hash) < expected - (int) expected ? 1 : 0);
      for (int k = 0; k < trades; k++) {
        long start =
            WORK_START
                + (long)
                    (GuestHash.unit(GuestHash.hash(hash, k))
                        * (WORK_END - WORK_START - EXCHANGE_TIMEOUT));
        if (start > v.tickOfDay() - VillageLife.INTERVAL && start <= v.tickOfDay()) {
          List<Villager> from = v.adults(trade.supplier()).stream().filter(Work::idle).toList();
          List<Villager> to = v.adults(trade.consumer()).stream().filter(Work::idle).toList();
          if (!from.isEmpty() && !to.isEmpty()) {
            long pick = GuestHash.hash(hash, k, 1);
            handOver(
                v.level(),
                from.get((int) Math.floorMod(pick, (long) from.size())),
                to.get((int) Math.floorMod(pick >>> 16, (long) to.size())),
                new ItemStack(trade.item()),
                Role.TRADE,
                false);
          }
        }
      }
    }
  }

  private static double supply(
      ResourceKey<VillagerProfession> supplier, Village v, VillageSimulationParameters parameters) {
    if (supplier == VillagerProfession.FARMER) {
      VillageSimulator.Rates rates = VillageSimulator.rates(v.state(), 0.0, 1.0, 0, parameters);
      double growth = VillageSimulator.growth(v.season(), parameters);
      return rates.consumption() <= 0.0
          ? growth
          : Math.min(1.0, growth * rates.production() / rates.consumption());
    }
    boolean seasonal =
        supplier == VillagerProfession.FISHERMAN || supplier == VillagerProfession.MASON;
    return seasonal && v.season() == Season.WINTER ? 0.3 : 1.0;
  }

  private static int count(Village v, ResourceKey<VillagerProfession> profession) {
    return v.state().villagePopulation().professionCount(profession.identifier());
  }

  static void handOver(
      ServerLevel level,
      Villager supplier,
      Villager consumer,
      ItemStack item,
      Role role,
      boolean repair) {
    if (Errands.busy(consumer) || !Errands.available(consumer)) {
      if (!repair) {
        carryTo(level, supplier, consumer.blockPosition(), item, role);
      }
      return;
    }
    long deadline = level.getGameTime() + EXCHANGE_TIMEOUT;
    boolean[] bowing = {false};
    Errand receive =
        new Errand(
            role,
            VillageLife.WORK,
            deadline,
            List.of(
                Step.wait(
                    Integer.MAX_VALUE,
                    null,
                    (l, villager, tick) -> {
                      if (!bowing[0]) {
                        villager
                            .getBrain()
                            .setMemory(MemoryModuleType.LOOK_TARGET, Errands.at(supplier));
                      }
                    }),
                repair
                    ? Step.wait(
                            90,
                            null,
                            (l, villager, tick) -> {
                              if (tick % 30 == 10) {
                                l.playSound(
                                    null,
                                    villager,
                                    SoundEvents.ANVIL_USE,
                                    SoundSource.NEUTRAL,
                                    0.4F,
                                    1.2F);
                                l.sendParticles(
                                    ParticleTypes.CRIT,
                                    villager.getX(),
                                    villager.getEyeY() - 0.5,
                                    villager.getZ(),
                                    5,
                                    0.2,
                                    0.1,
                                    0.2,
                                    0.1);
                              }
                            })
                        .holding(item)
                    : Step.wait(80, Errands.at(supplier), Action.NONE).holding(item)));
    Errand give =
        new Errand(
            role,
            VillageLife.WORK,
            deadline,
            repair
                ? List.of(
                    new Step(
                        Errands.at(consumer),
                        2,
                        0.5F,
                        20,
                        null,
                        item,
                        (l, villager, tick) -> bow(l, villager, consumer, receive, bowing, tick)),
                    Step.wait(90, Errands.at(consumer), Action.NONE).holding(ItemStack.EMPTY),
                    Step.wait(
                            40,
                            Errands.at(consumer),
                            (l, villager, tick) -> {
                              if (tick == 0) {
                                Errands.release(consumer);
                                l.playSound(
                                    null,
                                    villager,
                                    SoundEvents.VILLAGER_YES,
                                    SoundSource.NEUTRAL,
                                    0.6F,
                                    1.0F);
                              }
                            })
                        .holding(item))
                : List.of(
                    new Step(
                        Errands.at(consumer),
                        2,
                        0.5F,
                        20,
                        null,
                        item,
                        (l, villager, tick) -> bow(l, villager, consumer, receive, bowing, tick)),
                    Step.wait(30, Errands.at(consumer), Action.NONE).holding(ItemStack.EMPTY)));
    if (Errands.offer(level, supplier, give)) {
      Errands.offer(level, consumer, receive);
    }
  }

  private static void bow(
      ServerLevel level,
      Villager supplier,
      Villager consumer,
      Errand receive,
      boolean[] bowing,
      int tick) {
    bowing[0] = true;
    Vec3 middle = supplier.position().add(consumer.position()).scale(0.5).add(0.0, 0.2, 0.0);
    supplier.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, Errands.at(middle));
    consumer.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, Errands.at(middle));
    if (tick == 19) {

      supplier.gossip(level, consumer, level.getGameTime());
      level.sendParticles(
          ParticleTypes.HAPPY_VILLAGER,
          middle.x,
          supplier.getEyeY(),
          middle.z,
          6,
          0.4,
          0.3,
          0.4,
          0.0);
      if (Errands.current(consumer) == receive) {
        receive.skipTo(1);
      }
      bowing[0] = false;
    }
  }

  private static void carryTo(
      ServerLevel level, Villager villager, BlockPos place, ItemStack item, Role role) {
    Errands.offer(
        level,
        villager,
        new Errand(
            role,
            VillageLife.WORK,
            level.getGameTime() + EXCHANGE_TIMEOUT,
            List.of(
                Step.at(
                    place,
                    2,
                    20,
                    item,
                    (l, v, tick) -> {
                      if (tick == 19) {
                        l.playSound(
                            null, v, SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.5F, 0.8F);
                      }
                    }))));
  }

  static Item tool(Villager villager) {
    Holder<VillagerProfession> p = villager.getVillagerData().profession();
    if (p.is(VillagerProfession.FARMER)) return Items.IRON_HOE;
    if (p.is(VillagerProfession.FISHERMAN)) return Items.FISHING_ROD;
    if (p.is(VillagerProfession.SHEPHERD)) return Items.SHEARS;
    if (p.is(VillagerProfession.LIBRARIAN)) return Items.WRITABLE_BOOK;
    if (p.is(VillagerProfession.CARTOGRAPHER)) return Items.PAPER;
    if (p.is(VillagerProfession.CLERIC)) return Items.GLASS_BOTTLE;
    if (p.is(VillagerProfession.MASON)) return Items.IRON_PICKAXE;
    if (p.is(VillagerProfession.TOOLSMITH)) return Items.IRON_PICKAXE;
    if (p.is(VillagerProfession.WEAPONSMITH)) return Items.IRON_SWORD;
    if (p.is(VillagerProfession.ARMORER)) return Items.IRON_INGOT;
    if (p.is(VillagerProfession.BUTCHER)) return Items.IRON_AXE;
    if (p.is(VillagerProfession.FLETCHER)) return Items.ARROW;
    if (p.is(VillagerProfession.LEATHERWORKER)) return Items.LEATHER;
    return Items.AIR;
  }

  static void heldTools(Village v) {
    boolean enabled = SettlementsConfig.enabled(SettlementsConfig.HELD_TOOLS);
    for (Villager villager : v.villagers()) {
      if (Errands.busy(villager) || villager.isBaby()) {
        continue;
      }
      BlockPos site = jobSite(villager);
      boolean working =
          enabled
              && site != null
              && villager.getBrain().isActive(Activity.WORK)
              && site.closerToCenterThan(villager.position(), 3.5)
              && !(v.weekday() == Rites.FULL_MOON && smithsOf(v).contains(villager));
      Item tool = tool(villager);
      if (working && tool != Items.AIR) {
        if (villager.getMainHandItem().isEmpty() || Errands.carrying(villager)) {
          Errands.hold(villager, new ItemStack(tool));
        }
      } else if (Errands.carrying(villager)) {
        Errands.release(villager);
      }
    }
  }

  private static void snow(Village v) {
    if (!SettlementsConfig.enabled(SettlementsConfig.SNOW_CLEARING)
        || v.weather().type().isSnow()) {
      return;
    }
    List<BlockPos> drifts = drifts(v);
    if (drifts.isEmpty()) {
      return;
    }
    int next = 0;
    for (Villager villager : v.villagers()) {
      if (next >= drifts.size()) {
        return;
      }
      if (villager.isBaby()
          || !idle(villager)
          || villager.getVillagerData().profession().is(VillagerProfession.CLERIC)
          || NO_DRIFT.getOrDefault(villager, Long.MIN_VALUE) > v.gameTime()) {
        continue;
      }
      int first = next;
      List<Step> steps = new ArrayList<>();
      while (steps.size() < 4 && next < drifts.size() && next - first < DRIFT_TRIES) {
        BlockPos drift = drifts.get(next++);
        Path path = villager.getNavigation().createPath(drift, 1);
        if (path == null || !path.canReach()) {
          continue;
        }
        steps.add(
            Step.at(
                    drift,
                    1,
                    25,
                    new ItemStack(Items.IRON_SHOVEL),
                    (level, worker, tick) -> {
                      if (tick == 20
                          && level.getBlockState(drift).is(GuestSettlements.CLEARABLE_COVER)) {
                        level.destroyBlock(drift, false, worker);
                      }
                    })
                .looking(Errands.at(Vec3.atCenterOf(drift))));
      }
      if (steps.isEmpty()) {
        next = first;
        NO_DRIFT.put(villager, v.gameTime() + DRIFT_RETRY);
        continue;
      }
      Errands.offer(
          v.level(),
          villager,
          new Errand(Role.SNOW, VillageLife.WORK - 1, v.gameTime() + 2400, steps));
    }
  }

  private static List<BlockPos> drifts(Village v) {
    List<BlockPos> found = new ArrayList<>();
    List<BlockPos> centers = new ArrayList<>();
    centers.add(v.center());
    centers.addAll(v.manager().homes(v.node()));
    for (BlockPos center : centers) {
      int r = center == v.center() ? 8 : 3;
      for (int x = -r; x <= r; x++) {
        for (int z = -r; z <= r; z++) {
          BlockPos top = Places.top(v.level(), center.getX() + x, center.getZ() + z);
          if (top != null
              && Construction.ground(v.level().getBlockState(top))
              && v.level().getBlockState(top.above()).is(GuestSettlements.CLEARABLE_COVER)
              && !Errands.unreachable(v.level(), top.above())
              && !found.contains(top.above())) {
            found.add(top.above());
          }
        }
      }
      if (found.size() > 32) {
        break;
      }
    }
    return found;
  }

  static int radius(BoundingBox box) {
    return Math.max(box.getXSpan(), box.getZSpan()) / 2;
  }
}
