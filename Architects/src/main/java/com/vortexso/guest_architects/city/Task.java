package com.vortexso.guest_architects.city;

import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/** One unit of visible Architect work: walk to {@code pos}, work for a while, then rest. */
public record Task(Kind kind, BlockPos pos, CityRecord.@Nullable Damage damage) {
  public enum Kind {
    /** Routine care of the place: the ritual, the portal frame, the floor. */
    TEND(160),
    /** Sculk found outside the ritual area; the maintainer scrapes it back. */
    CONTAIN(120),
    /** A city-critical block someone destroyed; restored to the recorded state. */
    REPAIR(100),
    /** Standing watch over a part of the city. */
    WATCH(400),
    /** Young Architect staying beside its teacher. */
    FOLLOW(60);

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
