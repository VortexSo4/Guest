package com.vortexso.guest_architects.city;

import com.mojang.datafixers.util.Pair;
import com.vortexso.guest_architects.GuestArchitects;
import com.vortexso.guest_core.api.GuestHash;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.AlwaysTrueTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.ProcessorRule;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jspecify.annotations.Nullable;

public final class LivingCities {
  public static final ResourceKey<Structure> LIVING_CITY =
      ResourceKey.create(
          Registries.STRUCTURE,
          Identifier.fromNamespaceAndPath(GuestArchitects.MODID, "living_city"));
  public static final ResourceKey<Biome> SILENT_DEEP =
      ResourceKey.create(
          Registries.BIOME, Identifier.fromNamespaceAndPath(GuestArchitects.MODID, "silent_deep"));

  private static final int PIECES_PER_SECOND = 3;

  private static final RuleProcessor TO_AIR =
      new RuleProcessor(
          List.of(
              new ProcessorRule(
                  AlwaysTrueTest.INSTANCE,
                  AlwaysTrueTest.INSTANCE,
                  Blocks.AIR.defaultBlockState())));

  private static volatile @Nullable Structure livingCity;
  private static volatile @Nullable Holder<Biome> silentDeep;
  private static volatile @Nullable Map<String, StructurePoolElement> ruins;

  private LivingCities() {}

  public static void bind(RegistryAccess registries) {
    livingCity = registries.lookupOrThrow(Registries.STRUCTURE).getValue(LIVING_CITY);
    silentDeep = registries.lookupOrThrow(Registries.BIOME).get(SILENT_DEEP).orElse(null);
    ruins = null;
  }

  public static BiomeResolver quiet(ChunkAccess chunk, BiomeResolver resolver) {
    Structure structure = livingCity;
    Holder<Biome> quiet = silentDeep;
    if (structure == null
        || quiet == null
        || chunk.getReferencesForStructure(structure).isEmpty()) {
      return resolver;
    }
    return (x, y, z, sampler) -> {
      Holder<Biome> biome = resolver.getNoiseBiome(x, y, z, sampler);
      return biome.is(Biomes.DEEP_DARK) ? quiet : biome;
    };
  }

  static void convert(ServerLevel level, CitySite site) {
    if (site.conversionDone) {
      return;
    }
    int budget = PIECES_PER_SECOND;
    boolean pending = false;
    for (CitySite.Piece piece : site.pieces) {
      if (site.record.converted.contains(piece.index())) {
        continue;
      }
      if (budget == 0 || !loaded(level, piece.box())) {
        pending = true;
        continue;
      }
      swap(level, site, piece);
      site.record.converted.add(piece.index());
      ArchitectsData.get(level).setDirty();
      budget--;
    }
    boolean biomesPending = restoreBiomes(level, site.box);
    site.piecesDone = !pending;
    site.conversionDone = !pending && !biomesPending;
  }

  public static int convertedCount(CitySite site) {
    return site.record.converted.size();
  }

  private static boolean loaded(ServerLevel level, BoundingBox box) {
    for (int cx = SectionPos.blockToSectionCoord(box.minX());
        cx <= SectionPos.blockToSectionCoord(box.maxX());
        cx++) {
      for (int cz = SectionPos.blockToSectionCoord(box.minZ());
          cz <= SectionPos.blockToSectionCoord(box.maxZ());
          cz++) {
        if (level.getChunkSource().getChunkNow(cx, cz) == null) {
          return false;
        }
      }
    }
    return true;
  }

