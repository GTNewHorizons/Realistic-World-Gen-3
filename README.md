# RWG3

RWG3 is in development. Its first component is a deterministic,
hexagon-based tectonic and hydrology planner. The main idea is to be able to fine-tune the worldgen from a 2d preview,
with CubicChunk & DH worldgen using the same inputs as the 2d preview.

<img width="1334" height="918" alt="image" src="https://github.com/user-attachments/assets/e976bf80-01bf-4d3d-8794-d8b14e720de0" />

At the moment, there is only the preview tool on the repo, as it should be the development focus.
Actual CC worldgen is in the work, but not pushed on this repo. Soon™️

## 2D preview tool

Run the preview directly from the project:

```shell
./gradlew runPreview
```

The built mod JAR is also executable:

```shell
java -jar build/libs/RWG3-<version>.jar
```

Use `Tab` and `Shift+Tab` (or just the arrows) to move through all the visible 2d planning stages. Enter a signed 64-bit seed and press
`Enter` to regenerate, or use the circular-arrow button/`R` shortcut for a random seed.

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
