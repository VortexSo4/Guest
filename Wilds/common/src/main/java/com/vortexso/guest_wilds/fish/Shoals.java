package com.vortexso.guest_wilds.fish;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_wilds.Ecology;
import com.vortexso.guest_wilds.GuestWilds;
import com.vortexso.guest_wilds.Membership;
import com.vortexso.guest_wilds.WildsConfig;
import com.vortexso.guest_wilds.WildsParameters;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.animal.fish.AbstractSchoolingFish;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

public final class Shoals extends SavedData {
  public static final int CELL_SHIFT = 5;
  private static final WildsParameters P = WildsParameters.DEFAULT;
  private static final int SCHOOL_SPREAD = 2;
  private static final double CROWD_RADIUS = 48.0;

  private static final double LUCK_RELICS = 0.1;

  public enum Water {
    OCEAN,
    RIVER,
    LAKE
  }

  public enum Place {
    FISH,
    MIXED,
    RELICS;

    public String id() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public record Spot(Place place, @Nullable ResourceKey<LootTable> relics) {
    static final Spot FISH_ONLY = new Spot(Place.FISH, null);
  }

  public static final class Shoal {
    public final long cell;
    public final Water water;
    double n;
    long time;
    int concrete;

    static final Codec<Shoal> CODEC =
        RecordCodecBuilder.create(
            i ->
                i.group(
                        Codec.LONG.fieldOf("cell").forGetter(s -> s.cell),
                        Codec.INT.fieldOf("water").forGetter(s -> s.water.ordinal()),
                        Codec.DOUBLE.fieldOf("n").forGetter(s -> s.n),
                        Codec.LONG.fieldOf("time").forGetter(s -> s.time),
                        Codec.INT.fieldOf("concrete").forGetter(s -> s.concrete))
                    .apply(
                        i,
                        (cell, water, n, time, concrete) ->
                            new Shoal(cell, Water.values()[water], n, time, concrete)));

    Shoal(long cell, Water water, double n, long time, int concrete) {
      this.cell = cell;
      this.water = water;
      this.n = n;
      this.time = time;
      this.concrete = concrete;
    }

    public double fish() {
      return n;
    }

    public int concrete() {
      return concrete;
    }

    public BlockPos center() {
      return new BlockPos(
          (ChunkPos.getX(cell) << CELL_SHIFT) + 16, 0, (ChunkPos.getZ(cell) << CELL_SHIFT) + 16);
    }
  }

  public static final Codec<Shoals> CODEC =
      Shoal.CODEC.listOf().xmap(Shoals::new, shoals -> List.copyOf(shoals.shoals.values()));

  public static final SavedDataType<Shoals> TYPE =
      new SavedDataType<>(
          Identifier.fromNamespaceAndPath(GuestWilds.MODID, "shoals"), Shoals::new, CODEC, null);

  private final Long2ObjectOpenHashMap<Shoal> shoals = new Long2ObjectOpenHashMap<>();

  private Shoals() {}

  private Shoals(List<Shoal> saved) {
    saved.forEach(shoal -> shoals.put(shoal.cell, shoal));
  }

  public static Shoals get(ServerLevel level) {
    return level.getDataStorage().computeIfAbsent(TYPE);
  }

  public List<Shoal> shoals() {
    return List.copyOf(shoals.values());
  }

  public @Nullable Shoal shoal(long cell) {
    return shoals.get(cell);
  }

  public Shoal at(ServerLevel level, BlockPos pos) {
    long cell = ChunkPos.pack(pos.getX() >> CELL_SHIFT, pos.getZ() >> CELL_SHIFT);
    Shoal shoal = shoals.get(cell);
    long now = GuestTime.gameTime(level);
    if (shoal == null) {
      Holder<Biome> biome = level.getBiome(pos);
      Water water =
          biome.is(BiomeTags.IS_OCEAN)
              ? Water.OCEAN
              : biome.is(BiomeTags.IS_RIVER) ? Water.RIVER : Water.LAKE;
      shoal = new Shoal(cell, water, 0.0, now, 0);
      shoal.n = capacity(shoal, now);
      shoals.put(cell, shoal);
      setDirty();
    }
    advance(shoal, now);
    return shoal;
  }

