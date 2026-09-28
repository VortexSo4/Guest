package com.vortexso.guest_settlements.village;

import com.vortexso.guest_settlements.GuestSettlements;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.util.Util;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.jspecify.annotations.Nullable;

public final class RoutePlanner {

  public interface Terrain {

    int surface(int x, int z);

    boolean water(int x, int z, int surface);
  }

  public record Parameters(
      int cell,
      double climbCost,
      double maxSlope,
      double steepCost,
      double waterCost,
      double detour,
      double greed,
      int maxNodes) {}

  public static final Parameters DEFAULT = new Parameters(16, 8.0, 0.5, 4.0, 3.0, 1.5, 2.0, 20_000);

  public record Plan(List<BlockPos> waypoints, int samples, int expanded, boolean straight) {}

  private record Key(long first, long second) {}

  private record Node(long key, double g, double f) {}

  private static final int MAX_CACHED = 512;
  private static final int STRAIGHT_SPACING = 4;
  private static final int[] DI = {1, -1, 0, 0, 1, 1, -1, -1};
  private static final int[] DJ = {0, 0, 1, -1, 1, -1, 1, -1};
  private static final Map<ServerLevel, Map<Key, List<BlockPos>>> CACHE = new WeakHashMap<>();
  private static final Map<Key, CompletableFuture<Plan>> IN_FLIGHT = new ConcurrentHashMap<>();

  private RoutePlanner() {}

  public static List<BlockPos> route(ServerLevel level, BlockPos from, BlockPos to) {
    List<BlockPos> route = cached(level, from, to);
    if (route == null) {
      route = replan(level, from, to).waypoints();
    }
    return route.getFirst().equals(from) ? route : route.reversed();
  }

  public static Plan replan(ServerLevel level, BlockPos from, BlockPos to) {
    boolean swap = from.asLong() > to.asLong();
    BlockPos first = swap ? to : from;
    BlockPos second = swap ? from : to;
    Plan plan = plan(terrain(level), first, second, DEFAULT);
    synchronized (CACHE) {
      Map<Key, List<BlockPos>> routes =
          CACHE.computeIfAbsent(level, ignored -> new ConcurrentHashMap<>());
      if (routes.size() >= MAX_CACHED) {
        routes.clear();
      }
      routes.put(new Key(first.asLong(), second.asLong()), plan.waypoints());
    }
    return plan;
  }

  public static void whenPlanned(
      ServerLevel level, BlockPos from, BlockPos to, Consumer<List<BlockPos>> action) {
    if (cached(level, from, to) != null) {
      action.accept(route(level, from, to));
      return;
    }
    Key key =
        from.asLong() < to.asLong()
            ? new Key(from.asLong(), to.asLong())
            : new Key(to.asLong(), from.asLong());
    IN_FLIGHT
        .computeIfAbsent(
            key,
            ignored ->
                CompletableFuture.supplyAsync(
                        () -> replan(level, from, to), Util.backgroundExecutor())
                    .whenComplete(
                        (plan, error) -> {
                          IN_FLIGHT.remove(key);
                          if (error != null) {
                            GuestSettlements.LOGGER.error("Road planning failed", error);
                          }
                        }))
        .thenAcceptAsync(plan -> action.accept(route(level, from, to)), level.getServer());
  }

  public static @Nullable List<BlockPos> cached(ServerLevel level, BlockPos from, BlockPos to) {
    boolean swap = from.asLong() > to.asLong();
    Map<Key, List<BlockPos>> routes;
    synchronized (CACHE) {
      routes = CACHE.get(level);
    }
    List<BlockPos> route =
        routes == null
            ? null
            : routes.get(
                swap ? new Key(to.asLong(), from.asLong()) : new Key(from.asLong(), to.asLong()));
    return route == null || !swap ? route : route.reversed();
  }

