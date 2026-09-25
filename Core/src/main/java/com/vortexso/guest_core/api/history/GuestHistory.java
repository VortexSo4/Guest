package com.vortexso.guest_core.api.history;

import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Sparse per-dimension history. Only causes and deviations belong here, never derived results:
 * deleting this data changes the world, so it is state, not a cache.
 *
 * <p>Records are bucketed by 128-block regions so local queries stay local.
 */
public final class GuestHistory extends SavedData {
  private static final int REGION_SHIFT = 7;

  public static final Codec<GuestHistory> CODEC =
      HistoryRecord.CODEC.listOf().xmap(GuestHistory::new, GuestHistory::all);

  public static final SavedDataType<GuestHistory> TYPE =
      new SavedDataType<>(
          Identifier.fromNamespaceAndPath("guest_core", "history"), GuestHistory::new, CODEC);

  private final Long2ObjectOpenHashMap<List<HistoryRecord>> byRegion =
      new Long2ObjectOpenHashMap<>();

  private GuestHistory() {}

  private GuestHistory(List<HistoryRecord> records) {
    records.forEach(this::insert);
  }

  public static GuestHistory get(ServerLevel level) {
    return level.getDataStorage().computeIfAbsent(TYPE);
  }

  public void record(ServerLevel level, HistoryRecord record) {
    insert(record);
    setDirty();
    NeoForge.EVENT_BUS.post(new HistoryRecordedEvent(level, record));
  }

  /** Records within {@code radius} blocks (horizontal) of {@code center} matching the filter. */
  public List<HistoryRecord> near(BlockPos center, int radius, Predicate<HistoryRecord> filter) {
    List<HistoryRecord> result = new ArrayList<>();
    long radiusSqr = (long) radius * radius;
    int minX = (center.getX() - radius) >> REGION_SHIFT;
    int maxX = (center.getX() + radius) >> REGION_SHIFT;
    int minZ = (center.getZ() - radius) >> REGION_SHIFT;
    int maxZ = (center.getZ() + radius) >> REGION_SHIFT;

    for (int x = minX; x <= maxX; x++) {
      for (int z = minZ; z <= maxZ; z++) {
        List<HistoryRecord> bucket = byRegion.get(regionKey(x, z));
        if (bucket == null) {
          continue;
        }
        for (HistoryRecord record : bucket) {
          long dx = record.pos().getX() - center.getX();
          long dz = record.pos().getZ() - center.getZ();
          if (dx * dx + dz * dz <= radiusSqr && filter.test(record)) {
            result.add(record);
          }
        }
      }
    }
    return result;
  }

  public List<HistoryRecord> near(BlockPos center, int radius, Identifier kind) {
    return near(center, radius, record -> record.kind().equals(kind));
  }

  /** Forgetting is how memory fades; owners decide when their kinds expire. */
  public int forget(Predicate<HistoryRecord> filter) {
    int removed = 0;
    var iterator = byRegion.values().iterator();
    while (iterator.hasNext()) {
      List<HistoryRecord> bucket = iterator.next();
      int before = bucket.size();
      bucket.removeIf(filter);
      removed += before - bucket.size();
      if (bucket.isEmpty()) {
        iterator.remove();
      }
    }
    if (removed > 0) {
      setDirty();
    }
    return removed;
  }

  public int size() {
    int size = 0;
    for (List<HistoryRecord> bucket : byRegion.values()) {
      size += bucket.size();
    }
    return size;
  }

  private List<HistoryRecord> all() {
    List<HistoryRecord> all = new ArrayList<>();
    byRegion.values().forEach(all::addAll);
    return all;
  }

  private void insert(HistoryRecord record) {
    long key = regionKey(record.pos().getX() >> REGION_SHIFT, record.pos().getZ() >> REGION_SHIFT);
    byRegion.computeIfAbsent(key, ignored -> new ArrayList<>()).add(record);
  }

  private static long regionKey(int x, int z) {
    return ((long) x << 32) | (z & 0xFFFFFFFFL);
  }
}
