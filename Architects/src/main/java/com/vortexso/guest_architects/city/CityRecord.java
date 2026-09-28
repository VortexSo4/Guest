package com.vortexso.guest_architects.city;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

public final class CityRecord {

  private static final int MAX_DAMAGE = 64;

  public static final Codec<CityRecord> CODEC =
      RecordCodecBuilder.create(
          i ->
              i.group(
                      Codec.LONG.fieldOf("id").forGetter(r -> r.id),
                      BlockPos.CODEC.fieldOf("center").forGetter(r -> r.center),
                      Codec.DOUBLE.optionalFieldOf("extinct_day").forGetter(r -> r.extinctDay),
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
                      Codec.INT
                          .listOf()
                          .optionalFieldOf("converted", List.of())
                          .forGetter(r -> List.copyOf(r.converted)),
                      Memory.CODEC.optionalFieldOf("memory").forGetter(r -> r.memory))
                  .apply(i, CityRecord::new));

  public final long id;
  public final BlockPos center;

  public Optional<Double> extinctDay;

  public Optional<CityLife.State> stateOverride;
  public double delayDays;
  public double lastGrantDay;
  public boolean recognitionUsed;
  public final Map<UUID, Relation> relations;
  public final List<Damage> damage;
  public int spreadApplied;

  public final TreeSet<Integer> converted;

  public Optional<Memory> memory;

  private CityRecord(
      long id,
      BlockPos center,
      Optional<Double> extinctDay,
      Optional<CityLife.State> stateOverride,
      double delayDays,
      double lastGrantDay,
      boolean recognitionUsed,
      Map<UUID, Relation> relations,
      List<Damage> damage,
      int spreadApplied,
      List<Integer> converted,
      Optional<Memory> memory) {
    this.id = id;
    this.center = center;
    this.extinctDay = extinctDay;
    this.stateOverride = stateOverride;
    this.delayDays = delayDays;
    this.lastGrantDay = lastGrantDay;
    this.recognitionUsed = recognitionUsed;
    this.relations = new HashMap<>(relations);
    this.damage = new ArrayList<>(damage);
    this.spreadApplied = spreadApplied;
    this.converted = new TreeSet<>(converted);
    this.memory = memory;
  }

  public CityRecord(long id, BlockPos center) {
    this(
        id,
        center,
        Optional.empty(),
        Optional.empty(),
        0,
        0,
        false,
        Map.of(),
        List.of(),
        0,
        List.of(),
        Optional.empty());
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

  public record Relation(double points, long lastTime, double assistance, boolean removed) {
    public static final Relation NONE = new Relation(0, 0, 0, false);

    public static final Codec<Relation> CODEC =
        RecordCodecBuilder.create(
            i ->
                i.group(
                        Codec.DOUBLE.fieldOf("points").forGetter(Relation::points),
                        Codec.LONG.fieldOf("time").forGetter(Relation::lastTime),
                        Codec.DOUBLE.optionalFieldOf("help", 0.0).forGetter(Relation::assistance),
                        Codec.BOOL.optionalFieldOf("removed", false).forGetter(Relation::removed))
                    .apply(i, Relation::new));

    public double pointsAt(long gameTime, double decayPerDay) {
      double days = CityLife.day(gameTime - lastTime);
      return Math.max(0.0, points - days * decayPerDay * (1.0 + assistance));
    }
  }

  public record Damage(BlockPos pos, BlockState state) {
    public static final Codec<Damage> CODEC =
        RecordCodecBuilder.create(
            i ->
                i.group(
                        BlockPos.CODEC.fieldOf("pos").forGetter(Damage::pos),
                        BlockState.CODEC.fieldOf("state").forGetter(Damage::state))
                    .apply(i, Damage::new));
  }

  public record Memory(BlockPos origin, Rotation rotation) {
    public static final Codec<Memory> CODEC =
        RecordCodecBuilder.create(
            i ->
                i.group(
                        BlockPos.CODEC.fieldOf("origin").forGetter(Memory::origin),
                        Rotation.CODEC.fieldOf("rotation").forGetter(Memory::rotation))
                    .apply(i, Memory::new));
  }
}
