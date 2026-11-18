package com.irythill.rwg3.procgen.continent.steps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.irythill.rwg3.procgen.continent.Continent;
import com.irythill.rwg3.procgen.continent.Hex;
import com.irythill.rwg3.procgen.util.hex.AxialCoordinate;
import com.irythill.rwg3.procgen.util.hex.Vec2;

public final class OceanicPlatesStep implements IContinentStep {

    @Override
    public void apply(long seed, Continent continent) {
        // Mark edge-connected plates, then tune the land/ocean mix among their neighbors.
        classifyOceanicPlates(continent);
        // Materialize the plate-level classification onto every grid cell.
        writeOceanicClassification(continent);
    }

    private static void classifyOceanicPlates(Continent state) {
        Set<Integer> exterior = new HashSet<>();
        for (AxialCoordinate coordinate : state.grid.coordinates()) {
            Vec2 point = coordinate.toWorld();
            if (point.x < state.config.exteriorMargin || point.x > state.grid.width() - state.config.exteriorMargin
                || point.y < state.config.exteriorMargin
                || point.y > state.grid.height() - state.config.exteriorMargin) {
                exterior.add(state.grid.get(coordinate).plateId);
            }
        }
        for (int id : exterior) if (id > 0) state.plates.get(id - 1).oceanic = true;
        // Prefer low-contact interior plates, then high-contact exterior candidates.
        cherryPickOceanicPlates(state, state.config.internalCherryPick, false);
        cherryPickOceanicPlates(state, state.config.externalCherryPick, true);
    }

    private static void writeOceanicClassification(Continent state) {
        for (AxialCoordinate coordinate : state.grid.coordinates()) {
            Hex hex = state.grid.get(coordinate);
            hex.oceanic = hex.plateId > 0 && state.plates.get(hex.plateId - 1).oceanic;
        }
    }

    private static void cherryPickOceanicPlates(Continent state, int count, boolean reverse) {
        if (count == 0) return;
        Set<Integer> oceanic = new HashSet<>();
        for (int i = 0; i < state.plates.size(); i++) if (state.plates.get(i).oceanic) oceanic.add(i + 1);
        Map<Integer, Integer> contact = new HashMap<>();
        for (AxialCoordinate coordinate : state.grid.coordinates()) {
            int id = state.grid.get(coordinate).plateId;
            if (id == 0 || oceanic.contains(id)) continue;
            for (int direction = 0; direction < 6; direction++) {
                int neighbor = state.grid.getWrapped(coordinate.neighbor(direction)).plateId;
                if (neighbor != id && oceanic.contains(neighbor)) contact.put(id, contact.getOrDefault(id, 0) + 1);
            }
        }
        List<Map.Entry<Integer, Integer>> candidates = new ArrayList<>(contact.entrySet());
        candidates.sort(
            Comparator.comparingInt(Map.Entry<Integer, Integer>::getValue)
                .thenComparingInt(Map.Entry<Integer, Integer>::getKey));
        if (reverse) Collections.reverse(candidates);
        for (int i = 0; i < Math.min(count, candidates.size()); i++) {
            state.plates.get(
                candidates.get(i)
                    .getKey() - 1).oceanic = true;
        }
    }
}
