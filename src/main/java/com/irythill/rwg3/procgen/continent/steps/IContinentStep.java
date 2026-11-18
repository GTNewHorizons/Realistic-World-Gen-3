package com.irythill.rwg3.procgen.continent.steps;

import com.irythill.rwg3.procgen.continent.Continent;

/** One deterministic finite-continent transformation. */
public interface IContinentStep {

    void apply(long seed, Continent continent);
}
