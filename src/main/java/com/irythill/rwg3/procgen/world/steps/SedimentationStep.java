package com.irythill.rwg3.procgen.world.steps;

import com.irythill.rwg3.procgen.world.*;

public final class SedimentationStep implements IWorldStep {

    @Override
    public void apply(WorldGenerator engine, Column column) {
        // Expose the final sedimented height as this stage's output.
        column.height = column.finalHeight;
    }
}
