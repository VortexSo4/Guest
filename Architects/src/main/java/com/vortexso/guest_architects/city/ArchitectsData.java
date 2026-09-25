package com.vortexso.guest_architects.city;

import com.mojang.serialization.Codec;
import com.vortexso.guest_architects.GuestArchitects;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Per-dimension sparse city history. Callers must {@link #setDirty()} after changing a record. */
public final class ArchitectsData extends SavedData {
  public static final Codec<ArchitectsData> CODEC =
      CityRecord.CODEC
          .listOf()
          .xmap(ArchitectsData::new, data -> List.copyOf(data.cities.values()));

  public static final SavedDataType<ArchitectsData> TYPE =
      new SavedDataType<>(
          Identifier.fromNamespaceAndPath(GuestArchitects.MODID, "cities"),
          ArchitectsData::new,
          CODEC);

  private final Map<Long, CityRecord> cities = new TreeMap<>();

  private ArchitectsData() {}

  private ArchitectsData(List<CityRecord> records) {
    records.forEach(record -> cities.put(record.id, record));
  }

  public static ArchitectsData get(ServerLevel level) {
    return level.getDataStorage().computeIfAbsent(TYPE);
  }

  public CityRecord city(long id, BlockPos center) {
    return cities.computeIfAbsent(
        id,
        key -> {
          setDirty();
          return new CityRecord(key, center);
        });
  }
}
