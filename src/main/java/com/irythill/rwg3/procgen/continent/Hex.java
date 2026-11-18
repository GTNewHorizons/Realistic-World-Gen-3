package com.irythill.rwg3.procgen.continent;

import com.irythill.rwg3.procgen.util.hex.Vec2;

/** Mutable data carried by one finite procgen hex. */
public final class Hex {

    public int plateId;
    public boolean oceanic;
    public Vec2 velocity = Vec2.ZERO;
    public double convergence;
    public double divergence;
    public double height;
    public int terrainRegionId;
    public double erosionWeight;

    public Hex copy() {
        Hex copy = new Hex();
        copy.plateId = plateId;
        copy.oceanic = oceanic;
        copy.velocity = velocity;
        copy.convergence = convergence;
        copy.divergence = divergence;
        copy.height = height;
        copy.terrainRegionId = terrainRegionId;
        copy.erosionWeight = erosionWeight;
        return copy;
    }
}
