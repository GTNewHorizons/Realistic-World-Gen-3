package com.irythill.rwg3.procgen.world.steps;

import com.irythill.rwg3.procgen.world.*;

public final class ErosionStep implements IWorldStep {

    @Override
    public void apply(WorldGenerator engine, Column column) {
        // Expose the terrain height after erosion as this stage's output.
        column.height = column.erodedHeight;
    }
}
