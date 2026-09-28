package com.vortexso.guest_settlements.life;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.vortexso.guest_settlements.GuestSettlements;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

public final class LifeData extends SavedData {

  public record Traveler(
      long village, UUID id, CompoundTag entity, long depart, long back, BlockPos gate) {
    static final Codec<Traveler> CODEC =
        RecordCodecBuilder.create(
            i ->
                i.group(
                        Codec.LONG.fieldOf("village").forGetter(Traveler::village),
                        UUIDUtil.CODEC.fieldOf("id").forGetter(Traveler::id),
                        CompoundTag.CODEC.fieldOf("entity").forGetter(Traveler::entity),
                        Codec.LONG.fieldOf("depart").forGetter(Traveler::depart),
                        Codec.LONG.fieldOf("back").forGetter(Traveler::back),
                        BlockPos.CODEC.fieldOf("gate").forGetter(Traveler::gate))
                    .apply(i, Traveler::new));
  }

  public record Project(
      long village,
      String kind,
      Optional<Identifier> template,
      BlockPos origin,
      int direction,
      int size,
      int placed,
      long day,
      int wood,
      int stone) {
    static final Codec<Project> CODEC =
        RecordCodecBuilder.create(
            i ->
                i.group(
                        Codec.LONG.fieldOf("village").forGetter(Project::village),
                        Codec.STRING.fieldOf("kind").forGetter(Project::kind),
                        Identifier.CODEC.optionalFieldOf("template").forGetter(Project::template),
                        BlockPos.CODEC.fieldOf("origin").forGetter(Project::origin),
                        Codec.INT.fieldOf("direction").forGetter(Project::direction),
                        Codec.INT.optionalFieldOf("size", 0).forGetter(Project::size),
                        Codec.INT.fieldOf("placed").forGetter(Project::placed),
                        Codec.LONG.fieldOf("day").forGetter(Project::day),
                        Codec.INT.optionalFieldOf("wood", 0).forGetter(Project::wood),
                        Codec.INT.optionalFieldOf("stone", 0).forGetter(Project::stone))
                    .apply(i, Project::new));

    Project withProgress(int placed, long day, int wood, int stone) {
      return new Project(
          village, kind, template, origin, direction, size, placed, day, wood, stone);
    }
  }

  public static final Codec<LifeData> CODEC =
      RecordCodecBuilder.create(
          i ->
              i.group(
                      Traveler.CODEC.listOf().fieldOf("travelers").forGetter(d -> d.travelers),
                      Project.CODEC.listOf().fieldOf("projects").forGetter(d -> d.projects))
                  .apply(i, LifeData::new));

  public static final SavedDataType<LifeData> TYPE =
      new SavedDataType<>(GuestSettlements.id("village_life"), LifeData::new, CODEC);

  final List<Traveler> travelers = new ArrayList<>();
  final List<Project> projects = new ArrayList<>();

  private LifeData() {}

  private LifeData(List<Traveler> travelers, List<Project> projects) {
    this.travelers.addAll(travelers);
    this.projects.addAll(projects);
  }

  public static LifeData get(ServerLevel level) {
    return level.getDataStorage().computeIfAbsent(TYPE);
  }

  public List<Traveler> travelers(long village) {
    return travelers.stream().filter(traveler -> traveler.village() == village).toList();
  }

  public List<Project> projects(long village) {
    return projects.stream().filter(project -> project.village() == village).toList();
  }

  public List<Project> projects() {
    return List.copyOf(projects);
  }

  void replace(Project old, Project project) {
    int index = projects.indexOf(old);
    if (index >= 0) {
      projects.set(index, project);
    } else {
      projects.add(project);
    }
    setDirty();
  }

  void remove(Project project) {
    projects.remove(project);
    setDirty();
  }

  void add(Traveler traveler) {
    travelers.add(traveler);
    setDirty();
  }

  void remove(Traveler traveler) {
    travelers.remove(traveler);
    setDirty();
  }
}
