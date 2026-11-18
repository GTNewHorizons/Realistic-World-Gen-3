package com.irythill.rwg3.procgen.continent.steps;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.irythill.rwg3.procgen.continent.Continent;
import com.irythill.rwg3.procgen.continent.ContinentGenerator.Plate;
import com.irythill.rwg3.procgen.util.hex.AxialCoordinate;
import com.irythill.rwg3.procgen.util.hex.Vec2;

public final class PlatePlacementStep implements IContinentStep {

    @Override
    public void apply(long seed, Continent continent) {
        // Discover the finite grid's unique Poisson sites and create one moving plate per site.
        mapPlateSites(continent);
        // Transfer each sampled site's plate identity onto the grid cells it owns.
        assignPlates(continent);
        // Derive the plate centers needed to evaluate rotational velocity later.
        recomputeCenters(continent);
    }

    private static void mapPlateSites(Continent state) {
        Set<Long> sites = new HashSet<>();
        for (AxialCoordinate coordinate : state.grid.coordinates()) {
            Vec2 point = coordinate.toWorld();
            state.plateField.nearest(point.x, point.y, 1, state.query);
            long owner = state.query.ids[0];
            state.plateSiteByCoordinate.put(coordinate, owner);
            sites.add(owner);
        }
        List<Long> orderedSiteIds = new ArrayList<>(sites);
        orderedSiteIds.sort(Long::compareUnsigned);
        for (long siteId : orderedSiteIds) {
            int plateId = state.plates.size() + 1;
            double angle = state.random.nextDouble() * Math.PI * 2.0;
            Vec2 drift = new Vec2(Math.cos(angle), Math.sin(angle)).multiply(state.config.driftMultiplier);
            double spin = (state.random.nextDouble() * 2.0 - 1.0) * state.config.spinMultiplier;
            state.plateIdBySite.put(siteId, plateId);
            state.plates.add(new Plate(drift, spin));
        }
    }

    private static void assignPlates(Continent state) {
        for (AxialCoordinate coordinate : state.grid.coordinates()) {
            Integer plateId = state.plateIdBySite.get(state.plateSiteByCoordinate.get(coordinate));
            if (plateId == null) throw new IllegalStateException("Missing Poisson plate site");
            state.grid.get(coordinate).plateId = plateId;
        }
    }

    private static void recomputeCenters(Continent state) {
        double[] x = new double[state.plates.size() + 1];
        double[] y = new double[state.plates.size() + 1];
        int[] count = new int[state.plates.size() + 1];
        for (AxialCoordinate coordinate : state.grid.coordinates()) {
            int id = state.grid.get(coordinate).plateId;
            if (id <= 0) continue;
            Vec2 point = coordinate.toWorld();
            x[id] += point.x;
            y[id] += point.y;
            count[id]++;
        }
        for (int id = 1; id <= state.plates.size(); id++) {
            if (count[id] > 0) state.plates.get(id - 1).center = new Vec2(x[id] / count[id], y[id] / count[id]);
        }
    }

}
