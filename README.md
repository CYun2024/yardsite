# Yard Site

**English** | [简体中文](README.zh-CN.md)

Select a spawn point or building site before world creation, and play a reveal animation before entering the world.

## Overview

- **Selection map**: Displayed when creating a new single-player world. Provides a 3D terrain and biome sandbox for choosing a spawn point over a wide area.
- **Chunk detail view**: Each cell represents one real chunk, allowing the site to be placed on chunk boundaries.
- **Reveal animation**: After the site is confirmed, the camera flies from the sandbox view to the site. The building is generated as a gold wireframe and then as surface geometry, after which the real terrain takes over.
- **Structure mode**: Uses a `.litematic`, `.nbt` or `.schem` file as the site target. Footprint, spawn point, reveal animation and the actual building are all derived from that file.

## Usage

1. Select "Create New World". The selection map is displayed.
2. Choose an approximate position on the large sandbox. Double-click or select "Enter Chunk Detail" to choose the site by chunk.
3. Select "Confirm and Create" to play the reveal animation and create the world.

If the map is dismissed without a selection, the world is created with the vanilla spawn procedure. Changing the world seed causes the selection map to be sampled again.

## Reveal animation

- **Camera transition**: Uses the fixed field of view of the final aerial position. Approach is performed by camera translation; distance is interpolated logarithmically, with a mid-flight lift to clear high terrain.
- **Gold wireframe phase**: The wireframe is generated from the structure's crease edges, lit from bottom to top, with hidden-line removal based on the current camera position. When installed with the Yard series, the inn's wireframe is displayed.
- **Surface phase**: Structure mode only. The building is drawn from baked block models, filled from bottom to top with a scan frame.
- **Automatic framing**: Camera distance is derived from the content bounding box; structure mode uses the structure geometry.
- **Handover and fade**: After the real terrain takes over, the building effect remains for a configurable delay and then fades in place.

The reveal animation (`site.revealAnim`) or the surface phase (`site.revealSurface`) can be disabled in the configuration.

## Structure mode

Enable `structure.enabled` to display the structure footprint on the selection map and use the structure as the basis for the reveal animation and world generation.

```toml
[structure]
	enabled = true
	file = "config/yardsite/structures/tower.litematic"
	rotation = "CW90"   # NONE / CW90 / CW180 / CW270
	mirror = "NONE"     # NONE / X / Z / XZ
```

- Supported formats: `.litematic`, `.nbt`, `.schem`. No projection mod is required.
- When `structure.file` is empty, the first readable file in `config/yardsite/structures/` is used.
- Geometry is generated from baked block models. Connection states for fences, walls, glass panes and stairs are taken from the block states in the file; colors are sampled from block textures.
- The footprint can be flattened before placement (`structure.flatten`); the spawn point is a standable block inside the structure, near a door.
- Rotation, mirror and offset apply to the footprint, spawn point, reveal animation and the actual blocks.
- In the selection map, press T to rotate 90° clockwise and M to cycle the mirror mode. These changes apply only to the current world creation and are not written to the configuration.
- When installed with the Yard series, structure mode takes priority and only one building is generated.

## Yard series compatibility

- With this mod installed alone, the selected position is used as the spawn point.
- When installed with the Yard series, the selected position is used as the yard plot; the Yard series plans the building and spawn point.
- Neither side requires the other; both can be installed separately or together.

## Requirements

- Minecraft 1.21.1, NeoForge 21.1.x, Java 21.
- No required dependencies. Projection files are parsed by this mod.
- Sodium is optional. When installed, the handover between real terrain and the sandbox is more precise; without it, functionality is unaffected.
- Applies to single-player and LAN world creation only. Dedicated servers do not have this flow.

## Configuration

Configuration file: `config/yardsite-common.toml`.