  public static Terrain terrain(ServerLevel level) {
    ChunkGenerator generator = level.getChunkSource().getGenerator();
    RandomState random = level.getChunkSource().randomState();
    int seaLevel = generator.getSeaLevel();
    if (generator instanceof NoiseBasedChunkGenerator) {
      return new NoiseTerrain(random, seaLevel, level.getMinY(), level.getMaxY());
    }
    return new Terrain() {
      @Override
      public int surface(int x, int z) {
        return generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, random);
      }

      @Override
      public boolean water(int x, int z, int surface) {
        return surface <= seaLevel
            && generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random)
                < surface;
      }
    };
  }

  public static Plan plan(Terrain terrain, BlockPos from, BlockPos to, Parameters p) {
    Grid grid = new Grid(terrain, from.getX(), from.getZ(), p.cell());
    double length = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
    int goalI = Math.round((to.getX() - from.getX()) / (float) p.cell());
    int goalJ = Math.round((to.getZ() - from.getZ()) / (float) p.cell());
    if (Math.abs(goalI) + Math.abs(goalJ) <= 1) {
      return new Plan(List.of(from, to), 0, 0, true);
    }
    long start = key(0, 0);
    long goal = key(goalI, goalJ);
    double bound = p.detour() * length + 2.0 * p.cell();

    Long2DoubleOpenHashMap best = new Long2DoubleOpenHashMap();
    best.defaultReturnValue(Double.MAX_VALUE);
    Long2LongOpenHashMap parent = new Long2LongOpenHashMap();
    PriorityQueue<Node> open =
        new PriorityQueue<>(Comparator.comparingDouble(Node::f).thenComparingLong(Node::key));
    best.put(start, 0.0);
    open.add(new Node(start, 0.0, p.greed() * length));
    int expanded = 0;

    while (!open.isEmpty()) {
      Node node = open.poll();
      if (node.g() > best.get(node.key())) {
        continue;
      }
      if (node.key() == goal) {
        List<Long> path = new ArrayList<>();
        for (long k = goal; ; k = parent.get(k)) {
          path.add(k);
          if (k == start) {
            break;
          }
        }
        return new Plan(
            smooth(grid, path.reversed(), from, to, p), grid.samples.size(), expanded, false);
      }
      if (++expanded > p.maxNodes()) {
        break;
      }
      int i = i(node.key());
      int j = j(node.key());
      for (int d = 0; d < DI.length; d++) {
        int ni = i + DI[d];
        int nj = j + DJ[d];
        double x = (double) ni * p.cell();
        double z = (double) nj * p.cell();
        double toGoal = Math.hypot(goalI * (double) p.cell() - x, goalJ * (double) p.cell() - z);
        if (Math.hypot(x, z) + toGoal > bound) {
          continue;
        }
        long next = key(ni, nj);
        double g = node.g() + step(grid, node.key(), next, d >= 4, p);
        if (g < best.get(next)) {
          best.put(next, g);
          parent.put(next, node.key());
          open.add(new Node(next, g, g + p.greed() * toGoal));
        }
      }
    }
    return new Plan(straight(grid, from, to, p), grid.samples.size(), expanded, true);
  }

  private static double step(Grid grid, long from, long to, boolean diagonal, Parameters p) {
    double distance = diagonal ? p.cell() * Math.sqrt(2.0) : p.cell();
    double rise = Math.abs(grid.height(to) - grid.height(from));
    double cost = distance + p.climbCost() * rise;
    double excess = rise - p.maxSlope() * distance;
    if (excess > 0.0) {
      cost += p.steepCost() * excess * excess;
    }
    return grid.water(to) ? cost + p.waterCost() * distance : cost;
  }

  private static List<BlockPos> smooth(
      Grid grid, List<Long> path, BlockPos from, BlockPos to, Parameters p) {
    List<BlockPos> result = new ArrayList<>();
    result.add(from);
    int anchor = 0;
    for (int k = 1; k < path.size() - 1; k++) {
      if (!direct(grid, path, anchor, k + 1, p)) {
        result.add(grid.pos(path.get(k)));
        anchor = k;
      }
    }
    result.add(to);
    return List.copyOf(result);
  }

  private static boolean direct(Grid grid, List<Long> path, int from, int to, Parameters p) {
    double pathClimb = 0.0;
    for (int k = from; k < to; k++) {
      if (grid.water(path.get(k)) || grid.water(path.get(k + 1))) {
        return false;
      }
      pathClimb += Math.abs(grid.height(path.get(k + 1)) - grid.height(path.get(k)));
    }
    long start = path.get(from);
    long end = path.get(to);
    int di = i(end) - i(start);
    int dj = j(end) - j(start);
    int steps = Math.max(Math.abs(di), Math.abs(dj));
    double stepLength = Math.hypot(di, dj) * p.cell() / steps;
    int previous = grid.height(start);
    double climb = 0.0;
    for (int s = 1; s <= steps; s++) {
      long k =
          key(
              i(start) + Math.round(di * s / (float) steps),
              j(start) + Math.round(dj * s / (float) steps));
      int height = grid.height(k);
      int rise = Math.abs(height - previous);
      if (grid.water(k) || rise > p.maxSlope() * stepLength) {
        return false;
      }
      climb += rise;
      previous = height;
    }
    return climb <= pathClimb + 2.0;
  }

  private static List<BlockPos> straight(Grid grid, BlockPos from, BlockPos to, Parameters p) {
    List<BlockPos> result = new ArrayList<>();
    result.add(from);
    double length = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
    int count = (int) (length / (p.cell() * STRAIGHT_SPACING));
    for (int s = 1; s < count; s++) {
      double t = s / (double) count;
      int x = (int) Math.round(from.getX() + (to.getX() - from.getX()) * t);
      int z = (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * t);
      result.add(new BlockPos(x, grid.terrain.surface(x, z), z));
    }
    result.add(to);
    return List.copyOf(result);
  }

  private static long key(int i, int j) {
    return ((long) i << 32) | (j & 0xFFFFFFFFL);
  }

  private static int i(long key) {
    return (int) (key >> 32);
  }

  private static int j(long key) {
    return (int) key;
  }

  private static final class NoiseTerrain implements Terrain {
    private static final int STEP = 8;

    private final DensityFunction density;
    private final int seaLevel;
    private final int minY;
    private final int maxY;
    private int guess;
    private int ground;

    NoiseTerrain(RandomState random, int seaLevel, int minY, int maxY) {
      this.density = random.router().finalDensity().mapAll(NoiseTerrain::columnCached);
      this.seaLevel = seaLevel;
      this.minY = minY;
      this.maxY = maxY;
      this.guess = seaLevel;
    }

    @Override
    public int surface(int x, int z) {
      int top = maxY - 3 * STEP;
      int y = Math.min(Math.max(guess, seaLevel) + 2 * STEP, top);

      while (y < top && !(air(x, y, z) && air(x, y + STEP, z) && air(x, y + 2 * STEP, z))) {
        y += STEP;
      }
      int high = y;
      int low = y - STEP;
      while (low > minY && air(x, low, z)) {
        high = low;
        low -= STEP;
      }
      while (high - low > 1) {
        int middle = (low + high) >> 1;
        if (air(x, middle, z)) {
          high = middle;
        } else {
          low = middle;
        }
      }
      guess = high;
      ground = high;
      return Math.max(high, seaLevel);
    }

    @Override
    public boolean water(int x, int z, int surface) {
      return ground < seaLevel;
    }

    private boolean air(int x, int y, int z) {
      return density.compute(new DensityFunction.SinglePointContext(x, y, z)) <= 0.0;
    }

    private static DensityFunction columnCached(DensityFunction function) {
      if (!(function instanceof DensityFunctions.MarkerOrMarked marker)) {
        return function;
      }
      Object type = marker.type();
      String name = ((StringRepresentable) type).getSerializedName();
      return name.equals("flat_cache") || name.equals("cache_2d")
          ? new ColumnCache(marker.wrapped())
          : marker.wrapped();
    }
  }

  private static final class ColumnCache implements DensityFunction.SimpleFunction {
    private final DensityFunction wrapped;
    private long column = Long.MIN_VALUE;
    private double value;

    ColumnCache(DensityFunction wrapped) {
      this.wrapped = wrapped;
    }

    @Override
    public double compute(DensityFunction.FunctionContext context) {
      long key = key(context.blockX(), context.blockZ());
      if (key != column) {
        value = wrapped.compute(context);
        column = key;
      }
      return value;
    }

    @Override
    public double minValue() {
      return wrapped.minValue();
    }

    @Override
    public double maxValue() {
      return wrapped.maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
      return wrapped.codec();
    }
  }

  private static final class Grid {
    final Terrain terrain;
    final int originX;
    final int originZ;
    final int cell;
    final Long2IntOpenHashMap samples = new Long2IntOpenHashMap();

    Grid(Terrain terrain, int originX, int originZ, int cell) {
      this.terrain = terrain;
      this.originX = originX;
      this.originZ = originZ;
      this.cell = cell;
      samples.defaultReturnValue(Integer.MIN_VALUE);
    }

    int height(long key) {
      return sample(key) >> 1;
    }

    boolean water(long key) {
      return (sample(key) & 1) != 0;
    }

    BlockPos pos(long key) {
      return new BlockPos(originX + i(key) * cell, height(key), originZ + j(key) * cell);
    }

    private int sample(long key) {
      int sample = samples.get(key);
      if (sample == Integer.MIN_VALUE) {
        int x = originX + i(key) * cell;
        int z = originZ + j(key) * cell;
        int surface = terrain.surface(x, z);
        sample = surface * 2 + (terrain.water(x, z, surface) ? 1 : 0);
        samples.put(key, sample);
      }
      return sample;
    }
  }
}
