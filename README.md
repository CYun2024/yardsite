# Yard Site

**English** | [简体中文](README.zh-CN.md)

Yard Site adds a map to the *Create New World* screen. You pick a spot on it, and the world is
generated around that spot instead of wherever the game would have put you.

The map is drawn from the world generator itself: terrain height and biome colours, sampled on the
client without loading chunks. You start with the whole region, then zoom in to one cell per chunk
and click the cell you want. Pressing *Create World* plays a short camera move instead of the usual
loading bar: the map tilts away, circles the area while the nearby chunks arrive, then dissolves as
the camera drops to your spawn point.

## Using it

1. Go to *Create New World* as usual. The map opens before the world does.
2. Click a cell to place the marker, or double-click to open chunk view first. In chunk view one cell
   is one whole chunk, and that chunk is what actually decides the spawn.
3. Press *Confirm and Create*.

Changing the seed redraws the map. Going back without picking anything creates the world the normal
way.

## With and without the Yard mods

On its own, the picked cell becomes your spawn point. Once the spawn chunks are generated, the Y
value is checked against the real heightmap, so a tree or a snow layer at that spot will not leave
you standing inside it.

With a Yard-series mod installed (`yardcore` and the rest), the same cell becomes a yard plot. The
Yard mod plans the building origin around it and puts the spawn inside the yard, and the camera move
draws the building as a wireframe on the way down.

Neither side requires the other. Install one, the other, or both.

## Pointing it at a schematic

If you already have a finished building, turn on `structure.enabled` and point it at a schematic.
The whole flow switches to "put this building in the world":

```toml
[structure]
	enabled = true
	file = "config/yardsite/structures/tower.litematic"
	rotation = "CW90"   # NONE / CW90 / CW180 / CW270
	mirror = "NONE"     # NONE / X / Z / XZ
```

What changes:

- the gold box on the map is the structure's footprint, read from the file (after rotation/mirror);
- the camera move draws its **outline**: lines light up from the foundation to the roof, instead of
  a handful of stand-in boxes;
- once you are in the world, the mod places the blocks itself. The spawn point is a cell inside the
  structure that you can actually stand in, closest to the door, and the camera comes in from that side;
- slopes, shorelines and trees are levelled out first (foundation filled in, plot volume cleared), so
  the building is not half buried.

Formats: `.litematic` (Litematica), `.nbt` (vanilla structure block) and `.schem`
(WorldEdit / Sponge). **No schematic mod is required**: all three are just gzipped NBT, read here
directly. Litematica and WorldEdit are not dependencies.

The outline goes through real **hidden line removal**: every crease edge of the structure is listed,
then each one is asked "looking from the camera, is the first thing I hit this edge?" — if yes it is
drawn, if something is in front the stretch is dropped, and a half-hidden edge keeps only its visible
half. In practice:

- the three edges at the bottom back corner of a cube are not drawn (you cannot see them in a solid
  render either);
- stack two boxes offset from each other and the lower one's covered stretch disappears while the
  exposed stretch stays;
- orbit the camera and lines behind walls drop out, then come back.

**Geometry goes below block resolution**: every block is split into the boxes of its real shape before
edges are taken, so stairs are steps, slabs are half height, trapdoors are 3/16 thick, panes are the
thin sheet in the middle, fences are a post plus rails. The wireframe and the occlusion use the same
boxes, which is why a half-height slab only hides the lower half of the pillar behind it. Cross-model
plants (grass, flowers, vines) are skipped entirely — no lines, no occlusion.

Lines come in three weights, chosen by the **role an edge plays in the shape**, not by its length:

- **outline**: a convex edge whose two outward normals now face the camera on one side and away on the
  other — a silhouette edge. Thickest and brightest. This tier is *per-view*: turn the camera and last
  view's outline falls back to a structure line while a new one takes over;
- **structure**: convex but not a silhouette (stair treads, wall edges, block rims seen from inside);
- **detail**: concave corners (reveals of a doorway, bottom of a groove) plus the edges of thin plates —
  panes, trapdoors, carpets, signs.

Tiering by role instead of length means a continuous edge never jumps between weights as the camera
moves, and a slab or a single stair tread (0.5 thick) is a convex edge just like a wall edge, so the
outline stays connected.

On top of that there is a **screen-space cut**: edges projecting to less than 3.5 pixels are not drawn.
Half-block panes and railings are two or three pixels from a distance and would just be noise up close
they are still there — the drawing simplifies itself as you pull back.

Orientation is a config value, not a re-export: `rotation` / `mirror` rotate the footprint, the spawn
point, the outline and the placed blocks together, including stair and door facing (block states are
mirrored and rotated too).