  private void advance(Shoal shoal, long now) {
    if (now <= shoal.time) {
      return;
    }

    double n = Math.max(shoal.n, 1.0);
    shoal.n =
        Ecology.logistic(
            n,
            capacity(shoal, now),
            P.shoalRegrowthPerDay(),
            (now - shoal.time) / (double) Ecology.TICKS_PER_DAY);
    shoal.time = now;
    setDirty();
  }

  public static double capacity(Shoal shoal, long now) {
    return switch (shoal.water) {
      case OCEAN -> P.oceanShoal();
      case RIVER -> P.riverShoal() * (1.0 + 0.5 * run(now));
      case LAKE -> P.lakeShoal();
    };
  }

  public static double salmonShare(Shoal shoal, long now) {
    return switch (shoal.water) {
      case OCEAN -> 0.25 * (1.0 - 0.6 * run(now));
      case RIVER -> 0.25 + 0.5 * run(now);
      case LAKE -> 0.1;
    };
  }

  private static double run(long now) {
    return Ecology.salmonRun(GuestTime.dayOfYear(now), GuestTime.DAYS_PER_YEAR);
  }

  public void onFished(ServerLevel level, FishingHook hook, Player player) {
    long now = GuestTime.gameTime(level);
    Shoal shoal = at(level, hook.blockPosition());
    double ratio = shoal.n / capacity(shoal, now);
    RandomSource random = level.getRandom();
    Spot spot =
        WildsConfig.FISHING_BY_PLACE.get() ? spot(level, hook.blockPosition()) : Spot.FISH_ONLY;

    List<ItemStack> caught = new ArrayList<>();
    int fish = 0;
    if (spot.place() == Place.RELICS) {
      caught.addAll(relics(level, hook, player, spot.relics()));
    } else if (ratio >= P.depletedRatio() || random.nextDouble() < ratio / P.depletedRatio()) {

      fish = 1;
      if (ratio >= P.depletedRatio()) {

        fish +=
            Ecology.extraFish(
                ratio, hook.tickCount, P.multiCatchRatio(), P.waitTicksPerExtraFish());
      }
      if (spot.place() == Place.MIXED
          && random.nextDouble() < P.mixedArtifactChance() + LUCK_RELICS * luck(level, player)) {
        fish--;
        caught.addAll(relics(level, hook, player, spot.relics()));
      }
      for (int i = 0; i < fish; i++) {
        boolean salmon = random.nextDouble() < salmonShare(shoal, now);
        caught.add(new ItemStack(salmon ? Items.SALMON : Items.COD));
      }
      shoal.n = Math.max(0.0, shoal.n - fish);
      setDirty();
    }
    if (player instanceof ServerPlayer server) {
      ItemStack rod =
          player.getMainHandItem().is(Items.FISHING_ROD)
              ? player.getMainHandItem()
              : player.getOffhandItem();
      CriteriaTriggers.FISHING_ROD_HOOKED.trigger(server, rod, hook, caught);
    }
    for (ItemStack stack : caught) {
      give(level, hook, player, stack);
      if (stack.is(Items.COD) || stack.is(Items.SALMON)) {
        player.awardStat(Stats.FISH_CAUGHT, 1);
      }
    }
  }

  private static int luck(ServerLevel level, Player player) {
    ItemStack rod =
        player.getMainHandItem().is(Items.FISHING_ROD)
            ? player.getMainHandItem()
            : player.getOffhandItem();
    return Math.max(
        0, EnchantmentHelper.getFishingLuckBonus(level, rod, player) + (int) player.getLuck());
  }

