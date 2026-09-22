package com.vortexso.guest_settlements.village;

public record RoadEdge(long firstVillageId, long secondVillageId) {
    public RoadEdge {
        if (firstVillageId == secondVillageId) {
            throw new IllegalArgumentException("A road cannot connect a village to itself");
        }
        if (firstVillageId > secondVillageId) {
            throw new IllegalArgumentException("RoadEdge ids must be ordered");
        }
    }

    public static RoadEdge of(long first, long second) {
        return first < second
                ? new RoadEdge(first, second)
                : new RoadEdge(second, first);
    }
}
