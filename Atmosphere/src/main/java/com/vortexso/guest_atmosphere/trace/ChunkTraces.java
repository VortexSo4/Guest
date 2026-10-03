package com.vortexso.guest_atmosphere.trace;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.vortexso.guest_atmosphere.GuestAtmosphere;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

public final class ChunkTraces {
  public static final long NEVER = Long.MIN_VALUE;

  private static final long INHABITED_RECHECK = 1_200L;

  public static final MapCodec<ChunkTraces> CODEC =
      RecordCodecBuilder.mapCodec(
          instance ->
              instance
                  .group(
                      Codec.LONG
                          .optionalFieldOf("last_update", NEVER)
                          .forGetter(traces -> traces.lastUpdate),
                      Entry.CODEC
                          .listOf()
                          .optionalFieldOf("traces", List.of())
                          .forGetter(ChunkTraces::entries),
                      Codec.LONG
                          .optionalFieldOf("mineshaft_aged", NEVER)
                          .forGetter(traces -> traces.mineshaftAged),
                      Codec.LONG
                          .listOf()
                          .optionalFieldOf("fixed", List.of())
                          .forGetter(traces -> List.copyOf(traces.fixed)))
                  .apply(instance, ChunkTraces::new));

  private long lastUpdate;

  private long mineshaftAged;

  private final Long2ObjectOpenHashMap<Trace> traces = new Long2ObjectOpenHashMap<>();

  private final LongOpenHashSet fixed = new LongOpenHashSet();

  private boolean inhabited;

  private long inhabitedUntil;

  public ChunkTraces() {
    this(NEVER, List.of(), NEVER, List.of());
  }

  private ChunkTraces(
      long lastUpdate, List<Entry> entries, long mineshaftAged, List<Long> fixedPositions) {
    this.lastUpdate = lastUpdate;
    this.mineshaftAged = mineshaftAged;
    fixed.addAll(fixedPositions);
    for (Entry entry : entries) {
      traces.put(entry.pos(), new Trace(entry.kind(), entry.original(), entry.placedAt()));
    }
  }

  public boolean shouldSave() {
    return lastUpdate != NEVER || !traces.isEmpty() || !fixed.isEmpty();
  }

  public static boolean isFixed(Level level, BlockPos pos) {
    LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
    if (chunk == null) {
      return false;
    }
    ChunkTraces traces = chunk.getExistingDataOrNull(GuestAtmosphere.CHUNK_TRACES);
    return traces != null && traces.fixed.contains(pos.asLong());
  }

  boolean inhabited(long gameTime, BooleanSupplier check) {
    if (gameTime < inhabitedUntil - INHABITED_RECHECK || gameTime >= inhabitedUntil) {
      inhabited = check.getAsBoolean();
      inhabitedUntil = gameTime + INHABITED_RECHECK;
    }
    return inhabited;
  }

  public static void setFixed(Level level, BlockPos pos, boolean value) {
    LevelChunk chunk = level.getChunkAt(pos);
    ChunkTraces traces =
        value
            ? chunk.getData(GuestAtmosphere.CHUNK_TRACES)
            : chunk.getExistingDataOrNull(GuestAtmosphere.CHUNK_TRACES);
    if (traces != null
        && (value ? traces.fixed.add(pos.asLong()) : traces.fixed.remove(pos.asLong()))) {
      chunk.markUnsaved();
    }
  }

  public long lastUpdate() {
    return lastUpdate;
  }

  public void setLastUpdate(long lastUpdate) {
    this.lastUpdate = lastUpdate;
  }

  public long mineshaftAged() {
    return mineshaftAged;
  }

  public void setMineshaftAged(long mineshaftAged) {
    this.mineshaftAged = mineshaftAged;
  }

  public Trace get(long pos) {
    return traces.get(pos);
  }

  public void put(long pos, Trace trace) {
    traces.put(pos, trace);
  }

  public void remove(long pos) {
    traces.remove(pos);
  }

  public int size() {
    return traces.size();
  }

  public Long2ObjectMap<Trace> view() {
    return traces;
  }

  private List<Entry> entries() {
    List<Entry> entries = new ArrayList<>(traces.size());
    for (Long2ObjectMap.Entry<Trace> entry : traces.long2ObjectEntrySet()) {
      Trace trace = entry.getValue();
      entries.add(new Entry(entry.getLongKey(), trace.kind(), trace.original(), trace.placedAt()));
    }
    return entries;
  }

  public record Trace(Kind kind, BlockState original, long placedAt) {}

  private record Entry(long pos, Kind kind, BlockState original, long placedAt) {
    static final Codec<Entry> CODEC =
        RecordCodecBuilder.create(
            instance ->
                instance
                    .group(
                        Codec.LONG.fieldOf("pos").forGetter(Entry::pos),
                        Kind.CODEC.fieldOf("kind").forGetter(Entry::kind),
                        BlockState.CODEC.fieldOf("original").forGetter(Entry::original),
                        Codec.LONG.fieldOf("placed").forGetter(Entry::placedAt))
                    .apply(instance, Entry::new));
  }

  public enum Kind implements StringRepresentable {
    SNOW(0xFFFFFFFF),
    MUD(0xFF6B4A2B),
    ICE(0xFF88BBFF),
    SAND(0xFFE0C888),
    DRY(0xFFB08850),
    SCORCH(0xFF303030),
    SILT(0xFF8A8474),
    BURN(0xFFFF6A00);

    public static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);

    private final int debugColor;

    Kind(int debugColor) {
      this.debugColor = debugColor;
    }

    public int debugColor() {
      return debugColor;
    }

    public Component displayName() {
      return Component.translatable("guest_atmosphere.trace." + getSerializedName());
    }

    @Override
    public String getSerializedName() {
      return name().toLowerCase(Locale.ROOT);
    }
  }
}
