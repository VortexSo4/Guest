package com.vortexso.guest_wilds.fish;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_wilds.Ecology;
import com.vortexso.guest_wilds.GuestWilds;
import com.vortexso.guest_wilds.Membership;
import com.vortexso.guest_wilds.WildsParameters;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;
import org.jspecify.annotations.Nullable;

/**
 * Fish shoals per 32x32 water cell. Abundance regrows logistically between visits and fishing
 * depletes it, so a spot fished out today is poor tomorrow. Salmon crowd rivers during the spawning
 * run (late summer to autumn) and thin out at sea at the same time.
 */
public final class Shoals extends SavedData {
  public static final int CELL_SHIFT = 5;
  private static final WildsParameters P = WildsParameters.DEFAULT;

  public enum Water {
    OCEAN,
    RIVER,
    LAKE
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
          Identifier.fromNamespaceAndPath(GuestWilds.MODID, "shoals"), Shoals::new, CODEC);

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

  /** The shoal of this water cell, created at carrying capacity on first contact. */
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
    // A shoal fished to zero is re-seeded from neighbouring water, never truly empty.
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

  // ---- fishing ----

  public void onFished(ServerLevel level, ItemFishedEvent event) {
    FishingHook hook = event.getHookEntity();
    Player player = event.getEntity();
    long now = GuestTime.gameTime(level);
    Shoal shoal = at(level, hook.blockPosition());
    double ratio = shoal.n / capacity(shoal, now);
    RandomSource random = level.getRandom();
    int fish = 0;
    for (ItemStack stack : event.getDrops()) {
      if (stack.is(ItemTags.FISHES)) {
        fish += stack.getCount();
      }
    }
    if (fish > 0) {
      // A fished-out spot mostly yields nothing: the bite was a lone straggler that got away.
      if (ratio < P.depletedRatio() && random.nextDouble() > ratio / P.depletedRatio()) {
        event.setCanceled(true);
        return;
      }
      int extra =
          Ecology.extraFish(ratio, hook.tickCount, P.multiCatchRatio(), P.waitTicksPerExtraFish());
      for (int i = 0; i < extra; i++) {
        boolean salmon = random.nextDouble() < salmonShare(shoal, now);
        give(level, hook, player, new ItemStack(salmon ? Items.SALMON : Items.COD));
      }
      shoal.n = Math.max(0.0, shoal.n - fish - extra);
      setDirty();
    }
    if (random.nextDouble() < P.rareFindChance()) {
      ItemStack find = ruinFind(level, hook, player);
      if (!find.isEmpty()) {
        give(level, hook, player, find);
      }
    }
  }

  /** Flies toward the angler exactly like vanilla catches do. */
  private static void give(ServerLevel level, FishingHook hook, Player player, ItemStack stack) {
    ItemEntity item = new ItemEntity(level, hook.getX(), hook.getY(), hook.getZ(), stack);
    double xa = player.getX() - hook.getX();
    double ya = player.getY() - hook.getY();
    double za = player.getZ() - hook.getZ();
    item.setDeltaMovement(
        xa * 0.1, ya * 0.1 + Math.sqrt(Math.sqrt(xa * xa + ya * ya + za * za)) * 0.08, za * 0.1);
    level.addFreshEntity(item);
  }

  /** Something from the loot theme of a wreck or ruin within two chunks of the bobber. */
  private static ItemStack ruinFind(ServerLevel level, FishingHook hook, Player player) {
    ResourceKey<LootTable> table = nearbyRuinLoot(level, hook.blockPosition());
    if (table == null) {
      return ItemStack.EMPTY;
    }
    LootParams.Builder params =
        new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, hook.position());
    LootParams built =
        table == BuiltInLootTables.TRAIL_RUINS_ARCHAEOLOGY_COMMON
            ? params
                .withParameter(LootContextParams.THIS_ENTITY, player)
                .withParameter(LootContextParams.TOOL, player.getMainHandItem())
                .create(LootContextParamSets.ARCHAEOLOGY)
            : params.create(LootContextParamSets.CHEST);
    List<ItemStack> items =
        level.getServer().reloadableRegistries().getLootTable(table).getRandomItems(built);
    return items.isEmpty() ? ItemStack.EMPTY : items.get(level.getRandom().nextInt(items.size()));
  }

  private static @Nullable ResourceKey<LootTable> nearbyRuinLoot(ServerLevel level, BlockPos pos) {
    var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
    for (int dx = -2; dx <= 2; dx++) {
      for (int dz = -2; dz <= 2; dz++) {
        int chunkX = (pos.getX() >> 4) + dx;
        int chunkZ = (pos.getZ() >> 4) + dz;
        if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
          continue;
        }
        BlockPos probe = new BlockPos((chunkX << 4) + 8, pos.getY(), (chunkZ << 4) + 8);
        for (Structure structure : level.structureManager().getAllStructuresAt(probe).keySet()) {
          Identifier id = registry.getKey(structure);
          if (id == null) {
            continue;
          }
          String path = id.getPath();
          if (path.startsWith("shipwreck")) {
            return BuiltInLootTables.SHIPWRECK_SUPPLY;
          }
          if (path.startsWith("ocean_ruin")) {
            return BuiltInLootTables.UNDERWATER_RUIN_SMALL;
          }
          if (path.startsWith("trail_ruins")) {
            return BuiltInLootTables.TRAIL_RUINS_ARCHAEOLOGY_COMMON;
          }
        }
      }
    }
    return null;
  }

  // ---- observation ----

  /** Shows part of a rich shoal as live fish in water near the player. */
  public void materializeNear(ServerLevel level, ServerPlayer player) {
    RandomSource random = level.getRandom();
    double angle = random.nextDouble() * Math.PI * 2.0;
    double distance = 12.0 + random.nextDouble() * 16.0;
    int x = player.getBlockX() + (int) (Math.cos(angle) * distance);
    int z = player.getBlockZ() + (int) (Math.sin(angle) * distance);
    if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
      return;
    }
    int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
    BlockPos at = new BlockPos(x, top - 1, z);
    if (!level.getFluidState(at).is(FluidTags.WATER)
        || !level.getFluidState(at.above()).is(FluidTags.WATER)) {
      return;
    }
    long now = GuestTime.gameTime(level);
    Shoal shoal = at(level, at);
    if (shoal.n / capacity(shoal, now) < P.visibleFishRatio()
        || shoal.concrete >= P.maxVisibleFish()) {
      return;
    }
    boolean salmon = random.nextDouble() < salmonShare(shoal, now);
    EntityType<? extends Mob> type = salmon ? EntityType.SALMON : EntityType.COD;
    Membership.spawn(
        type,
        level,
        at,
        new Membership(Membership.Kind.SHOAL, shoal.cell, salmon ? "salmon" : "cod"));
  }

  /** Shoal members count loaded fish only: they are neither persistent nor worth tracking away. */
  public void onMemberJoin(Membership membership) {
    Shoal shoal = shoals.get(membership.node());
    if (shoal != null) {
      shoal.concrete++;
      setDirty();
    }
  }

  /** Despawned or unloaded fish just return to the shoal; only a real death reduces it. */
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
    // A full, unwatched shoal is the default state of its water: forget it, it is re-derivable.
    if (shoal.concrete == 0 && shoal.n >= 0.99 * capacity(shoal, now)) {
      shoals.remove(shoal.cell);
    }
    setDirty();
  }
}
