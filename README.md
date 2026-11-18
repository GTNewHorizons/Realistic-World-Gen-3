# RWG3

Realistic World Generator 3 is a Minecraft 1.7.10/GTNH mod in development. Its first component is a deterministic,
hexagon-based tectonic and hydrology planner ported from the iVy prototype.

## Planner preview

Run the preview directly from the project:

```shell
./gradlew runPreview
```

The built mod JAR is also executable:

```shell
java -jar build/libs/RWG3-<version>.jar
```

Use `Tab` and `Shift+Tab` to move through the eight visible planning stages. Enter a signed 64-bit seed and press
`Enter` to
regenerate, or use the circular-arrow button/`R` shortcut for a random seed.

Use the mouse wheel to zoom toward the cursor, drag with the left or middle mouse button to pan, and press `Home` to
reset the viewport. The final heightmap shows block coordinates and surface height beside the cursor.

For a display-free verification run:

```shell
./gradlew previewSmoke
```

The runnable JAR can also sample terrain profiles in block coordinates. Point, line, grid and adaptive northward
path requests may be repeated; CSV output includes the source, eroded and sedimented heights:

```shell
java -jar build/libs/RWG3-<version>.jar --sample --seed 123 \
  --point 10605 -5266 \
  --line 10605 -6500 10605 -4000 16 \
  --north-path 10605 -5266 100 16
```
