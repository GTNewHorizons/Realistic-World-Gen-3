package com.irythill.rwg3.procgen.world.steps;

import com.irythill.rwg3.procgen.world.*;

public final class RecoveryStep implements IWorldStep {

    @Override
    public void apply(WorldGenerator engine, Column column) {
        // Expose the post-erosion recovered height as this stage's output.
        column.height = column.recoveredHeight;
    }
}
