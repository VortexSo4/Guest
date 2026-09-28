package com.vortexso.guest_architects.city;

import com.vortexso.guest_architects.GuestArchitects;
import com.vortexso.guest_architects.entity.Displays;
import com.vortexso.guest_core.api.GuestHash;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class MemoryPlace {
  static final Identifier SHRINE =
      Identifier.fromNamespaceAndPath(GuestArchitects.MODID, "memory/shrine");

  private static final String ROW_TAG = GuestArchitects.MODID + ".memory_row";
  private static final String SYMBOL_TAG = GuestArchitects.MODID + ".memory_symbol";
  private static final String RELIC_TAG = GuestArchitects.MODID + ".memory_relic";

  private static final long SALT_RELIC = 0x52454C43L;
  private static final long SALT_NAMES = 0x4E414D45L;
  private static final long SALT_KEEPER = 0x4B454550L;

  private static final int MIN_PERIOD = 2400;

  private static final int PERIOD_SPREAD = 3600;
  private static final int VISIT_WINDOW = 400;
  private static final int MAX_NAMES = 16;

  private static final int SEARCH_BUDGET = 400_000;

  private static final FontDescription GLYPHS =
      new FontDescription.Resource(Identifier.withDefaultNamespace("alt"));
  private static final int GLYPH_COLOR = 0x7FE9F0;

  private MemoryPlace() {}

  public enum Relic {
    GRASS,

    SCULK,

    OLD_ITEM
  }

  public record Markers(
      BoundingBox box,
      List<BlockPos> niches,
      BlockPos relic,
      BlockPos glyphRow,
      BlockPos symbol,
      BlockPos keeper,
      Direction front) {}

  public static Relic relic(long seed, long cityId) {
    return Relic.values()[
        (int) (GuestHash.unit(GuestHash.hash(seed, cityId, SALT_RELIC)) * Relic.values().length)];
  }

  static @Nullable Markers ensure(ServerLevel level, CitySite site) {
    if (site.record.memory.isEmpty()) {
      StructureTemplate shrine = level.getStructureManager().getOrCreate(SHRINE);
      Optional<CityRecord.Memory> spot = findSpot(level, site, shrine);
      if (spot.isEmpty()) {
        GuestArchitects.LOGGER.debug("City {}: no free spot for the memory place", site.id);
        return null;
      }
      CityRecord.Memory memory = spot.get();
      shrine.placeInWorld(
          level,
          memory.origin(),
          memory.origin(),
          settings(memory.rotation()),
          level.getRandom(),
          Block.UPDATE_CLIENTS);
      site.record.memory = spot;
      ArchitectsData.get(level).setDirty();
      Markers markers = markers(level, memory);
      placeRelic(level, site, markers);
      return markers;
    }
    return markers(level, site.record.memory.get());
  }

  private static StructurePlaceSettings settings(Rotation rotation) {
    return new StructurePlaceSettings()
        .setRotation(rotation)
        .addProcessor(BlockIgnoreProcessor.STRUCTURE_BLOCK);
  }

  private static Optional<CityRecord.Memory> findSpot(
      ServerLevel level, CitySite site, StructureTemplate shrine) {
    List<BoundingBox> areas = new ArrayList<>(site.quietBuildings);
    areas.add(site.portalBox);
    int[] budget = {SEARCH_BUDGET};
    for (BoundingBox area : areas) {
      for (int y = area.minY(); y <= area.maxY() - 4; y++) {
        for (int x = area.minX(); x <= area.maxX(); x++) {
          for (int z = area.minZ(); z <= area.maxZ(); z++) {
            BlockPos origin = new BlockPos(x, y, z);
            if (!level.isLoaded(origin)
                || !level.getBlockState(origin).isAir()
                || !sturdy(level, origin.below())) {
              continue;
            }
            for (Rotation rotation : Rotation.values()) {
              BoundingBox box = shrine.getBoundingBox(settings(rotation), origin);
              if (fits(level, box, site, budget)) {
                return Optional.of(new CityRecord.Memory(origin, rotation));
              }
              if (budget[0] <= 0) {
                return Optional.empty();
              }
            }
          }
        }
      }
    }
    return Optional.empty();
  }

  private static boolean fits(ServerLevel level, BoundingBox box, CitySite site, int[] budget) {
    if (!site.box.isInside(box.getCenter()) || site.inRitualArea(box.getCenter())) {
      return false;
    }
    for (BlockPos pos :
        BlockPos.betweenClosed(
            box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
      budget[0]--;
      if (!level.isLoaded(pos) || !level.getBlockState(pos).isAir()) {
        return false;
      }
      if (pos.getY() == box.minY() && !sturdy(level, pos.below())) {
        return false;
      }
    }
    return true;
  }

  private static boolean sturdy(ServerLevel level, BlockPos pos) {
    return level.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP);
  }

  static Markers markers(ServerLevel level, CityRecord.Memory memory) {
    StructureTemplate shrine = level.getStructureManager().getOrCreate(SHRINE);
    StructurePlaceSettings settings = settings(memory.rotation());
    List<BlockPos> niches = new ArrayList<>();
    BlockPos relic = memory.origin();
    BlockPos row = memory.origin();
    BlockPos symbol = memory.origin();
    BlockPos keeper = memory.origin();
    for (StructureTemplate.StructureBlockInfo info :
        shrine.filterBlocks(memory.origin(), settings, Blocks.STRUCTURE_BLOCK)) {
      String meta = info.nbt() == null ? "" : info.nbt().getStringOr("metadata", "");
      switch (meta) {
        case "niche" -> niches.add(info.pos());
        case "relic" -> relic = info.pos();
        case "glyph_row" -> row = info.pos();
        case "symbol" -> symbol = info.pos();
        case "keeper" -> keeper = info.pos();
        default -> {}
      }
    }

    niches.sort(
        Comparator.comparingInt((BlockPos p) -> -p.getY())
            .thenComparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getZ));
    return new Markers(
        shrine.getBoundingBox(settings, memory.origin()),
        List.copyOf(niches),
        relic,
        row,
        symbol,
        keeper,
        memory.rotation().rotate(Direction.NORTH));
  }

  private static void placeRelic(ServerLevel level, CitySite site, Markers markers) {
    BlockPos pos = markers.relic();
    switch (relic(level.getSeed(), site.id)) {
      case GRASS -> {
        level.setBlock(pos, Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(pos.above(), Blocks.SHORT_GRASS.defaultBlockState(), Block.UPDATE_CLIENTS);
      }
      case SCULK -> level.setBlock(pos, Blocks.SCULK.defaultBlockState(), Block.UPDATE_CLIENTS);
      case OLD_ITEM -> {}
    }
  }

  static void update(ServerLevel level, CitySite site, int remembered, long gameTime) {
    Markers markers = site.memory;
    if (markers == null) {
      return;
    }
    BlockState candle = Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.LIT, true);
    int filled = Math.min(remembered, markers.niches().size());
    for (int i = 0; i < filled; i++) {
      BlockPos niche = markers.niches().get(i);
      if (level.isLoaded(niche) && level.getBlockState(niche).isAir()) {
        level.setBlock(niche, candle, Block.UPDATE_CLIENTS);
      }
    }
    long seed = level.getSeed();
    AABB area = AABB.of(markers.box()).inflate(2);

    Display row = find(level, area, ROW_TAG);
    if (row == null) {
      spawnText(
          level,
          markers.glyphRow(),
          markers.front(),
          ROW_TAG,
          names(seed, site.id, remembered),
          0.9F,
          15);
    } else if (site.shownNames != remembered) {
      Displays.setText(row, names(seed, site.id, remembered));
    }
    site.shownNames = remembered;
    int light = 3 + (int) Math.round(12 * freshness(seed, site.id, gameTime));
    Display symbol = find(level, area, SYMBOL_TAG);
    if (symbol == null) {
      spawnText(
          level, markers.symbol(), markers.front(), SYMBOL_TAG, symbol(seed, site.id), 3.0F, light);
    } else if (site.shownLight != light) {
      Displays.setLight(symbol, light);
    }
    site.shownLight = light;
    if (relic(seed, site.id) == Relic.OLD_ITEM && find(level, area, RELIC_TAG) == null) {
      Vec3 at = markers.relic().getCenter();
      Displays.spawnItem(level, at, oldItem(level, seed, site.id), 0.6F, RELIC_TAG, false);
    }
  }

  static void forget(ServerLevel level, CitySite site) {
    for (Display display :
        level.getEntitiesOfClass(
            Display.class,
            site.aabb(),
            d ->
                d.entityTags().stream()
                    .anyMatch(t -> t.startsWith(GuestArchitects.MODID + ".memory")))) {
      display.discard();
    }
  }

  private static @Nullable Display find(ServerLevel level, AABB area, String tag) {
    List<Display> found =
        level.getEntitiesOfClass(Display.class, area, d -> d.entityTags().contains(tag));
    for (int i = 1; i < found.size(); i++) {
      found.get(i).discard();
    }
    return found.isEmpty() ? null : found.getFirst();
  }

  private static void spawnText(
      ServerLevel level,
      BlockPos marker,
      Direction front,
      String tag,
      Component text,
      float scale,
      int light) {

    Vec3 at = marker.getCenter().subtract(Vec3.atLowerCornerOf(front.getUnitVec3i()).scale(0.45));
    Displays.spawnText(level, at, front.toYRot(), text, scale, light, tag);
  }

  private static long period(long seed, long cityId) {
    return MIN_PERIOD
        + (long) (GuestHash.unit(GuestHash.hash(seed, cityId, SALT_KEEPER)) * PERIOD_SPREAD);
  }

  public static boolean visitDue(long seed, long cityId, long gameTime) {
    return Math.floorMod(gameTime + cityId, period(seed, cityId)) < VISIT_WINDOW;
  }

  static double freshness(long seed, long cityId, long gameTime) {
    long period = period(seed, cityId);
    return 1.0 - Math.floorMod(gameTime + cityId, period) / (double) period;
  }

  public static void renew(ServerLevel level, CitySite site, Vec3 keeperEyes) {
    Markers markers = site.memory;
    if (markers == null) {
      return;
    }
    CityManager.glyphs(level, keeperEyes, markers.symbol().getCenter(), 10);
    Vec3 symbol = markers.symbol().getCenter();
    level.sendParticles(ParticleTypes.GLOW, symbol.x, symbol.y, symbol.z, 6, 0.2, 0.2, 0.2, 0.0);
    if (relic(level.getSeed(), site.id) == Relic.GRASS) {
      Vec3 grass = markers.relic().above().getCenter();
      level.sendParticles(ParticleTypes.SPLASH, grass.x, grass.y, grass.z, 12, 0.2, 0.1, 0.2, 0.05);
    }
  }

  static Component names(long seed, long cityId, int remembered) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < Math.min(remembered, MAX_NAMES); i++) {
      long h = GuestHash.hash(seed, cityId, SALT_NAMES, i);
      int length = 2 + (int) Math.floorMod(h, 3L);
      for (int c = 0; c < length; c++) {
        text.append((char) ('a' + Math.floorMod(h >>> (8 + 5 * c), 26L)));
      }
      text.append(' ');
    }

    for (char digit : Integer.toString(remembered).toCharArray()) {
      text.append((char) ('a' + (digit - '0')));
    }
    return glyphs(text.toString());
  }

  static Component symbol(long seed, long cityId) {
    return glyphs(
        String.valueOf(
            (char) ('a' + Math.floorMod(GuestHash.hash(seed, cityId, SALT_NAMES), 26L))));
  }

  private static Component glyphs(String text) {
    return Component.literal(text).withStyle(Style.EMPTY.withFont(GLYPHS).withColor(GLYPH_COLOR));
  }

  private static ItemStack oldItem(ServerLevel level, long seed, long cityId) {
    Holder<Enchantment> unbreaking =
        level
            .registryAccess()
            .lookupOrThrow(Registries.ENCHANTMENT)
            .getOrThrow(Enchantments.UNBREAKING);
    ItemStack[] choices = {
      new ItemStack(Items.DIAMOND_CHESTPLATE),
      new ItemStack(Items.BRUSH),
      new ItemStack(Items.SPYGLASS),
      new ItemStack(Items.DISC_FRAGMENT_5)
    };
    ItemStack item =
        choices[
            (int)
                Math.floorMod(GuestHash.hash(seed, cityId, SALT_RELIC, 1), (long) choices.length)];
    if (item.isDamageableItem()) {
      item.enchant(unbreaking, 3);
      item.setDamageValue(item.getMaxDamage() * 3 / 4);
    }
    return item;
  }
}