  private static void swap(ServerLevel level, CitySite site, CitySite.Piece piece) {
    StructurePoolElement ruin = ruinOf(level, piece.templates());
    if (ruin == null) {

      return;
    }
    PoolElementStructurePiece structurePiece = piece.piece();
    BoundingBox box = piece.box();
    List<Kept> kept = openedContainers(level, box);
    StructureTemplateManager templates = level.getStructureManager();
    StructurePlaceSettings clear =
        new StructurePlaceSettings()
            .setRotation(structurePiece.getRotation())
            .setBoundingBox(box)
            .setKnownShape(true)
            .addProcessor(BlockIgnoreProcessor.STRUCTURE_BLOCK)
            .addProcessor(TO_AIR);
    for (String template : piece.templates()) {
      templates
          .getOrCreate(Identifier.parse(template))
          .placeInWorld(
              level,
              structurePiece.getPosition(),
              site.referencePos,
              clear,
              level.getRandom(),
              Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }
    ruin.place(
        templates,
        level,
        level.structureManager(),
        level.getChunkSource().getGenerator(),
        structurePiece.getPosition(),
        site.referencePos,
        structurePiece.getRotation(),
        box,
        RandomSource.create(GuestHash.hash(level.getSeed(), site.id, piece.index())),
        LiquidSettings.APPLY_WATERLOGGING,
        false);
    for (Kept k : kept) {
      if (level.getBlockEntity(k.pos()) instanceof RandomizableContainerBlockEntity container) {
        container.setLootTable(null);
        for (int i = 0; i < container.getContainerSize(); i++) {
          container.setItem(i, i < k.items().size() ? k.items().get(i) : ItemStack.EMPTY);
        }
      }
    }
  }

  private record Kept(BlockPos pos, List<ItemStack> items) {}

  private static List<Kept> openedContainers(ServerLevel level, BoundingBox box) {
    List<Kept> kept = new ArrayList<>();
    for (int cx = SectionPos.blockToSectionCoord(box.minX());
        cx <= SectionPos.blockToSectionCoord(box.maxX());
        cx++) {
      for (int cz = SectionPos.blockToSectionCoord(box.minZ());
          cz <= SectionPos.blockToSectionCoord(box.maxZ());
          cz++) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) {
          continue;
        }
        for (BlockEntity entity : chunk.getBlockEntities().values()) {
          if (entity instanceof RandomizableContainerBlockEntity container
              && container.getLootTable() == null
              && box.isInside(entity.getBlockPos())) {
            List<ItemStack> items = new ArrayList<>();
            for (int i = 0; i < container.getContainerSize(); i++) {
              items.add(container.getItem(i).copy());
            }
            kept.add(new Kept(entity.getBlockPos(), items));
          }
        }
      }
    }
    return kept;
  }

  private static @Nullable StructurePoolElement ruinOf(ServerLevel level, List<String> templates) {
    Map<String, StructurePoolElement> map = ruins;
    if (map == null) {
      map = new HashMap<>();
      for (Holder.Reference<StructureTemplatePool> pool :
          level.registryAccess().lookupOrThrow(Registries.TEMPLATE_POOL).listElements().toList()) {
        Identifier id = pool.key().identifier();
        if (!id.getNamespace().equals(GuestArchitects.MODID)
            || !id.getPath().startsWith("ancient_city")) {
          continue;
        }
        for (Pair<StructurePoolElement, Integer> entry : pool.value().getTemplates()) {
          List<String> paths = CitySite.templates(entry.getFirst());
          if (!paths.isEmpty()) {
            map.putIfAbsent(key(paths), entry.getFirst());
          }
        }
      }
      ruins = map;
    }
    return map.get(key(templates));
  }

  private static String key(List<String> templates) {
    return templates.stream()
        .map(t -> t.substring(t.indexOf(':') + 1))
        .collect(Collectors.joining("|"));
  }

  private static boolean restoreBiomes(ServerLevel level, BoundingBox box) {
    Holder<Biome> quiet = silentDeep;
    if (quiet == null) {
      return false;
    }
    Holder<Biome> deepDark =
        level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.DEEP_DARK);
    List<ChunkAccess> changed = new ArrayList<>();
    boolean unloaded = false;
    for (int cx = SectionPos.blockToSectionCoord(box.minX());
        cx <= SectionPos.blockToSectionCoord(box.maxX());
        cx++) {
      for (int cz = SectionPos.blockToSectionCoord(box.minZ());
          cz <= SectionPos.blockToSectionCoord(box.maxZ());
          cz++) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) {
          unloaded = true;
          continue;
        }
        if (!contains(chunk)) {
          continue;
        }
        chunk.fillBiomesFromNoise(
            (x, y, z, sampler) -> {
              Holder<Biome> current = chunk.getNoiseBiome(x, y, z);
              return current.is(SILENT_DEEP) ? deepDark : current;
            },
            level.getChunkSource().randomState().sampler());
        chunk.markUnsaved();
        changed.add(chunk);
      }
    }
    if (!changed.isEmpty()) {
      level.getChunkSource().chunkMap.resendBiomesForChunks(changed);
    }
    return unloaded;
  }

  private static boolean contains(LevelChunk chunk) {
    for (LevelChunkSection section : chunk.getSections()) {
      if (section.getBiomes().maybeHas(b -> b.is(SILENT_DEEP))) {
        return true;
      }
    }
    return false;
  }
}
