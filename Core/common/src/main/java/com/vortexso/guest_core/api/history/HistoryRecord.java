package com.vortexso.guest_core.api.history;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;

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
