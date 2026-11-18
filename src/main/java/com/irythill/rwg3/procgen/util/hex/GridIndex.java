package com.irythill.rwg3.procgen.util.hex;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Stable dense indexing and cached neighbor data for numeric planner passes. */
public final class GridIndex {

    public final List<AxialCoordinate> coordinates;
    public final Vec2[] worldPositions;
    public final int[][] neighbors;
    public final int[][] terrainNeighbors;
    public final Vec2[] directionVectors;
    private final Map<AxialCoordinate, Integer> indices;

    public GridIndex(HexGrid<?> grid) {
        coordinates = grid.coordinates();
        indices = new HashMap<>();
        for (int i = 0; i < coordinates.size(); i++) indices.put(coordinates.get(i), i);
        worldPositions = new Vec2[coordinates.size()];
        neighbors = new int[coordinates.size()][6];
        terrainNeighbors = new int[coordinates.size()][6];
        directionVectors = new Vec2[6];
        for (int direction = 0; direction < 6; direction++) {
            directionVectors[direction] = new AxialCoordinate(0, 0).neighbor(direction)
                .toWorld()
                .normalized();
        }
        for (int i = 0; i < coordinates.size(); i++) {
            AxialCoordinate coordinate = coordinates.get(i);
            worldPositions[i] = grid.wrap(coordinate.toWorld());
            for (int direction = 0; direction < 6; direction++) {
                AxialCoordinate adjacent = coordinate.neighbor(direction);
                AxialCoordinate wrapped = grid.wrap(adjacent);
                Integer neighbor = indices.get(wrapped);
                neighbors[i][direction] = neighbor == null ? i : neighbor;
                terrainNeighbors[i][direction] = adjacent.equals(wrapped) ? neighbors[i][direction] : i;
            }
        }
    }

    public int indexOf(AxialCoordinate coordinate) {
        Integer index = indices.get(coordinate);
        return index == null ? -1 : index;
    }
}
