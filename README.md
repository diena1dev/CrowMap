# CrowMap

A Fabric client-side mod for Minecraft 1.21.11 that embeds a browser directly in-game, with an in-world map projection, and HUD minimap.

Built on the amazing owo-lib and the Graphene project!

---

## Features

### Browser

Opens a full-screen browser pointed at a configurable Dynmap URL (defaults to the Horizon's End Dynmap).

Right-clicking in the browser screen opens a context menu with options to jump to the map coordinates under the cursor via the `/jump <x> <y>` and `/fleet jump <x> <y>` commands.

### World Projection

Renders the browser texture as a flat panel in the world, anchored to a configurable position and facing direction. The projection can be repositioned at any time and is automatically repositioned when its anchor sign is found nearby.

To anchor the projection to a sign, place a sign containing the anchor text (default: `[CrowMap]`) and press the Place World Map keybind while standing near it. The projection will follow the sign's position and facing direction.

### HUD Minimap

Renders the browser texture as a small minimap in a configurable corner of the screen. Size, margin, and corner are all configurable in the config.

### Local LiveAtlas Instance

When enabled, CrowMap downloads LiveAtlas (better Dynmap) and hosts it locally on a random loopback port.

Disabled by default, accessible from the config menu. Performance *should* be fine, but better be safe than sorry!

### System Map Overlay

Displays a full-screen image (`textures/system_map.png` in the mod's resource pack) centered and scaled to fit the screen.

Supports hold mode (visible while the key is held) or toggle mode (press to show, press again to hide)- hold and toggle mode can be changed in the mod config.

IT IS POSSIBLE TO OVERRIDE THE IMAGE!
Just like Minecraft's default assets can be overridden by using the `minecraft:` namespace, CrowMap's assets can be overriden with the `crowmap:` namespace!

Appending a `crowmap/textures/system_map.png` (yes it has to be a .png) to your locally downloaded
[ResourcePack](https://github.com/horizonsendmc/resourcepack) will allow you change the image to literally anything you want- wether that's a really dumb image or an updated map is up to you.  

---

## Keybinds

All binds are in the CrowMap category in the Controls menu.

| Action            | Default key | Description                                                                                                                                              |
|-------------------|-------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| Open Map          | X           | Opens the full-screen browser.                                                                                                                           |
| Place World Map   | ` (grave)   | Places the world projection at the block you are looking at. If anchor mode is enabled, stores the offset from the nearest matching anchor sign instead. |
| Toggle World Map  | [           | Toggles the world projection on and off.                                                                                                                 |
| Toggle HUD Map    | ]           | Toggles the HUD minimap on and off.                                                                                                                      |
| Show Overlay      | Z           | Shows the system map overlay. Hold mode by default; configurable to toggle.                                                                              |

---

## Configuration

The config screen is accessible from `Options > Controls > CrowMap Settings`. (please open a GitHub issue if you can think of a better spot to put this)

It can also be opened with the command `/owo-config crowmap`.

---

## Dependencies

- Fabric Loader
- Fabric API
- owo-lib (embedded via mod jar (aka don't also install the mod))
- Graphene (embedded via mod jar (same thing))
