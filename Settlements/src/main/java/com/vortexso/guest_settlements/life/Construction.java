package com.vortexso.guest_settlements.life;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.Season;
import com.vortexso.guest_settlements.SettlementsConfig;
import com.vortexso.guest_settlements.life.Errands.Errand;
import com.vortexso.guest_settlements.life.Errands.Step;
import com.vortexso.guest_settlements.life.LifeData.Project;
import com.vortexso.guest_settlements.life.VillageLife.Role;
import com.vortexso.guest_settlements.life.VillageLife.Village;
import com.vortexso.guest_settlements.village.Caravans;
import com.vortexso.guest_settlements.village.RoadEdge;
import com.vortexso.guest_settlements.village.RoutePlanner;
import com.vortexso.guest_settlements.village.VillageNode;
import com.vortexso.guest_settlements.village.VillageSimulationParameters;
import com.vortexso.guest_settlements.village.VillageSimulator;
import com.vortexso.guest_settlements.village.VillageState;
import com.vortexso.guest_settlements.village.VillageWorldManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class Construction {
  public enum Kind {
    HOUSE,
    FARM,
    BRIDGE,
    STALL,
    FENCE
  }

  record Placement(BlockPos pos, BlockState state, boolean surface) {}

  private record Key(
      String kind, Optional<Identifier> template, BlockPos origin, int direction, int size) {
    static Key of(Project project) {
      return new Key(
          project.kind(),
          project.template(),
          project.origin(),
          project.direction(),
          project.size());
    }
  }

  private enum Material {
    WOOD,
    STONE,
    OTHER
  }

  private static final int SITE_MIN = 4;

  private static final int LEVEL_TOLERANCE = 5;

  private static final int SITE_MAX = 28;
  private static final int BRIDGE_REACH = 64;
  private static final int BRIDGE_MAX = 24;
  private static final int FENCE_LENGTH = 12;
  private static final int MAX_PROJECTS_PER_CATCH_UP = 4;
  private static final int MAX_TREES_PER_CATCH_UP = 8;
  private static final int LOG_PLANKS = 4;
  private static final int PLACE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

  private static final Map<Key, List<Placement>> BLUEPRINTS = new HashMap<>();

  private static final Map<ServerLevel, Map<Long, long[]>> TODAY = new WeakHashMap<>();

  private static final Map<ServerLevel, Map<Long, Long>> PLANNED = new WeakHashMap<>();

  private Construction() {}

  public static boolean active(ServerLevel level, VillageNode node) {
    return !LifeData.get(level).projects(node.id()).isEmpty();
  }

  static void tick(Village v) {
    if (!SettlementsConfig.enabled(SettlementsConfig.CONSTRUCTION)) {
      return;
    }
    LifeData data = LifeData.get(v.level());
    List<Project> projects = data.projects(v.node().id());
    if (projects.isEmpty()) {
      Map<Long, Long> planned = PLANNED.computeIfAbsent(v.level(), ignored -> new HashMap<>());
      if (planned.getOrDefault(v.node().id(), Long.MIN_VALUE) != v.day()) {
        planned.put(v.node().id(), v.day());
        plan(v.level(), v.manager(), v.node(), v.state(), v.day());
      }
      return;
    }
    Project project = projects.get(0);
    if (project.day() < v.day()) {

      data.replace(
          project,
          project.withProgress(project.placed(), v.day(), project.wood(), project.stone()));
    }
    if (!v.between(Work.WORK_START, Work.WORK_END) || v.weather().isSevere()) {
      return;
    }
    List<Villager> builders = builders(v);
    long[] today =
        TODAY
            .computeIfAbsent(v.level(), ignored -> new HashMap<>())
            .computeIfAbsent(v.node().id(), ignored -> new long[] {Long.MIN_VALUE, 0});
    if (today[0] != v.day()) {
      today[0] = v.day();
      today[1] = 0;
    }
    double quota = builders.size() * SettlementsConfig.value(SettlementsConfig.BLOCKS_PER_BUILDER);
    double dayShare = (v.tickOfDay() - Work.WORK_START + 1.0) / (Work.WORK_END - Work.WORK_START);
    for (Villager builder : builders) {
      if (today[1] >= Math.ceil(quota * dayShare)) {
        return;
      }
      if (Errands.canTake(builder, VillageLife.WORK) && !builder.isSleeping()) {
        if (build(v, builder, Key.of(project), today)) {
          return;
        }
      }
    }
  }

  private static List<Villager> builders(Village v) {
    List<Villager> builders = new ArrayList<>();
    for (Villager villager : v.villagers()) {
      var p = villager.getVillagerData().profession();
      if (!villager.isBaby()
          && (p.is(VillagerProfession.MASON)
              || p.is(VillagerProfession.NONE)
              || (p.is(VillagerProfession.FARMER) && v.season() == Season.WINTER))) {
        builders.add(villager);
      }
    }
    if (builders.isEmpty()) {
      v.villagers().stream()
          .filter(
              villager ->
                  !villager.isBaby()
                      && !villager.getVillagerData().profession().is(VillagerProfession.NITWIT))
          .findFirst()
          .ifPresent(builders::add);
    }
    return builders;
  }

  static int builders(VillageState state) {
    int count =
        state.villagePopulation().professionCount(VillagerProfession.MASON.identifier())
            + state.villagePopulation().professionCount(VillagerProfession.NONE.identifier());
    return count > 0 ? count : state.adults() > 0 ? 1 : 0;
  }

  private static boolean build(Village v, Villager builder, Key key, long[] today) {
    ServerLevel level = v.level();
    Project project = find(level, v.node(), key);
    if (project == null) {
      return false;
    }
    List<Placement> plan = blueprint(level, project);
    int next = nextNeeded(level, plan, project.placed());
    if (next >= plan.size()) {
      complete(level, project);
      return false;
    }
    Placement target = plan.get(next);
    BlockPos pos = resolve(level, target);
    if (pos == null) {
      return false;
    }
    if (Errands.unreachable(level, pos)) {

      complete(level, project);
      return false;
    }
    List<Step> steps = new ArrayList<>();
    Material material = material(target.state());
    if (material == Material.WOOD && project.wood() <= 0) {
      BlockPos tree =
          Places.tree(
              level,
              v.center(),
              Work.radius(v.box()) + SITE_MAX,
              VillageLife.reach(v.box()),
              v.box(),
              v.day() ^ builder.getId());
      if (tree != null) {
        steps.add(
            Step.at(
                    tree,
                    2,
                    80,
                    new ItemStack(Items.IRON_AXE),
                    (l, villager, tick) -> chop(l, v.node(), key, tree, villager, tick))
                .looking(Errands.at(Vec3.atCenterOf(tree.above()))));
      }
    } else if (material == Material.STONE && project.stone() <= 0 && v.season() != Season.WINTER) {
      BlockPos stone =
          Places.stone(
              level,
              v.center(),
              Work.radius(v.box()) + SITE_MAX,
              VillageLife.reach(v.box()),
              v.box(),
              v.day() ^ builder.getId());
      if (stone != null) {
        steps.add(
            Step.at(
                    stone.above(),
                    1,
                    60,
                    new ItemStack(Items.IRON_PICKAXE),
                    (l, villager, tick) -> quarry(l, v.node(), key, stone, villager, tick))
                .looking(Errands.at(Vec3.atCenterOf(stone))));
      }
    }
    if (steps.isEmpty() && material != Material.OTHER) {

      steps.add(Step.at(v.center(), 3, 20, ItemStack.EMPTY, Errands.Action.NONE));
    } else if (material == Material.OTHER) {
      steps.add(Step.at(v.center(), 3, 20, ItemStack.EMPTY, Errands.Action.NONE));
    }
    steps.add(
        Step.at(
                pos,
                3,
                30,
                new ItemStack(item(target.state())),
                (l, villager, tick) -> {
                  if (tick == 20 && placeNext(l, v.node(), key, true)) {
                    today[1]++;
                  }
                })
            .looking(Errands.at(Vec3.atCenterOf(pos))));
    return Errands.offer(
        level,
        builder,
        new Errand(Role.BUILDING, VillageLife.WORK, level.getGameTime() + 4000, steps));
  }

  private static void chop(
      ServerLevel level, VillageNode node, Key key, BlockPos base, Villager villager, int tick) {
    if (tick % 20 == 10) {
      level.playSound(null, base, SoundEvents.AXE_STRIP, SoundSource.BLOCKS, 0.8F, 0.8F);
    }
    if (tick != 79 || !level.getBlockState(base).is(BlockTags.LOGS)) {
      return;
    }
    List<BlockPos> logs = Places.treeLogs(level, base, 32);
    Item log = level.getBlockState(base).getBlock().asItem();
    for (BlockPos pos : logs) {
      level.destroyBlock(pos, false, villager);
    }
    addStock(level, node, key, logs.size() * LOG_PLANKS, 0);
    Errands.hold(villager, new ItemStack(log));
  }

  private static void quarry(
      ServerLevel level, VillageNode node, Key key, BlockPos stone, Villager villager, int tick) {
    if (tick % 15 == 5) {
      level.playSound(null, stone, SoundEvents.STONE_HIT, SoundSource.BLOCKS, 0.7F, 1.0F);
    }
    if (tick == 59 && level.getBlockState(stone).is(BlockTags.BASE_STONE_OVERWORLD)) {
      level.destroyBlock(stone, false, villager);
      addStock(level, node, key, 0, 1);
    }
  }

  private static void addStock(ServerLevel level, VillageNode node, Key key, int wood, int stone) {
    Project project = find(level, node, key);
    if (project != null) {
      LifeData.get(level)
          .replace(
              project,
              project.withProgress(
                  project.placed(), project.day(), project.wood() + wood, project.stone() + stone));
    }
  }

  private static @Nullable Project find(ServerLevel level, VillageNode node, Key key) {
    for (Project project : LifeData.get(level).projects(node.id())) {
      if (Key.of(project).equals(key)) {
        return project;
      }
    }
    return null;
  }

  private static int nextNeeded(ServerLevel level, List<Placement> plan, int from) {
    int i = from;
    while (i < plan.size()) {
      Placement placement = plan.get(i);
      BlockPos pos = resolve(level, placement);
      if (pos == null) {
        return i;
      }
      if (!placement.state().isAir() && !satisfied(level, placement, pos)) {
        return i;
      }
      i++;
    }
    return i;
  }

  private static boolean satisfied(ServerLevel level, Placement placement, BlockPos pos) {
    BlockState current = level.getBlockState(pos);
    if (placement.surface()) {

      return current.getBlock() instanceof FenceBlock || !natural(level.getBlockState(pos.below()));
    }
    if (placement.state().isAir()) {
      return current.isAir();
    }

    return current == placement.state()
        || (placement.state().is(Blocks.COBBLESTONE)
            && current.isFaceSturdy(level, pos, Direction.UP)
            && !current.is(BlockTags.LEAVES));
  }

  static boolean placeNext(ServerLevel level, VillageNode node, Key key, boolean effects) {
    Project project = find(level, node, key);
    if (project == null) {
      return false;
    }
    List<Placement> plan = blueprint(level, project);
    int i = project.placed();
    int wood = project.wood();
    int stone = project.stone();
    boolean placed = false;
    while (i < plan.size() && !placed) {
      Placement placement = plan.get(i);
      BlockPos pos = resolve(level, placement);
      if (pos == null) {
        break;
      }
      i++;
      if (satisfied(level, placement, pos)) {
        continue;
      }
      if (placement.state().isAir()) {

        level.setBlock(pos, Blocks.AIR.defaultBlockState(), PLACE_FLAGS);
        continue;
      }
      set(level, pos, placement.state(), effects);
      switch (material(placement.state())) {
        case WOOD -> wood = Math.max(0, wood - 1);
        case STONE -> stone = Math.max(0, stone - 1);
        case OTHER -> {}
      }
      placed = true;
    }
    project = project.withProgress(i, project.day(), wood, stone);
    LifeData.get(level).replace(find(level, node, key), project);
    if (i >= plan.size()) {
      complete(level, project);
    }
    return placed;
  }

  private static void set(ServerLevel level, BlockPos pos, BlockState state, boolean effects) {
    level.setBlock(pos, state, PLACE_FLAGS);
    if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
        && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
      level.setBlock(
          pos.above(),
          state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER),
          PLACE_FLAGS);
    }
    if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.FOOT) {
      level.setBlock(
          pos.relative(state.getValue(BedBlock.FACING)),
          state.setValue(BedBlock.PART, BedPart.HEAD),
          PLACE_FLAGS);
    }
    if (effects) {
      level.playSound(
          null,
          pos,
          state.getSoundType(level, pos, null).getPlaceSound(),
          SoundSource.BLOCKS,
          1.0F,
          0.9F);
      level.levelEvent(2001, pos, Block.getId(state));
    }
  }

  private static void complete(ServerLevel level, Project project) {
    LifeData.get(level).remove(project);
    BLUEPRINTS.remove(Key.of(project));
  }

  private static @Nullable BlockPos resolve(ServerLevel level, Placement placement) {
    BlockPos pos = placement.pos();
    if (!level.hasChunkAt(pos)) {
      return null;
    }
    if (!placement.surface()) {
      return pos;
    }
    BlockPos top = Places.top(level, pos.getX(), pos.getZ());
    if (top == null) {
      return null;
    }

    return level.getBlockState(top).getBlock() instanceof FenceBlock ? top : top.above();
  }

  private static Material material(BlockState state) {
    if (state.is(BlockTags.MINEABLE_WITH_AXE)) {
      return Material.WOOD;
    }
    return state.is(BlockTags.MINEABLE_WITH_PICKAXE) ? Material.STONE : Material.OTHER;
  }

  private static Item item(BlockState state) {
    if (state.is(Blocks.WATER)) {
      return Items.WATER_BUCKET;
    }
    Item item = state.getBlock().asItem();
    return item == Items.AIR ? Items.STICK : item;
  }

  public static void catchUp(
      ServerLevel level,
      VillageWorldManager manager,
      VillageNode node,
      VillageState state,
      long today) {
    if (!SettlementsConfig.enabled(SettlementsConfig.CONSTRUCTION) || node.structureBox() == null) {
      return;
    }
    int trees = 0;
    for (int round = 0; round < MAX_PROJECTS_PER_CATCH_UP; round++) {
      List<Project> projects = LifeData.get(level).projects(node.id());
      Project project =
          projects.isEmpty() ? plan(level, manager, node, state, today) : projects.get(0);
      if (project == null || project.day() >= today) {
        return;
      }
      double perDay =
          builders(state) * SettlementsConfig.value(SettlementsConfig.BLOCKS_PER_BUILDER);
      long days = today - project.day();
      int budget = (int) Math.min(Integer.MAX_VALUE, Math.round(perDay * days));
      Key key = Key.of(project);
      LifeData.get(level)
          .replace(
              project,
              project.withProgress(project.placed(), today, project.wood(), project.stone()));
      int used = 0;
      while (used < budget && find(level, node, key) != null) {
        Project current = find(level, node, key);
        List<Placement> plan = blueprint(level, current);
        int next = nextNeeded(level, plan, current.placed());
        if (next < plan.size()
            && material(plan.get(next).state()) == Material.WOOD
            && current.wood() <= 0
            && trees < MAX_TREES_PER_CATCH_UP) {
          BlockPos tree =
              Places.tree(
                  level,
                  node.bell() != null ? node.bell() : node.center(),
                  Work.radius(node.structureBox()) + SITE_MAX,
                  VillageLife.reach(node.structureBox()),
                  node.structureBox(),
                  today + trees);
          if (tree != null) {
            List<BlockPos> logs = Places.treeLogs(level, tree, 32);
            logs.forEach(log -> level.removeBlock(log, false));
            addStock(level, node, key, logs.size() * LOG_PLANKS, 0);
          }
          trees++;
        }
        if (!placeNext(level, node, key, false)) {
          if (find(level, node, key) != null) {
            return;
          }
        }
        used++;
      }
      if (find(level, node, key) != null || perDay <= 0.0) {
        return;
      }

      long nextStart = project.day() + (long) Math.ceil(used / perDay);
      if (nextStart >= today || plan(level, manager, node, state, nextStart) == null) {
        return;
      }
    }
  }

  static @Nullable Project plan(
      ServerLevel level,
      VillageWorldManager manager,
      VillageNode node,
      VillageState state,
      long day) {
    if (!SettlementsConfig.enabled(SettlementsConfig.CONSTRUCTION)
        || node.structureBox() == null
        || state.adults() == 0
        || state.fallen()) {
      return null;
    }
    VillageSimulationParameters parameters = SettlementsConfig.parameters();
    VillageSimulator.Rates rates = VillageSimulator.rates(state, 0.0, 1.0, day, parameters);
    long salt = GuestHash.hash(level.getSeed(), node.id(), day);
    Project project = null;
    if (state.beds() <= state.population()) {
      project = templateSite(level, node, Kind.HOUSE, salt, day);
    }
    if (project == null && rates.production() < rates.consumption()) {
      project = templateSite(level, node, Kind.FARM, salt, day);
    }
    if (project == null) {
      project = bridge(level, manager, node, day);
    }
    if (project == null
        && state.foodReserve() >= state.population() * parameters.storagePerVillager() / 2.0) {
      if (tradeHeavy(manager, node, day)) {
        project = stall(level, node, salt, day);
      }
      if (project == null
          && manager.pressure(node) >= SettlementsConfig.value(SettlementsConfig.FENCE_PRESSURE)) {
        project = fence(level, node, salt, day);
      }
    }
    if (project != null) {
      LifeData.get(level).replace(project, project);
    }
    return project;
  }

  private static boolean tradeHeavy(VillageWorldManager manager, VillageNode node, long day) {
    int arrived = 0;
    for (Caravans.Caravan caravan : manager.arrivals(node, day - GuestTime.DAYS_PER_SEASON, day)) {
      if (!caravan.lost()) {
        arrived++;
      }
    }
    return arrived >= 2;
  }

  private static String style(ServerLevel level, VillageNode node) {
    String type = VillagerType.byBiome(level.getBiome(node.center())).identifier().getPath();
    return switch (type) {
      case "desert", "savanna", "taiga" -> type;
      case "snow" -> "snowy";
      default -> "plains";
    };
  }

  private static Identifier template(ServerLevel level, VillageNode node, Kind kind, long salt) {
    String style = style(level, node);
    if (kind == Kind.FARM) {
      String farm =
          switch (style) {
            case "desert" -> "desert_farm_1";
            case "savanna" -> "savanna_small_farm";
            case "snowy" -> "snowy_farm_1";
            case "taiga" -> "taiga_small_farm_1";
            default -> "plains_small_farm_1";
          };
      return Identifier.withDefaultNamespace("village/" + style + "/houses/" + farm);
    }
    int houses = style.equals("taiga") ? 5 : 8;
    int n = 1 + (int) Math.floorMod(salt, (long) houses);
    return Identifier.withDefaultNamespace(
        "village/" + style + "/houses/" + style + "_small_house_" + n);
  }

  private static @Nullable Project templateSite(
      ServerLevel level, VillageNode node, Kind kind, long salt, long day) {
    Identifier id = template(level, node, kind, salt);
    StructureTemplate template = level.getServer().getStructureManager().get(id).orElse(null);
    if (template == null) {
      return null;
    }
    Rotation rotation = Rotation.values()[(int) Math.floorMod(salt >>> 4, 4L)];
    BoundingBox local =
        template.getBoundingBox(BlockPos.ZERO, rotation, BlockPos.ZERO, Mirror.NONE);
    BoundingBox box = node.structureBox();
    BlockPos center = node.bell() != null ? node.bell() : node.center();
    int radius = Work.radius(box);
    var reach = VillageLife.reach(box);
    int ground = Places.ground(level, center).getY() - 1;
    int[] floor = new int[1];

    List<BlockPos> site =
        Places.ring(
            level,
            center,
            SITE_MIN * 2,
            radius + SITE_MAX,
            3,
            salt,
            1,
            top -> {
              int x0 = top.getX() - local.getXSpan() / 2;
              int z0 = top.getZ() - local.getZSpan() / 2;
              if (!Places.inside(reach, new BlockPos(x0 - 1, 0, z0 - 1))
                  || !Places.inside(
                      reach, new BlockPos(x0 + local.getXSpan(), 0, z0 + local.getZSpan()))
                  || Math.abs(top.getY() - ground) > LEVEL_TOLERANCE
                  || Errands.unreachable(level, top.above())
                  || !open(level, top)) {
                return false;
              }
              Integer y =
                  flatFloor(
                      level,
                      node,
                      top.getX() - local.getXSpan() / 2,
                      top.getZ() - local.getZSpan() / 2,
                      local.getXSpan(),
                      local.getZSpan());
              floor[0] = y == null ? 0 : y;
              return y != null;
            });
    if (!site.isEmpty()) {
      BlockPos candidate = site.get(0);
      int minX = candidate.getX() - local.getXSpan() / 2;
      int minZ = candidate.getZ() - local.getZSpan() / 2;
      BlockPos origin = new BlockPos(minX - local.minX(), floor[0], minZ - local.minZ());
      return new Project(
          node.id(), kind.name(), Optional.of(id), origin, rotation.ordinal(), 0, 0, day, 0, 0);
    }
    return null;
  }

  private static @Nullable Integer flatFloor(
      ServerLevel level, VillageNode node, int minX, int minZ, int sizeX, int sizeZ) {
    int low = Integer.MAX_VALUE;
    int high = Integer.MIN_VALUE;
    BoundingBox footprint =
        new BoundingBox(minX - 1, -64, minZ - 1, minX + sizeX, 512, minZ + sizeZ);
    for (Project other : LifeData.get(level).projects(node.id())) {
      if (footprint.intersects(extent(level, other))) {
        return null;
      }
    }
    for (int x = minX - 1; x <= minX + sizeX; x++) {
      for (int z = minZ - 1; z <= minZ + sizeZ; z++) {
        BlockPos top = Places.top(level, x, z);
        if (top == null || !natural(level.getBlockState(top))) {
          return null;
        }
        if (!clearAbove(level, top)) {
          return null;
        }
        low = Math.min(low, top.getY());
        high = Math.max(high, top.getY());
      }
    }
    return high - low <= 1 ? high : null;
  }

  private static boolean open(ServerLevel level, BlockPos top) {
    return natural(level.getBlockState(top)) && clearAbove(level, top);
  }

  private static boolean clearAbove(ServerLevel level, BlockPos top) {
    int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, top.getX(), top.getZ()) - 1;
    for (int y = top.getY() + 1; y <= surface; y++) {
      BlockState state = level.getBlockState(new BlockPos(top.getX(), y, top.getZ()));
      if (!state.isAir() && !(state.canBeReplaced() && !state.is(BlockTags.LEAVES))) {
        return false;
      }
    }
    return true;
  }

  private static boolean natural(BlockState state) {
    return state.is(BlockTags.SUBSTRATE_OVERWORLD)
        || state.is(BlockTags.SAND)
        || state.is(Blocks.GRAVEL)
        || state.is(Blocks.SNOW_BLOCK)
        || state.is(BlockTags.BASE_STONE_OVERWORLD);
  }

  private static BoundingBox extent(ServerLevel level, Project project) {
    List<Placement> plan = blueprint(level, project);
    if (plan.isEmpty()) {
      return new BoundingBox(project.origin());
    }
    return BoundingBox.encapsulatingPositions(plan.stream().map(Placement::pos).toList())
        .orElse(new BoundingBox(project.origin()));
  }

  private static @Nullable Project stall(ServerLevel level, VillageNode node, long salt, long day) {
    BlockPos center = node.bell() != null ? node.bell() : node.center();
    var reach = VillageLife.reach(node.structureBox());
    int[] floor = new int[1];
    List<BlockPos> site =
        Places.ring(
            level,
            center,
            5,
            24,
            2,
            salt ^ 0x57A11L,
            1,
            top -> {
              if (!Places.inside(reach, top) || !open(level, top)) {
                return false;
              }
              Integer y = flatFloor(level, node, top.getX() - 1, top.getZ() - 1, 3, 3);
              floor[0] = y == null ? 0 : y;
              return y != null;
            });
    if (!site.isEmpty()) {
      BlockPos candidate = site.get(0);
      return new Project(
          node.id(),
          Kind.STALL.name(),
          Optional.empty(),
          new BlockPos(candidate.getX() - 1, floor[0] + 1, candidate.getZ() - 1),
          (int) Math.floorMod(salt, 16L),
          3,
          0,
          day,
          0,
          0);
    }
    return null;
  }

  private static @Nullable Project fence(ServerLevel level, VillageNode node, long salt, long day) {
    BoundingBox box = node.structureBox();
    Direction side = Direction.from2DDataValue((int) Math.floorMod(salt, 4L));
    Direction along = side.getClockWise();
    BlockPos mid =
        new BlockPos(
            side.getAxis() == Direction.Axis.X
                ? (side.getStepX() > 0 ? box.maxX() + 3 : box.minX() - 3)
                : box.getCenter().getX(),
            box.getCenter().getY(),
            side.getAxis() == Direction.Axis.Z
                ? (side.getStepZ() > 0 ? box.maxZ() + 3 : box.minZ() - 3)
                : box.getCenter().getZ());
    int offset =
        (int)
            Math.floorMod(
                salt >>> 8,
                (long)
                    Math.max(
                        1,
                        (side.getAxis() == Direction.Axis.X ? box.getZSpan() : box.getXSpan())
                            - FENCE_LENGTH));
    BlockPos start =
        mid.relative(
            along,
            offset - (side.getAxis() == Direction.Axis.X ? box.getZSpan() : box.getXSpan()) / 2);
    int usable = 0;
    int fenced = 0;
    for (int i = 0; i < FENCE_LENGTH; i++) {
      BlockPos top =
          Places.top(
              level, start.getX() + along.getStepX() * i, start.getZ() + along.getStepZ() * i);
      if (top == null) {
        return null;
      }
      if (level.getBlockState(top).getBlock() instanceof FenceBlock) {
        fenced++;
      } else if (natural(level.getBlockState(top))) {
        usable++;
      }
    }

    if (fenced > FENCE_LENGTH / 2 || usable < FENCE_LENGTH / 2) {
      return null;
    }
    return new Project(
        node.id(),
        Kind.FENCE.name(),
        Optional.empty(),
        start,
        along.get2DDataValue(),
        FENCE_LENGTH,
        0,
        day,
        0,
        0);
  }

  private static @Nullable Project bridge(
      ServerLevel level, VillageWorldManager manager, VillageNode node, long day) {
    BlockPos from = node.bell() != null ? node.bell() : node.center();
    int reach = Work.radius(node.structureBox()) + BRIDGE_REACH;
    for (RoadEdge road : manager.roads()) {
      long other =
          road.firstVillageId() == node.id()
              ? road.secondVillageId()
              : road.secondVillageId() == node.id() ? road.firstVillageId() : 0L;
      VillageNode neighbor = other == 0L ? null : manager.node(other);
      if (neighbor == null) {
        continue;
      }
      List<BlockPos> route = RoutePlanner.route(level, from, neighbor.center());
      BlockPos land = null;
      BlockPos bank = null;
      int run = 0;
      long previous = Long.MIN_VALUE;
      for (int s = 0; s + 1 < route.size(); s++) {
        BlockPos a = route.get(s);
        BlockPos b = route.get(s + 1);
        double length = Math.max(1.0, Math.sqrt(a.distSqr(b)));
        Direction direction =
            Direction.getApproximateNearest(b.getX() - a.getX(), 0.0, b.getZ() - a.getZ());
        for (int t = 0; t <= length; t++) {
          int x = (int) Math.round(a.getX() + (b.getX() - a.getX()) * t / length);
          int z = (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * t / length);
          long column = BlockPos.asLong(x, 0, z);
          if (column == previous) {
            continue;
          }
          previous = column;
          BlockPos top = Places.top(level, x, z);
          if (top == null || Math.abs(x - from.getX()) + Math.abs(z - from.getZ()) > reach * 1.5) {
            break;
          }
          boolean ground = ground(level.getBlockState(top));
          if (bank == null) {
            if (land != null && gap(level, top, land)) {
              bank = land;
              run = 1;
            } else {
              land = ground ? top : null;
            }
          } else if (gap(level, top, bank)) {
            run++;
          } else if (!ground) {
            bank = null;
            run = 0;
            land = null;
          } else {
            if (run >= 2
                && run <= BRIDGE_MAX
                && Math.abs(top.getY() - bank.getY()) <= 1
                && span(level, bank, direction, run)
                && !bridged(level, bank, direction, run)) {
              BlockPos origin = bank.relative(direction.getOpposite());
              return new Project(
                  node.id(),
                  Kind.BRIDGE.name(),
                  Optional.empty(),
                  new BlockPos(origin.getX(), bank.getY(), origin.getZ()),
                  direction.get2DDataValue(),
                  run + 3,
                  0,
                  day,
                  0,
                  0);
            }
            bank = null;
            run = 0;
            land = top;
          }
        }
      }
    }
    return null;
  }

  private static boolean gap(ServerLevel level, BlockPos top, BlockPos bank) {
    return water(level, top) || (top.getY() < bank.getY() - 3 && ground(level.getBlockState(top)));
  }

  private static boolean water(ServerLevel level, BlockPos top) {
    return Places.isWater(level, top) || level.getBlockState(top).is(BlockTags.ICE);
  }

  static boolean ground(BlockState state) {
    return natural(state) || state.is(Blocks.DIRT_PATH);
  }

  private static boolean span(ServerLevel level, BlockPos bank, Direction along, int run) {
    Direction side = along.getClockWise();
    for (int i = -1; i <= run + 1; i++) {
      for (int w = -2; w <= 2; w++) {
        BlockPos top =
            Places.top(
                level,
                bank.getX() + along.getStepX() * i + side.getStepX() * w,
                bank.getZ() + along.getStepZ() * i + side.getStepZ() * w);
        if (top == null
            || top.getY() > bank.getY() + 1
            || !(water(level, top) || ground(level.getBlockState(top)))) {
          return false;
        }
        boolean over = i >= 1 && i <= run;
        if (w == 0 && (over ? !gap(level, top, bank) : !ground(level.getBlockState(top)))) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean bridged(ServerLevel level, BlockPos bank, Direction direction, int run) {
    BlockPos middle = bank.relative(direction, 1 + run / 2);
    return !level.getBlockState(middle).isAir() && !Places.isWater(level, middle);
  }

  static List<Placement> blueprint(ServerLevel level, Project project) {
    return BLUEPRINTS.computeIfAbsent(Key.of(project), key -> derive(level, project));
  }

  private static List<Placement> derive(ServerLevel level, Project project) {
    Kind kind = Kind.valueOf(project.kind());
    return switch (kind) {
      case HOUSE, FARM ->
          project
              .template()
              .map(
                  id ->
                      fromTemplate(
                          level, id, project.origin(), Rotation.values()[project.direction()]))
              .orElse(List.of());
      case BRIDGE -> bridgeBlueprint(level, project);
      case STALL -> stallBlueprint(project);
      case FENCE -> fenceBlueprint(level, project);
    };
  }

  private static List<Placement> fromTemplate(
      ServerLevel level, Identifier id, BlockPos origin, Rotation rotation) {
    StructureTemplate template = level.getServer().getStructureManager().get(id).orElse(null);
    if (template == null) {
      return List.of();
    }
    CompoundTag tag = template.save(new CompoundTag());
    ListTag paletteTag = tag.getListOrEmpty("palette");
    if (paletteTag.isEmpty()) {
      paletteTag = tag.getListOrEmpty("palettes").getListOrEmpty(0);
    }
    HolderGetter<Block> blocks = level.holderLookup(Registries.BLOCK);
    List<BlockState> palette = new ArrayList<>();
    for (int i = 0; i < paletteTag.size(); i++) {
      palette.add(NbtUtils.readBlockState(blocks, paletteTag.getCompoundOrEmpty(i)));
    }
    List<Placement> footing = new ArrayList<>();
    List<Placement> body = new ArrayList<>();
    ListTag blockList = tag.getListOrEmpty("blocks");
    for (int i = 0; i < blockList.size(); i++) {
      CompoundTag entry = blockList.getCompoundOrEmpty(i);
      ListTag p = entry.getListOrEmpty("pos");
      BlockPos local = new BlockPos(p.getIntOr(0, 0), p.getIntOr(1, 0), p.getIntOr(2, 0));
      BlockState state = palette.get(Math.min(entry.getIntOr("state", 0), palette.size() - 1));
      if (state.is(Blocks.STRUCTURE_VOID)) {
        continue;
      }
      if (state.is(Blocks.JIGSAW)) {
        state =
            parse(
                level, entry.getCompoundOrEmpty("nbt").getStringOr("final_state", "minecraft:air"));
      }
      BlockPos pos =
          StructureTemplate.transform(local, Mirror.NONE, rotation, BlockPos.ZERO).offset(origin);
      state = state.rotate(rotation);
      body.add(new Placement(pos, state, false));
      if (local.getY() == 0 && !state.isAir()) {
        footing.add(new Placement(pos.below(), Blocks.COBBLESTONE.defaultBlockState(), false));
      }
    }
    body.sort(
        Comparator.comparingInt((Placement placement) -> placement.pos().getY())
            .thenComparingInt(placement -> placement.pos().getZ())
            .thenComparingInt(placement -> placement.pos().getX()));
    footing.addAll(body);
    return List.copyOf(footing);
  }

  private static BlockState parse(ServerLevel level, String state) {
    try {
      return BlockStateParser.parseForBlock(level.holderLookup(Registries.BLOCK), state, false)
          .blockState();
    } catch (CommandSyntaxException exception) {
      return Blocks.AIR.defaultBlockState();
    }
  }

  private static BlockState planks(ServerLevel level, BlockPos pos) {
    return switch (style(level, pos)) {
      case "taiga", "snowy" -> Blocks.SPRUCE_PLANKS.defaultBlockState();
      case "savanna" -> Blocks.ACACIA_PLANKS.defaultBlockState();
      default -> Blocks.OAK_PLANKS.defaultBlockState();
    };
  }

  private static BlockState fence(ServerLevel level, BlockPos pos) {
    return switch (style(level, pos)) {
      case "taiga", "snowy" -> Blocks.SPRUCE_FENCE.defaultBlockState();
      case "savanna" -> Blocks.ACACIA_FENCE.defaultBlockState();
      default -> Blocks.OAK_FENCE.defaultBlockState();
    };
  }

  private static String style(ServerLevel level, BlockPos pos) {
    String type = VillagerType.byBiome(level.getBiome(pos)).identifier().getPath();
    return type.equals("snow") ? "snowy" : type;
  }

  private static List<Placement> bridgeBlueprint(ServerLevel level, Project project) {
    Direction along = Direction.from2DDataValue(project.direction());
    Direction side = along.getClockWise();
    BlockState deck = planks(level, project.origin());
    BlockState rail = fence(level, project.origin());
    List<Placement> plan = new ArrayList<>();
    for (int i = 0; i < project.size(); i++) {
      BlockPos row = project.origin().relative(along, i);
      for (int w = -2; w <= 2; w++) {
        plan.add(new Placement(row.relative(side, w), deck, false));
      }
      for (int w = -2; w <= 2; w += 4) {
        plan.add(
            new Placement(
                row.relative(side, w).above(),
                rail.setValue(FenceBlock.NORTH, along.getAxis() == Direction.Axis.Z)
                    .setValue(FenceBlock.SOUTH, along.getAxis() == Direction.Axis.Z)
                    .setValue(FenceBlock.EAST, along.getAxis() == Direction.Axis.X)
                    .setValue(FenceBlock.WEST, along.getAxis() == Direction.Axis.X),
                false));
      }
    }
    return List.copyOf(plan);
  }

  private static List<Placement> stallBlueprint(Project project) {
    BlockPos o = project.origin();
    BlockState post = Blocks.OAK_FENCE.defaultBlockState();
    BlockState awning =
        net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .getOptional(
                Identifier.withDefaultNamespace(
                    net.minecraft.world.item.DyeColor.byId(project.direction()).getName()
                        + "_wool"))
            .orElse(Blocks.WHITE_WOOL)
            .defaultBlockState();
    List<Placement> plan = new ArrayList<>();
    plan.add(
        new Placement(
            o.offset(1, 0, 1),
            Blocks.BARREL.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP),
            false));
    for (int y = 0; y < 2; y++) {
      for (int[] corner : new int[][] {{0, 0}, {2, 0}, {0, 2}, {2, 2}}) {
        plan.add(new Placement(o.offset(corner[0], y, corner[1]), post, false));
      }
    }
    for (int x = 0; x < 3; x++) {
      for (int z = 0; z < 3; z++) {
        plan.add(new Placement(o.offset(x, 2, z), awning, false));
      }
    }
    return List.copyOf(plan);
  }

  private static List<Placement> fenceBlueprint(ServerLevel level, Project project) {
    Direction along = Direction.from2DDataValue(project.direction());
    BlockState post =
        fence(level, project.origin())
            .setValue(FenceBlock.NORTH, along.getAxis() == Direction.Axis.Z)
            .setValue(FenceBlock.SOUTH, along.getAxis() == Direction.Axis.Z)
            .setValue(FenceBlock.EAST, along.getAxis() == Direction.Axis.X)
            .setValue(FenceBlock.WEST, along.getAxis() == Direction.Axis.X);
    List<Placement> plan = new ArrayList<>();
    for (int i = 0; i < project.size(); i++) {
      plan.add(new Placement(project.origin().relative(along, i), post, true));
    }
    return List.copyOf(plan);
  }

  public record View(
      String kind,
      String name,
      BoundingBox extent,
      int placed,
      int total,
      int wood,
      int stone,
      @Nullable BlockPos next) {}

  public static List<View> views(ServerLevel level, VillageNode node) {
    List<View> views = new ArrayList<>();
    for (Project project : LifeData.get(level).projects(node.id())) {
      List<Placement> plan = blueprint(level, project);
      int next = project.placed() < plan.size() ? project.placed() : -1;
      views.add(
          new View(
              project.kind(),
              project
                  .template()
                  .map(Identifier::getPath)
                  .map(path -> path.substring(path.lastIndexOf('/') + 1))
                  .orElse(project.kind().toLowerCase(java.util.Locale.ROOT)),
              extent(level, project),
              project.placed(),
              plan.size(),
              project.wood(),
              project.stone(),
              next >= 0 ? plan.get(next).pos() : null));
    }
    return views;
  }

  public static @Nullable String planNow(
      ServerLevel level, VillageWorldManager manager, VillageNode node, @Nullable Kind force) {
    if (node.state() == null || node.structureBox() == null) {
      return null;
    }
    long day = GuestTime.day(GuestTime.gameTime(level));
    long salt = GuestHash.hash(level.getSeed(), node.id(), day, System.nanoTime());
    Project project =
        force == null
            ? plan(level, manager, node, node.state(), day)
            : switch (force) {
              case HOUSE, FARM -> templateSite(level, node, force, salt, day);
              case BRIDGE -> bridge(level, manager, node, day);
              case STALL -> stall(level, node, salt, day);
              case FENCE -> fence(level, node, salt, day);
            };
    if (project != null && force != null) {
      LifeData.get(level).replace(project, project);
    }
    return project == null ? null : project.kind() + " @ " + project.origin().toShortString();
  }

  public static int cancel(ServerLevel level, VillageNode node) {
    List<Project> projects = LifeData.get(level).projects(node.id());
    projects.forEach(project -> complete(level, project));
    return projects.size();
  }

  public static int advance(ServerLevel level, VillageNode node, int blocks) {
    List<Project> projects = LifeData.get(level).projects(node.id());
    if (projects.isEmpty()) {
      return 0;
    }
    Key key = Key.of(projects.get(0));
    int placed = 0;
    for (int i = 0; i < blocks && find(level, node, key) != null; i++) {
      if (placeNext(level, node, key, true)) {
        placed++;
      }
    }
    return placed;
  }
}
