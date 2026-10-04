package com.vortexso.guest_settlements.society;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.vortexso.guest_settlements.GuestSettlements;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

public final class SocietyData extends SavedData {
  public enum CampKind implements StringRepresentable {
    OUTPOST,
    MANSION;

    public static final Codec<CampKind> CODEC = StringRepresentable.fromEnum(CampKind::values);

    @Override
    public String getSerializedName() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  public record Camp(
      long id,
      CampKind kind,
      BlockPos center,
      BoundingBox box,
      int evokers,
      int ravagers,
      int pending,
      int crew,
      long day,
      Optional<BlockPos> pen) {
    static final Codec<Camp> CODEC =
        RecordCodecBuilder.create(
            i ->
                i.group(
                        Codec.LONG.fieldOf("id").forGetter(Camp::id),
                        CampKind.CODEC.fieldOf("kind").forGetter(Camp::kind),
                        BlockPos.CODEC.fieldOf("center").forGetter(Camp::center),
                        BoundingBox.CODEC.fieldOf("box").forGetter(Camp::box),
                        Codec.INT.fieldOf("evokers").forGetter(Camp::evokers),
                        Codec.INT.fieldOf("ravagers").forGetter(Camp::ravagers),
                        Codec.INT.fieldOf("pending").forGetter(Camp::pending),
                        Codec.INT.fieldOf("crew").forGetter(Camp::crew),
                        Codec.LONG.fieldOf("day").forGetter(Camp::day),
                        BlockPos.CODEC.optionalFieldOf("pen").forGetter(Camp::pen))
                    .apply(i, Camp::new));

    public Camp withRavagers(int ravagers, int pending) {
      return new Camp(id, kind, center, box, evokers, ravagers, pending, crew, day, pen);
    }

    public Camp withEvokers(int evokers) {
      return new Camp(id, kind, center, box, evokers, ravagers, pending, crew, day, pen);
    }

    public Camp withCrew(int crew) {
      return new Camp(id, kind, center, box, evokers, ravagers, pending, crew, day, pen);
    }

    public Camp withDay(long day, int pending) {
      return new Camp(id, kind, center, box, evokers, ravagers, pending, crew, day, pen);
    }

    public Camp withPen(BlockPos pen) {
      return new Camp(
          id, kind, center, box, evokers, ravagers, pending, crew, day, Optional.of(pen));
    }
  }

  public record TracedBlock(BlockPos pos, BlockState placed, BlockState original) {
    static final Codec<TracedBlock> CODEC =
        RecordCodecBuilder.create(
            i ->
                i.group(
                        BlockPos.CODEC.fieldOf("pos").forGetter(TracedBlock::pos),
                        BlockState.CODEC.fieldOf("placed").forGetter(TracedBlock::placed),
                        BlockState.CODEC.fieldOf("original").forGetter(TracedBlock::original))
                    .apply(i, TracedBlock::new));
  }

  public record Trace(long id, String kind, long time, long lifetime, List<TracedBlock> blocks) {
    static final Codec<Trace> CODEC =
        RecordCodecBuilder.create(
            i ->
                i.group(
                        Codec.LONG.fieldOf("id").forGetter(Trace::id),
                        Codec.STRING.fieldOf("kind").forGetter(Trace::kind),
                        Codec.LONG.fieldOf("time").forGetter(Trace::time),
                        Codec.LONG.fieldOf("lifetime").forGetter(Trace::lifetime),
                        TracedBlock.CODEC.listOf().fieldOf("blocks").forGetter(Trace::blocks))
                    .apply(i, Trace::new));
  }

  private record Immigrants(long village, int count) {
    static final Codec<Immigrants> CODEC =
        RecordCodecBuilder.create(
            i ->
                i.group(
                        Codec.LONG.fieldOf("village").forGetter(Immigrants::village),
                        Codec.INT.fieldOf("count").forGetter(Immigrants::count))
                    .apply(i, Immigrants::new));
  }

  public static final Codec<SocietyData> CODEC =
      RecordCodecBuilder.create(
          i ->
              i.group(
                      Camp.CODEC
                          .listOf()
                          .optionalFieldOf("camps", List.of())
                          .forGetter(data -> List.copyOf(data.camps.values())),
                      Trace.CODEC
                          .listOf()
                          .optionalFieldOf("traces", List.of())
                          .forGetter(data -> List.copyOf(data.traces.values())),
                      Immigrants.CODEC
                          .listOf()
                          .optionalFieldOf("immigrants", List.of())
                          .forGetter(SocietyData::immigrantList))
                  .apply(i, SocietyData::new));

  public static final SavedDataType<SocietyData> TYPE =
      new SavedDataType<>(GuestSettlements.id("society"), SocietyData::new, CODEC, null);

  public final Map<Long, Camp> camps = new HashMap<>();
  public final Map<Long, Trace> traces = new HashMap<>();
  private final Map<Long, Integer> immigrants = new HashMap<>();

  private SocietyData() {}

  private SocietyData(List<Camp> camps, List<Trace> traces, List<Immigrants> immigrants) {
    camps.forEach(camp -> this.camps.put(camp.id(), camp));
    traces.forEach(trace -> this.traces.put(trace.id(), trace));
    immigrants.forEach(entry -> this.immigrants.put(entry.village(), entry.count()));
  }

  public static SocietyData get(ServerLevel level) {
    return level.getDataStorage().computeIfAbsent(TYPE);
  }

  public void putCamp(Camp camp) {
    camps.put(camp.id(), camp);
    setDirty();
  }

  public void putTrace(Trace trace) {
    traces.put(trace.id(), trace);
    setDirty();
  }

  public void removeTrace(long id) {
    if (traces.remove(id) != null) {
      setDirty();
    }
  }

  public void addImmigrants(long village, int count) {
    immigrants.merge(village, count, Integer::sum);
    setDirty();
  }

  public int takeImmigrants(long village) {
    Integer count = immigrants.remove(village);
    if (count != null) {
      setDirty();
    }
    return count == null ? 0 : count;
  }

  public void takeImmigrant(long village) {
    Integer count = immigrants.get(village);
    if (count != null) {
      if (count <= 1) {
        immigrants.remove(village);
      } else {
        immigrants.put(village, count - 1);
      }
      setDirty();
    }
  }

  public int immigrants(long village) {
    return immigrants.getOrDefault(village, 0);
  }

  private List<Immigrants> immigrantList() {
    List<Immigrants> result = new ArrayList<>();
    immigrants.forEach((village, count) -> result.add(new Immigrants(village, count)));
    return result;
  }
}
