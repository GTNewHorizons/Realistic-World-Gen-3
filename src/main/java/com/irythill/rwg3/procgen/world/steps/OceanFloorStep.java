package com.irythill.rwg3.procgen.world.steps;

import com.irythill.rwg3.procgen.world.Column;
import com.irythill.rwg3.procgen.world.WorldGenerator;

public final class OceanFloorStep implements IWorldStep {

    @Override
    public void apply(WorldGenerator generator, Column column) {
        // Expose the sampled ocean-floor height as this stage's output.
        column.height = column.sourceHeight;
    }
}
