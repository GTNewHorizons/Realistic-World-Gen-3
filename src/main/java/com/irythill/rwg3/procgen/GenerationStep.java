package com.irythill.rwg3.procgen;

/** Common read-only metadata exposed by generator-owned step registrations. */
public interface GenerationStep {

    String id();

    String label();

    boolean hasView();
}
