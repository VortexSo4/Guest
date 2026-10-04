package com.vortexso.guest_wilds.fabric;

import com.vortexso.guest_wilds.WildsEvents;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ServerLevelAccessor;

public final class SpawnHooks {
  private static final Set<Entity> CANCELLED =
      Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

  private SpawnHooks() {}

  public static boolean finalizeSpawn(
      Mob mob, ServerLevelAccessor level, EntitySpawnReason reason) {
    return switch (WildsEvents.onFinalizeSpawn(mob, level.getLevel(), reason)) {
      case KEEP -> false;
      case SKIP_FINALIZE -> true;
      case CANCEL -> {
        CANCELLED.add(mob);
        yield false;
      }
    };
  }

  static boolean allowLoad(Entity entity) {
    return !CANCELLED.remove(entity);
  }
}
