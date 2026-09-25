package com.vortexso.guest_architects.city;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Everything about a city that cannot be derived from {@code seed + id + time}: debug overrides,
 * what players did, and how far the world has already been changed. Population, state and deaths
 * are never stored; {@link CityLife} computes them.
 */
public final class CityRecord {
  /** Damage waiting for repair is bounded; older damage is simply accepted as the city's state. */
  private static final int MAX_DAMAGE = 64;

  public static final Codec<CityRecord> CODEC =
      RecordCodecBuilder.create(
          i ->
              i.group(
                      Codec.LONG.fieldOf("id").forGetter(r -> r.id),
                      BlockPos.CODEC.fieldOf("center").forGetter(r -> r.center),
                      Codec.BOOL.optionalFieldOf("living").forGetter(r -> r.livingOverride),
                      ExtraCodecs.legacyEnum(CityLife.State::valueOf)
                          .optionalFieldOf("state")
                          .forGetter(r -> r.stateOverride),
                      Codec.DOUBLE.optionalFieldOf("delay", 0.0).forGetter(r -> r.delayDays),
                      Codec.DOUBLE
                          .optionalFieldOf("last_grant", 0.0)
                          .forGetter(r -> r.lastGrantDay),
                      Codec.BOOL
                          .optionalFieldOf("recognized", false)
                          .forGetter(r -> r.recognitionUsed),
                      Codec.unboundedMap(UUIDUtil.STRING_CODEC, Relation.CODEC)
                          .optionalFieldOf("relations", Map.of())
                          .forGetter(r -> r.relations),
                      Damage.CODEC
                          .listOf()
                          .optionalFieldOf("damage", List.of())
                          .forGetter(r -> r.damage),
                      Codec.INT.optionalFieldOf("spread", 0).forGetter(r -> r.spreadApplied),
                      Codec.INT.optionalFieldOf("niches", 0).forGetter(r -> r.nichesPlaced))
                  .apply(i, CityRecord::new));

  public final long id;
  public final BlockPos center;
  public Optional<Boolean> livingOverride;
  public Optional<CityLife.State> stateOverride;
  public double delayDays;
  public double lastGrantDay;
  public boolean recognitionUsed;
  public final Map<UUID, Relation> relations;
  public final List<Damage> damage;
  public int spreadApplied;
  public int nichesPlaced;

  private CityRecord(
      long id,
      BlockPos center,
      Optional<Boolean> livingOverride,
      Optional<CityLife.State> stateOverride,
      double delayDays,
      double lastGrantDay,
      boolean recognitionUsed,
      Map<UUID, Relation> relations,
      List<Damage> damage,
      int spreadApplied,
      int nichesPlaced) {
    this.id = id;
    this.center = center;
    this.livingOverride = livingOverride;
    this.stateOverride = stateOverride;
    this.delayDays = delayDays;
    this.lastGrantDay = lastGrantDay;
    this.recognitionUsed = recognitionUsed;
    this.relations = new HashMap<>(relations);
    this.damage = new ArrayList<>(damage);
    this.spreadApplied = spreadApplied;
    this.nichesPlaced = nichesPlaced;
  }

  public CityRecord(long id, BlockPos center) {
    this(id, center, Optional.empty(), Optional.empty(), 0, 0, false, Map.of(), List.of(), 0, 0);
  }

  public Relation relation(UUID player) {
    return relations.getOrDefault(player, Relation.NONE);
  }

  public void addDamage(BlockPos pos, BlockState state) {
    if (damage.size() >= MAX_DAMAGE) {
      damage.removeFirst();
    }
    damage.add(new Damage(pos.immutable(), state));
  }

  /**
   * Interference one player caused and the help they gave. Points fade with time; help makes them
   * fade faster, which is the Architects' individual tolerance.
   */
  public record Relation(double points, long lastTime, double assistance) {
    public static final Relation NONE = new Relation(0, 0, 0);

    public static final Codec<Relation> CODEC =
        RecordCodecBuilder.create(
            i ->
                i.group(
                        Codec.DOUBLE.fieldOf("points").forGetter(Relation::points),
                        Codec.LONG.fieldOf("time").forGetter(Relation::lastTime),
                        Codec.DOUBLE.optionalFieldOf("help", 0.0).forGetter(Relation::assistance))
                    .apply(i, Relation::new));

    public double pointsAt(long gameTime, double decayPerDay) {
      double days = CityLife.day(gameTime - lastTime);
      return Math.max(0.0, points - days * decayPerDay * (1.0 + assistance));
    }
  }

  /** A city-critical block the player destroyed, restored by the Architect whose task covers it. */
  public record Damage(BlockPos pos, BlockState state) {
    public static final Codec<Damage> CODEC =
        RecordCodecBuilder.create(
            i ->
                i.group(
                        BlockPos.CODEC.fieldOf("pos").forGetter(Damage::pos),
                        BlockState.CODEC.fieldOf("state").forGetter(Damage::state))
                    .apply(i, Damage::new));
  }
}