On the map screen, **T** rotates 90° clockwise and **M** cycles the mirror (none → X → Z → XZ). Those
only affect the current world creation and are not written back to the config — try the orientation
against the terrain first, then copy the value into the toml.

Leaving `structure.file` **empty** is fine: the first readable file in `config/yardsite/structures/`
(`.litematic` / `.nbt` / `.schem`) is used, so a pack author can just drop the building in that folder.
If that folder has nothing either, nothing is projected and the map screen says "No structure file
set" — that is a hint, not an error, and nothing else gets placed behind your back.

If a Yard-series mod is installed as well, structure mode wins — two buildings are never placed on
top of each other.

## Biome mods

Biomes are read from the same biome source the world uses, and TerraBlender is handled explicitly.
Mods that register their biomes through TerraBlender, such as Terralith, show up on the map
correctly. Other biome mods have not been tested one by one; most should work for the same reason.
A mod that adds biomes after world generation has run may still show the base biome on the map.

## Requirements

- Minecraft 1.21.1, NeoForge 21.1.x, Java 21
- No required dependencies; schematic formats are parsed in-house, no Litematica/WorldEdit needed
- Sodium is optional. With it, real terrain stays hidden until each chunk column is actually being
  drawn, so it cannot show through the map. Without it everything still works, the hand-off is just
  a little rougher.
- A Yard-series mod is optional and only adds the plot mode

## Config

`config/yardsite-common.toml`:

| Key | Default | What it does |
|---|---|---|
| `site.spawnSiteSelect` | `true` | show the map at all; `false` keeps vanilla spawn behaviour |
| `site.selectRadius` | `2000` | map size, ±N blocks around the spawn area |
| `site.sampleThreads` | `0` | sampling threads; `0` means half the CPU cores |
| `site.spawnChunkRadius` | `3` | spawn chunk radius kept loaded after you enter |
| `site.earlyEntry` | `false` | log in early and keep generating chunks afterwards |
| `site.revealAnim` | `true` | the camera move; `false` keeps the vanilla loading screen |
| `site.revealSurface` | `true` | draw the reveal as surfaces instead of lines (structure mode: a small model with all decorations) |
| `site.revealSurfaceScan` | `true` | gold lines grow up first (C), hold 2 s, then surfaces fill the wireframe from the bottom up (D); `false` shows the whole model at once |
| `site.revealLineWidth` | `1.5` | outline line width in GUI pixels (distance independent); only used when `revealSurface=false` |
| `site.revealLineGlow` | `true` | soft halo around the lines; turn off on weak GPUs |
| `structure.enabled` | `false` | use a schematic as the site target (see above) |
| `structure.file` | `""` | schematic path, relative to the game directory. Empty = first file in `config/yardsite/structures/` |
| `structure.rotation` | `NONE` | clockwise rotation: NONE / CW90 / CW180 / CW270 |
| `structure.mirror` | `NONE` | mirror before rotating: NONE / X / Z / XZ |
| `structure.offsetX/Y/Z` | `0` | shift the whole structure a few blocks (for aligning) |
| `structure.place` | `true` | `false` = draw the outline only, place nothing |
| `structure.flatten` | `true` | level the plot before placing |
| `structure.maxVoxels` | `4000000` | largest structure volume that will be read |
| `structure.maxLines` | `12000` | outline line budget; the longest lines are kept |
| `structure.showInfo` | `false` | show the structure info line on the map screen |

Biome colours changed in the in-game editor are saved to `config/yardsite/site_biome_colors.json`.

## Worth knowing

- Only for single-player and LAN world creation. Dedicated servers do not have this screen.
- While the camera move plays, this mod takes over the world-creation loading screen. If another mod
  does the same, the two may get in each other's way; set `site.revealAnim = false` to compare.
- The map uses the mod's own renderer. No assets from the base game or from other mods are included.
- Outline lines are a constant width <b>in screen pixels</b>: never hair-thin at a distance, never
  chunky up close.
- The schematic parsers have a self-check: `tools/schematic-check/run.sh` (plain Java, no game
  needed). Run it after touching the readers or the outline builder.

## Building

```bash
./gradlew build                  # jar in build/libs/yardsite-<version>.jar
./gradlew runClient              # dev client
```

Sodium is only needed at compile time for the optional culling hooks. Drop
`sodium-neoforge-0.8.13+mc1.21.1-mod.jar` into `libs/` before building to include them; without it
the build skips those classes and the mod still runs.

## Credits

Design, decisions and testing: CYun_CYER. Part of the code was written with AI assistants (DeepSeek,
Qwen) and then reviewed, tested and edited by hand; the mod's behaviour is not generated at runtime.

## License

MIT, see [LICENSE](LICENSE). Use it, change it, ship it, sell it, as long as the copyright notice
stays.

---

Author: **CYun_CYER** (Modrinth: CYun2024)
