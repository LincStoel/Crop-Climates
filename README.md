# Crop Climates

**An add-on for [Cold Sweat](https://www.curseforge.com/minecraft/mc-mods/cold-sweat) that makes crop growth consider the environment.**

Every plant has a temperature and humidity range that it grows best in. Plant your crops somewhere that matches and it will grow well. Plant your crops somewhere hostile and growth slows to a crawl. Plant your crops far enough outside their range and they will start to wilt. Temperature is read live from Cold Sweat, so the biome, altitude, time of day, local temperature, etc. all affect how a farm grows. Additionally, crop climates implements a humidity system that builds on top of Minecraft's humidity allowing for greenhouses (enclosed spaces with a hygrometer) to modulate humidity to suit your crop's needs.

Minecraft 1.21.1 · NeoForge · requires Cold Sweat 2.4.3+

---

## Features

- **Climate-driven growth.** Each crop, sapling and growable plant has its own ideal temperature and humidity band. How well its environment matches scales growth from a small bonus (1.25× vanilla at the center of both bands) down to nearly nothing.
- **Wilting.** Plants far outside their band slowly lose growth stages, with their own particle effect. Saplings that wilt all the way die off into a dead bush, crops eventually wither and break.
- **Weather and sky.** Rain raises humidity for crops it actually falls on, meaning that open air crops contend with a more variable humidity. However, crops that can't see the sky grow a little slower since they aren't receiving true sunlight. Nether crops work the other way round and are penalized under open sky.
- **Greenhouses.** Hang a **Hygrometer** inside a sealed room to turn it into a greenhouse allowing you to control the humidity by placing water, lava, as well as wet and dry sponges. Water and wet sponges humidify the room, lava and dry sponges dry it out.
- **Soil Tester.** A handheld tool that reports the temperature, humidity and expected growth rate at any crop.
- **Tooltips.** Hold Alt over a seed or sapling to see its ideal conditions and whether it would thrive or struggle where you're standing.
- **Commands.** `/cropclimates explain <pos>` breaks down why a plant is growing the way it is, `/cropclimates audit` lists crop-like blocks from your mods that have no climate band yet, and `/cropclimates status` shows the mod's current state.
- **Advancements.** Custom advancements commemorate your crop growing exploits.

## Data-driven crop bands

Every crop's growth band is defined in datapack JSON under `data/<namespace>/crop_climate/<modid>.json`, so modpack makers can retune, add or remove crops without touching code. A pack only needs to include the crops it changes.

```json
{
  "wheat": {
    "temperature": [40, 90],
    "humidity": [0.25, 0.85],
    "item": "minecraft:wheat_seeds"
  },
  "#c:crops": {
    "temperature": [45, 90],
    "humidity": [0.25, 0.7]
  }
}
```

Entries can name a single block or a block tag, and optional flags cover saplings (`tree`), aquatic crops (`aquatic`), Nether crops (`nether`), whether a plant can wilt (`regresses`), and which growth hook a block uses. Biome humidity can also be overridden through `data/<namespace>/climate/biome_moisture.json`, and the blocks that humidify or dry out a greenhouse are ordinary block tags (`crop_climates:humidifier` and `crop_climates:desiccant`).

## Built-in mod compatibility

Crop Climates ships with climate bands for vanilla Minecraft and the following mods. Each file only loads if its mod is installed, so there's nothing to configure.

Aether, 
Ars Elemental, 
Ars Nouveau, 
Brewin' and Chewin', 
Cold Sweat, 
Corn Delight, 
Deep Aether, 
Ecologics, 
Ender's Delight, 
Farmer's Delight, 
Fruits Delight, 
Ghosts, 
Luminous Nether, 
My Nether's Delight, 
Oritech, 
Quark, 
Supplementaries, 
WAN's Ancient Beasts

(Not on this list? it's easy to add crops via datapack!)

[Jade](https://www.curseforge.com/minecraft/mc-mods/jade) is supported as an optional extra: looking at a crop shows how well it's doing (thriving, struggling and so on), and looking at a Hygrometer shows its greenhouse humidity.

## Pairs well with a seasons mod

Crop Climates takes its temperatures from Cold Sweat, so anything that changes Cold Sweat's temperatures changes how crops grow too. With a seasons mod that Cold Sweat supports, such as Serene Seasons, crops follow the seasons on their own: summer crops struggle through winter, cold-hardy crops take over, and greenhouses and hearths become a way to keep growing out of season.

## Highly configurable

Almost every number in the mod is exposed in the server config, including:

- the growth ceiling, floor and curve, and how quickly growth falls off outside a band
- sky and Nether-sky penalties, and how much rain raises humidity
- whether plants wilt, how far outside their band, and how quickly
- greenhouse size limits, humidity strength, and drip and dust effects
- the growth tiers shown by the Soil Tester, tooltips, and Jade readout
- a blacklist of blocks or block tags the mod should leave alone
- cache sizes and per-tick budgets for servers that want to tune performance

## Performance

Crop Climates is designed to stay out of the way. It was stress tested with fields of around 50,000 plants at 10× the normal random tick speed, thousands of separate greenhouses, and a maximum-size greenhouse packed with growing trees. Crop Climates' own share of server tick time stayed in the low single digits, and even the heaviest scenarios stayed well within manageable load.

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.181 or newer
- Cold Sweat 2.4.3 or newer (required)
- Jade 15+ (optional)
