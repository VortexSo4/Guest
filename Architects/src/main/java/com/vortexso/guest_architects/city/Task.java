package com.vortexso.guest_architects.city;

import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

public record Task(Kind kind, BlockPos pos, CityRecord.@Nullable Damage damage) {
  public enum Kind {
    TEND(160),

    CONTAIN(120),

    REPAIR(100),

    WATCH(400),

    FOLLOW(60),

    VISIT(240);

    private final int workTicks;

    Kind(int workTicks) {
      this.workTicks = workTicks;
    }

    public int workTicks() {
      return workTicks;
    }
  }

  public Task(Kind kind, BlockPos pos) {
    this(kind, pos, null);
  }
}