  private static void give(ServerLevel level, FishingHook hook, Player player, ItemStack stack) {
    ItemEntity item = new ItemEntity(level, hook.getX(), hook.getY(), hook.getZ(), stack);
    double xa = player.getX() - hook.getX();
    double ya = player.getY() - hook.getY();
    double za = player.getZ() - hook.getZ();
    item.setDeltaMovement(
        xa * 0.1, ya * 0.1 + Math.sqrt(Math.sqrt(xa * xa + ya * ya + za * za)) * 0.08, za * 0.1);
    level.addFreshEntity(item);
    level.addFreshEntity(
        new ExperienceOrb(
            level,
            player.getX(),
            player.getY() + 0.5,
            player.getZ() + 0.5,
            level.getRandom().nextInt(6) + 1));
  }

  private static List<ItemStack> relics(
      ServerLevel level, FishingHook hook, Player player, @Nullable ResourceKey<LootTable> table) {
    if (table == null) {
      return List.of();
    }
    LootParams.Builder params =
        new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, hook.position());
    boolean archaeology = table.identifier().getPath().startsWith("archaeology/");
    LootParams built =
        archaeology
            ? params
                .withParameter(LootContextParams.THIS_ENTITY, player)
                .withParameter(LootContextParams.TOOL, player.getMainHandItem())
                .create(LootContextParamSets.ARCHAEOLOGY)
            : params.create(LootContextParamSets.CHEST);
    List<ItemStack> items =
        level.getServer().reloadableRegistries().getLootTable(table).getRandomItems(built);

    return items.isEmpty()
        ? List.of()
        : List.of(items.get(level.getRandom().nextInt(items.size())));
  }

