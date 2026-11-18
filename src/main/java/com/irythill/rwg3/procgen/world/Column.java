package com.irythill.rwg3.procgen.world;

/** Mutable exact block-coordinate sample passed through the worldgen pipeline. */
public final class Column {

    public final double x;
    public final double z;
    public double height;
    public double sourceHeight;
    public double erodedHeight;
    public double recoveredHeight;
    public double sedimentHeight;
    public double finalHeight;
    public double sourceGrade;
    public double sedimentGrade;
    public double sedimentMix;
    public long continentId;
    public int plateId;
    public boolean oceanic;
    public boolean complete;

    public Column(double x, double z) {
        this.x = x;
        this.z = z;
    }
}
