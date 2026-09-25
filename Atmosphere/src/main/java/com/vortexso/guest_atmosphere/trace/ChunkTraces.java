package com.vortexso.guest_atmosphere.trace;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Sparse per-chunk record of blocks Atmosphere changed, with the state they had before, so that
 * every trace can revert to exactly what was there. Stored as a chunk attachment: it loads and
 * saves with its chunk and costs nothing for chunks without traces.
 */
public final class ChunkTraces {
  public static final long NEVER = Long.MIN_VALUE;

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
                          .forGetter(ChunkTraces::entries))
                  .apply(instance, ChunkTraces::new));

  /** Game time (Guest clock) this chunk's traces were last brought up to date. */
  private long lastUpdate;

  private final Long2ObjectOpenHashMap<Trace> traces = new Long2ObjectOpenHashMap<>();

  public ChunkTraces() {
    this(NEVER, List.of());
  }

  private ChunkTraces(long lastUpdate, List<Entry> entries) {
    this.lastUpdate = lastUpdate;
    for (Entry entry : entries) {
      traces.put(entry.pos(), new Trace(entry.kind(), entry.original(), entry.placedAt()));
    }
  }

  public boolean shouldSave() {
    return lastUpdate != NEVER || !traces.isEmpty();
  }

  public long lastUpdate() {
    return lastUpdate;
  }

  public void setLastUpdate(long lastUpdate) {
    this.lastUpdate = lastUpdate;
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

  /**
   * @param original state before Atmosphere touched the block (air under placed snow, water under
   *     ice, or the vanilla snow layer count that a storm deepened)
   */
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
    SCORCH(0xFF303030);

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
