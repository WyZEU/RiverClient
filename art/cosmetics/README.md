# Local cosmetic previews

Four original cuboid models: River cap, Frost beanie, Aurora wings and Ember wings.
These are local previews, disabled by default. They are not connected to ownership,
payments or presence. Other players do not receive the selection.

Start a development client with `-Driver.cosmetics.preview=true` in its JVM arguments,
join a world, then open Cosmetics → Headwear or Wings. Select a model and drag the
player to rotate it. Headwear hides when the head slot is occupied; wings hide with
elytra. Selections reset when the client closes.

Optional startup arguments:

```text
-Driver.cosmetics.head=rivercap
-Driver.cosmetics.wings=aurorawings
-Driver.cosmetics.tab=wings
```

Head ids: `none`, `rivercap`, `frostbeanie`. Wing ids: `none`, `aurorawings`, `emberwings`.
The preview flag is required even when model ids are supplied.

## Editing

Open `river_accessories.blend` in Blender. Each accessory has its own collection;
enable that collection's visibility to edit it. Models use 16 pixels per block.
The head anchor is at the base of the head, and the wing anchor is at the top of the
body. Blender X points right, Y backward and Z up.

Move, rotate and scale individual cuboids or edit their rectangular dimensions.
Keep each mesh rectangular with eight vertices and one palette material. Do not
join objects, parent them or add modifiers. Collection custom properties identify
the accessory, name and slot; material `palette_index` selects a color from the
scene's `accessory_palette`. These properties are saved in the source file.

After saving the scene, export without recreating the models:

```sh
blender --background art/cosmetics/river_accessories.blend --python art/cosmetics/export_models.py
```

The exporter validates cuboids and texture bounds, then writes the game JSON and
palette texture. The game bakes these into ordinary cached player model parts.
To recreate the original scene from its source, run:

```sh
blender --background --python art/cosmetics/create_models.py
```

This replaces the `.blend` file. Do not run it over unsaved model edits.
