package com.irythill.rwg3.procgen.world.steps;

import com.irythill.rwg3.procgen.world.Column;
import com.irythill.rwg3.procgen.world.WorldGenerator;

public final class ContinentCompositionStep implements IWorldStep {

    @Override
    public void apply(WorldGenerator generator, Column column) {
        // Expose the continent-composed source height as this stage's output.
        column.height = column.sourceHeight;
    }
}