| Key | Default | Description |
|---|---|---|
| `site.spawnSiteSelect` | `true` | Display the selection screen when creating a new world; disable to use the vanilla spawn procedure |
| `site.selectRadius` | `2000` | Selection map radius (blocks) |
| `site.selectRadiusEpic` | `8000` | Selection map radius for Epic Terrain (blocks) |
| `site.sandboxColumns` | `256` | Maximum number of columns per side of the selection map |
| `site.sampleThreads` | `0` | Sampler thread count; 0 for automatic |
| `site.spawnChunkRadius` | `3` | Spawn chunk radius kept loaded after entering the world |
| `site.earlyEntry` | `false` | Enter the world after waiting for 9 spawn chunks only |
| `site.instantReveal` | `false` | Disable the growth animation of selection map columns |
| `site.revealAnim` | `true` | Play the reveal animation before entering the world |
| `site.revealSurface` | `true` | Draw the building as surface geometry during the reveal |
| `site.revealSurfaceScan` | `true` | Fill the surface from bottom to top with a scan frame |
| `site.revealFadeDelayMs` | `1000` | Time the building effect remains after the center chunk hands over (ms) |
| `site.revealLineWidth` | `1.5` | Gold wireframe width (GUI pixels) |
| `site.revealLineGlow` | `true` | Glow around the gold wireframe |
| `structure.enabled` | `false` | Enable structure mode |
| `structure.file` | `""` | Structure file path; empty for automatic selection |
| `structure.rotation` | `NONE` | Clockwise rotation applied on placement |
| `structure.mirror` | `NONE` | Mirror mode applied before rotation |
| `structure.offsetX/Y/Z` | `0` | Structure offset (blocks) |
| `structure.place` | `true` | Generate the building during world creation |
| `structure.flatten` | `true` | Flatten the footprint before placement |
| `structure.maxVoxels` | `4000000` | Maximum readable structure volume |
| `structure.maxLines` | `12000` | Maximum number of reveal wireframe segments |
| `structure.showInfo` | `false` | Display structure information on the selection map |

Biome color adjustments are stored in `config/yardsite/site_biome_colors.json`.

## Notes

- The reveal animation takes over the vanilla world-creation loading screen. It may conflict with other mods that modify the same screen; disable it with `site.revealAnim`.
- The ground scan limit for site selection and building placement is y=300 (500 for Epic Terrain). High terrain is handled at its actual height.
- This mod contains no game assets or third-party mod code.
- The gold wireframe width is a constant in screen pixels and does not change with distance.

## Building

```bash
./gradlew build                    # 1.21.1 NeoForge → build/libs/
./gradlew -p forge-1.20.1 build    # 1.20.1 Forge     → dist/
./gradlew runClient                # development client
```

Sodium is used only for optional compile-time culling hooks. Place the corresponding Sodium jar in `libs/` to enable them; the build succeeds without it.

### 1.20.1 Forge (legacy)

`forge-1.20.1/` is a **separate Gradle build** (ForgeGradle 6 + MixinGradle) that shares the same core sources rather than duplicating them:

1. `generateForgeSources` copies `src/main/java` into `forge-1.20.1/build/generated/forge-src`;
2. a short table of exact string replacements (in `forge-1.20.1/build.gradle`) adapts the 1.20.1 API differences;
3. the result is compiled together with the Forge platform classes in `forge-1.20.1/src/main/java/dev/yard/site/platform/forge`.

So **there is still only one copy of the core code**: fix it here, and both jars pick it up. Changes that only touch `forge-1.20.1/` (mods.toml, mixins, platform classes, replacements) do not affect the NeoForge build. The jar lands in `dist/` at the repository root and is not committed.


## Development notes

Design, trade-offs and in-game testing were performed by the author. Some code was written with AI assistance and was manually reviewed and organized before submission. The mod does not depend on any AI service at runtime.

## License

MIT License. See [LICENSE](LICENSE). Use, modification, redistribution and commercial use are permitted with retention of the copyright notice.

---

Author: **CYun_CYER** (Modrinth: CYun2024)