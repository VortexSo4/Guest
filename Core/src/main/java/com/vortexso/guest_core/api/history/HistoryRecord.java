package com.vortexso.guest_core.api.history;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;

/**
 * One meaningful deviation from the deterministic baseline: something that happened and can change
 * future outcomes. The meaning of {@code kind} belongs to the addon that recorded it.
 *
 * @param actor who caused it (usually a player), if anyone
 * @param subject who/what it happened to (a villager, a city), if relevant
 * @param weight how strong the event was; consumers decide the scale for their own kinds
 */
public record HistoryRecord(
    long gameTime,
    BlockPos pos,
    Identifier kind,
    Optional<UUID> actor,
    Optional<UUID> subject,
    int weight) {
  public static final Codec<HistoryRecord> CODEC =
      RecordCodecBuilder.create(
          i ->
              i.group(
                      Codec.LONG.fieldOf("time").forGetter(HistoryRecord::gameTime),
                      BlockPos.CODEC.fieldOf("pos").forGetter(HistoryRecord::pos),
                      Identifier.CODEC.fieldOf("kind").forGetter(HistoryRecord::kind),
                      UUIDUtil.CODEC.optionalFieldOf("actor").forGetter(HistoryRecord::actor),
                      UUIDUtil.CODEC.optionalFieldOf("subject").forGetter(HistoryRecord::subject),
                      Codec.INT.optionalFieldOf("weight", 1).forGetter(HistoryRecord::weight))
                  .apply(i, HistoryRecord::new));
}
