package com.irythill.rwg3.procgen.world.steps;

import com.irythill.rwg3.procgen.world.Column;
import com.irythill.rwg3.procgen.world.WorldGenerator;

public final class InterpolationStep implements IWorldStep {

    @Override
    public void apply(WorldGenerator generator, Column column) {
        // Expose the continuously interpolated source height as this stage's output.
        column.height = column.sourceHeight;
    }
}