  public static Spot spot(ServerLevel level, BlockPos hook) {
    var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
    ResourceKey<LootTable> near = null;
    for (int dx = -2; dx <= 2; dx++) {
      for (int dz = -2; dz <= 2; dz++) {
        int chunkX = (hook.getX() >> 4) + dx;
        int chunkZ = (hook.getZ() >> 4) + dz;
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
          continue;
        }
        for (Structure structure : chunk.getAllReferences().keySet()) {
          Identifier id = registry.getKey(structure);
          ResourceKey<LootTable> table =
              id == null ? null : relicTable(id.getPath(), level.getRandom());
          if (table == null) {
            continue;
          }
          if (dx == 0 && dz == 0 && over(level, chunk.getPos(), structure, hook)) {
            return new Spot(Place.RELICS, table);
          }
          near = table;
        }
      }
    }
    return near != null ? new Spot(Place.MIXED, near) : Spot.FISH_ONLY;
  }

  private static boolean over(
      ServerLevel level, ChunkPos chunk, Structure structure, BlockPos hook) {
    for (StructureStart start :
        level.structureManager().startsForStructure(chunk, s -> s == structure)) {
      for (StructurePiece piece : start.getPieces()) {
        var box = piece.getBoundingBox();
        if (hook.getX() >= box.minX()
            && hook.getX() <= box.maxX()
            && hook.getZ() >= box.minZ()
            && hook.getZ() <= box.maxZ()) {
          return true;
        }
      }
    }
    return false;
  }

  static @Nullable ResourceKey<LootTable> relicTable(String path, RandomSource random) {
    if (path.startsWith("shipwreck")) {
      return random.nextFloat() < 0.15F
          ? BuiltInLootTables.SHIPWRECK_TREASURE
          : BuiltInLootTables.SHIPWRECK_SUPPLY;
    }
    if (path.startsWith("ocean_ruin")) {
      return path.contains("warm")
          ? BuiltInLootTables.OCEAN_RUIN_WARM_ARCHAEOLOGY
          : BuiltInLootTables.OCEAN_RUIN_COLD_ARCHAEOLOGY;
    }
    if (path.startsWith("trail_ruins")) {
      return random.nextFloat() < 0.1F
          ? BuiltInLootTables.TRAIL_RUINS_ARCHAEOLOGY_RARE
          : BuiltInLootTables.TRAIL_RUINS_ARCHAEOLOGY_COMMON;
    }
    if (path.startsWith("village")) {
      return BuiltInLootTables.VILLAGE_FISHER;
    }
    if (path.startsWith("ruined_portal")) {
      return BuiltInLootTables.RUINED_PORTAL;
    }
    return null;
  }

  public void materializeNear(ServerLevel level, ServerPlayer player) {
    RandomSource random = level.getRandom();
    double angle = random.nextDouble() * Math.PI * 2.0;
    double distance = 16.0 + random.nextDouble() * 16.0;
    int x = player.getBlockX() + (int) (Math.cos(angle) * distance);
    int z = player.getBlockZ() + (int) (Math.sin(angle) * distance);
    if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
      return;
    }
    int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
    BlockPos at = new BlockPos(x, surface - 2, z);
    if (!openWater(level, at)) {
      return;
    }
    long now = GuestTime.gameTime(level);
    Shoal shoal = at(level, at);
    double ratio = shoal.n / capacity(shoal, now);
    if (ratio < P.visibleFishRatio() || shoal.concrete > 0) {
      return;
    }
    int crowd =
        level
            .getEntitiesOfClass(
                AbstractSchoolingFish.class, new AABB(player.blockPosition()).inflate(CROWD_RADIUS))
            .size();
    int size =
        Math.min(
            Math.min(WildsConfig.FISH_SCHOOL_SIZE.get(), (int) shoal.n),
            P.schoolMin()
                + (int)
                    ((P.schoolMax() - P.schoolMin()) * Math.clamp((ratio - 0.5) / 0.5, 0.0, 1.0)));
    if (size < 2 || crowd + size > P.maxVisibleFish()) {
      return;
    }
    boolean salmon = random.nextDouble() < salmonShare(shoal, now);
    EntityType<? extends AbstractSchoolingFish> type = salmon ? EntityType.SALMON : EntityType.COD;
    Membership membership =
        new Membership(Membership.Kind.SHOAL, shoal.cell, salmon ? "salmon" : "cod");
    AbstractSchoolingFish leader = Membership.spawn(type, level, at, membership);
    if (leader == null) {
      return;
    }
    for (int i = 1; i < size; i++) {
      BlockPos spot =
          at.offset(
              random.nextInt(2 * SCHOOL_SPREAD + 1) - SCHOOL_SPREAD,
              random.nextInt(3) - 1,
              random.nextInt(2 * SCHOOL_SPREAD + 1) - SCHOOL_SPREAD);
      if (!level.getFluidState(spot).is(FluidTags.WATER)) {
        spot = at;
      }
      Membership.spawn(type, level, spot, membership, fish -> fish.startFollowing(leader));
    }
  }

  private static boolean openWater(ServerLevel level, BlockPos at) {
    for (BlockPos probe :
        new BlockPos[] {
          at, at.above(), at.below(), at.east(2), at.west(2), at.north(2), at.south(2)
        }) {
      if (!GuestWilds.loaded(level, probe) || !level.getFluidState(probe).is(FluidTags.WATER)) {
        return false;
      }
    }
    return true;
  }

  public void onMemberJoin(Membership membership) {
    Shoal shoal = shoals.get(membership.node());
    if (shoal != null) {
      shoal.concrete++;
      setDirty();
    }
  }

  public void onMemberLeave(ServerLevel level, Membership membership, boolean killed) {
    Shoal shoal = shoals.get(membership.node());
    if (shoal == null) {
      return;
    }
    long now = GuestTime.gameTime(level);
    shoal.concrete = Math.max(0, shoal.concrete - 1);
    if (killed) {
      advance(shoal, now);
      shoal.n = Math.max(0.0, shoal.n - 1.0);
    }

    if (shoal.concrete == 0 && shoal.n >= 0.99 * capacity(shoal, now)) {
      shoals.remove(shoal.cell);
    }
    setDirty();
  }
}
