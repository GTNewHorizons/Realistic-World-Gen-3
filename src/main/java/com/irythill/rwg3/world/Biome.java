package com.irythill.rwg3.world;

/** Climate biome assigned as a smooth percentage on every land hex. */
public enum Biome {

    HOT_DESERT("Hot desert", 15.0, 29.0, 13.0),
    SAVANNA("Savanna", 10.0, 27.0, 45.0),
    JUNGLE("Jungle", 20.0, 27.0, 82.0),
    COLD_DESERT("Cold desert", 10.0, 7.0, 12.0),
    BOREAL_FOREST("Boreal forest", 15.0, -2.0, 34.0),
    TEMPERATE_FOREST("Temperate forest", 15.0, 12.0, 57.0),
    SNOWY_DESERT("Snowy desert", 5.0, -16.0, 9.0),
    SNOWY_BOREAL_FOREST("Snowy boreal forest", 10.0, -13.0, 44.0);

    public final String label;
    public final double targetPercent;
    public final double idealTemperature;
    public final double idealHumidity;

    Biome(String label, double targetPercent, double idealTemperature, double idealHumidity) {
        this.label = label;
        this.targetPercent = targetPercent;
        this.idealTemperature = idealTemperature;
        this.idealHumidity = idealHumidity;
    }
}
