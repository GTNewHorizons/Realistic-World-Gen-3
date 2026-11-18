package com.irythill.rwg3.procgen.world.steps;

import com.irythill.rwg3.procgen.world.*;

/** One exact-column transformation in the infinite-world pipeline. */
public interface IWorldStep {

    void apply(WorldGenerator engine, Column column);
}
