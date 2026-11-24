package com.irythill.rwg3.world.steps;

import com.irythill.rwg3.world.World;

/** One deterministic world generation pass. */
public interface IGenerationStep {

    void apply(long seed, World world);
}
