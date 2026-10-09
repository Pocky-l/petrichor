<p align="center">
  <img src="src/main/resources/logo.png" alt="Petrichor: Rain &amp; Storms" width="160">
</p>

<h1 align="center">Petrichor: Rain &amp; Storms</h1>

<p align="center">
  Realistic rain and storms: rain types, puddles, runoff and drips, branching lightning with delayed thunder.
</p>

<p align="center">
  <img alt="Minecraft 1.21.1" src="https://img.shields.io/badge/Minecraft-1.21.1-62B47A">
  <img alt="NeoForge" src="https://img.shields.io/badge/Loader-NeoForge-F16436">
  <img alt="License MIT" src="https://img.shields.io/badge/License-MIT-blue">
</p>

## Features

### Rain

- **Rain types** that change during a long rain: drizzle, rain, downpour and thunderstorm. **Rain builds up and eases
  off like real rain**: it starts as a light drizzle, swells step by step into rain, a downpour or a thunderstorm over
  a few minutes, and dies down to a drizzle again before it stops - no sudden jumps. Intensity swells and ebbs with
  gusts, wind turns slowly and slants the rain.
- **Sun showers**: some light rains in daytime fall while the sun keeps shining - blue sky, bright light, glinting
  drops.
- **Rainbows** opposite the sun in sun showers and sometimes when a rain clears up in daylight: a primary bow with a
  fainter, reversed secondary one, standing where a real rainbow would (mornings and afternoons, when the sun is low).
- Drops with depth: they taper, glint and vary in size and speed; curtains of rain sweep past with the wind in
  sheets, coarser and higher the further away they are; light gets dimmer and colder under rain clouds.
- **Rainy air** instead of a flat grey wall: the haze deepens with distance and settles in valleys, so ridges behind
  ridges fade in layers; heavier showers drift across the land; the sky turns into a cloud deck with dark rolls and
  rain shafts on the horizon, lit up by lightning.
- In snowy and dry land (deserts, badlands) the mod steps aside and the weather is vanilla.
- Rain never passes through walls: falling slanted in the wind, drops stop at the wall and the lee side of a building
  stays dry.
- Splashes on the ground, rings on water, spray from leaves and mobs, mist over the canopy in heavy rain.
- **Rain on hot blocks**: drops falling on lava, magma blocks and lit campfires hiss and boil away in small puffs of
  steam, and a lava lake steams faintly in the rain.

### Water on the ground

- **Puddles** gather in hollows and on flat ground, reflect the sky and the world, ripple under the rain and dry out
  slowly afterwards. Wet ground darkens and shines; ground under roofs stays dry with a soft edge.
- Water runs off towards edges, spills over steps and **drips from roofs, eaves, cliffs and leaves** - also for a while
  after the rain.
- Splashing footsteps in puddles.

### Sound

- A **soundscape placed in the world**, not a recording in your ears: rain is heard from where it actually falls -
  through the open door, from the field on your left, from the crowns above.
- **Every surface sounds like itself**: grass and earth, stone, planks, metal, glass, wool, puddles and open water each
  have their own field recordings, light and heavy.
- **Intensity matters**: a drizzle is a soft hush with single drops you can pick out, a downpour a dense roar;
  recordings crossfade with the rain's intensity and gusts.
- **Shelter**: the roof above you drums according to what it is made of - a tin roof rings, planks knock, a skylight
  taps, a tent thuds, a thick roof rumbles. Rain beats on the windows the wind drives it against.
- **Muffling**: rain behind walls, windows and roofs sounds muffled (a low-pass filter), not just quieter - step inside
  and the storm becomes a cosy murmur; open the door and it rushes in.
- Single drops falling off eaves and leaves land on stone, wood, metal or a puddle - and keep dripping after the rain.
- Rain far away in every open direction and the **wind** in storms give the world its size.
- **Under water** the rain and the storm above turn dull and faint. Underground, wind, thunder and distant rain fall
  silent and rise again as you walk out of a cave.

### Lightning and thunder

- Branching lightning with a growing leader, return strokes and afterglow; flashes light the sky and the world.
- Distant bolts and flashes inside the clouds during thunderstorms.
- **Thunder arrives after the flash** at the speed of sound: a sharp crack nearby, a rolling clap further away, a low
  rumble from a distant storm - and a dull boom when heard from indoors.
- Extra strikes during thunderstorms prefer the tallest spot nearby.

### Cinematic storm

- **The world holds its breath**: for a moment before lightning strikes the view darkens - then the flash.
- A close strike **overexposes the view**; afterwards the eyes need a moment in the dark, and the bolt lingers as a
  fading afterimage.
- In the light of a flash every falling drop shines and freezes in place, like in a strobe.

## Controls

No keys. Commands for operators:

| Command | Effect |
|---|---|
| `/petrichor weather <drizzle\|rain\|downpour\|thunderstorm\|sun_shower> [seconds]` | Start a rain that builds up to this type |
| `/petrichor weather clear` | Stop the rain |
| `/petrichor wetness <0..1>` | Set how wet the ground is |
| `/petrichor status` | Show the current weather |
| `/petrichor strike` | Strike lightning near you |

## Crafting

Nothing to craft: the mod changes the weather.

## Configuration

Client options (*Mods -> Petrichor: Rain & Storms -> Config*): quality preset (Low / Medium / High / Ultra), rain density, wind,
splashes, fog, rainy atmosphere, steam on hot blocks, rainbows, puddles, runoff and drips, lightning and flashes, cinematic effects (darkening before
a strike, flash glare and afterimage) and sound - rain, drip, wind and thunder volume, roof sounds, muffling behind walls. Server options: chances of each rain type and of sun showers, how fast the rain
changes, how fast the ground gets wet and dries, extra lightning strikes.

The mod works on the client alone; installed on the server too, every player sees the same rain, sun showers and wetness.
Muffling is turned off automatically when
[Sound Physics Remastered](https://www.curseforge.com/minecraft/mc-mods/sound-physics-remastered) is installed.

## Compatibility

- **Shader packs** ([Iris](https://modrinth.com/mod/iris), Oculus): the rain, splashes, drips and distant curtains are drawn through the pack's
  weather program, so they take on the pack's look and lighting. Puddles become pixel-edged pools of real water that the
  pack draws with its own water (reflections, waves). The mod's sky haze and the wet sheen of the ground step aside -
  packs bring their own fog and wet surfaces. Sounds, lightning and the cinematic storm work as usual.
- [Sodium](https://modrinth.com/mod/sodium) works.

## Installation

1. Install [NeoForge](https://neoforged.net) for Minecraft 1.21.1.
2. Put this mod into the `mods` folder.

## Building

```sh
./gradlew build
```

The jar is written to `build/libs/`.

## Credits

- Author: **Pocky**.
- Sounds: field recordings from [Freesound](https://freesound.org), all released under
  [Creative Commons 0](https://creativecommons.org/publicdomain/zero/1.0/), edited into loops and single sounds by
  `tools/prepare_sounds.py`:
  - [soft_rain_outside](https://freesound.org/s/768872/) by Gustavo_C
  - [mediumrain](https://freesound.org/s/160699/) by klangfabrik
  - [Rain, Heavy in Woods](https://freesound.org/s/870823/) by CHallSmith
  - [RAINVege_Forest. Drops On Grass, Bushes, Ferns And Leaves](https://freesound.org/s/865323/) by newlocknew
  - [rain medium on street pavement](https://freesound.org/s/454143/) by kyles
  - [Heavy rain, splatty on stone](https://freesound.org/s/338109/) by SpliceSound
  - [Rain On Wood Deck (Burya rain library)](https://freesound.org/s/611421/) by cocaine
  - [Rain, on metallic tin roof, heavy med light](https://freesound.org/s/717874/) by TRP
  - [rooflight raindrops](https://freesound.org/s/242724/) by rucisko
  - [Rain under a skylight](https://freesound.org/s/718977/) by clement.bernardeau
  - [Rain_Hitting_Window_9](https://freesound.org/s/869851/) by SignatureSoundsOrg
  - [Rain on tent](https://freesound.org/s/484723/) by Breviceps
  - [Rain on Tent](https://freesound.org/s/817155/) by craigsmith
  - [Rain, on water lake, modulating light to med](https://freesound.org/s/572429/) by TRP
  - [md10trk1 (rain falling into a puddle)](https://freesound.org/s/124168/) by alienistcog
  - [Indoors_Shed_RainOnTinRoof_01](https://freesound.org/s/521773/) by MrFossy
  - [Medium rain on a tin roof](https://freesound.org/s/771981/) by Sassaby
  - [Rain in a barn](https://freesound.org/s/414162/) by felix.blume
  - [rain medium on roof or large wood shed](https://freesound.org/s/451156/) by kyles
  - [Strong Rain on Roof from Inside of the Room](https://freesound.org/s/428603/) by Erbsland-Music
  - [Wind gusting from window](https://freesound.org/s/574556/) by TRP
  - [eau qui coule - water dripping](https://freesound.org/s/843505/) by vfrattaroli
  - [water dripping from gutter to ground](https://freesound.org/s/180948/) by jc144940
  - [Dripping water](https://freesound.org/s/770443/) by SpinOpel
  - [Water dripping under bridge in small town](https://freesound.org/s/442480/) by BonnyOrbit
  - [Water dripping after the rain](https://freesound.org/s/628404/) by xkeril
  - [gully with water drips](https://freesound.org/s/249927/) by launemax
  - [Water Dripping on Wood (off mic)](https://freesound.org/s/683783/) by Elements-Library
  - [Water Dripping on Thin Metal](https://freesound.org/s/683778/) by Elements-Library
  - [Rain, on leaves, close up, popping, brittle](https://freesound.org/s/577303/) by TRP
  - [Rain, light close drops on leaves](https://freesound.org/s/715698/) by TRP
  - [Thunder, close crack crash big](https://freesound.org/s/717907/) by TRP
  - [Thunder, close crack light rain](https://freesound.org/s/717909/) by TRP
  - [Thunder, pretty close crack](https://freesound.org/s/567945/) by TRP
  - [thunder](https://freesound.org/s/191992/) by pyer75
  - [Spectacular thunder clap](https://freesound.org/s/865693/) by Valerie-Vivegnis
  - [Loud Thunderclap](https://freesound.org/s/570351/) by JPBILLINGSLEYJR
  - [thunder-rain-middle-distance](https://freesound.org/s/197738/) by ragamuffin
  - [Distant rumbles](https://freesound.org/s/584946/) by richwise
  - [Distant Thunder 2](https://freesound.org/s/581123/) by Fission9
  - [peal of thunder - distant](https://freesound.org/s/243782/) by bastipictures
  - [Splashing Footsteps Shallow Water](https://freesound.org/s/861369/) by ChristopherJngs

<!-- more-mods:start -->
## More mods by Pocky

<table>
  <tr>
    <td align="center" width="112"><a href="https://www.curseforge.com/minecraft/mc-mods/turbo-for-distant-horizons"><img src="https://raw.githubusercontent.com/Pocky-l/dhturbo/main/docs/icon.png" width="96" alt="Turbo for Distant Horizons"></a></td>
    <td>
      <a href="https://www.curseforge.com/minecraft/mc-mods/turbo-for-distant-horizons"><b>Turbo for Distant Horizons</b></a><br>
      Distant Horizons addon: generates distant terrain from the world noise many times faster, with real trees nearby.<br>
      <a href="https://www.curseforge.com/minecraft/mc-mods/turbo-for-distant-horizons"><img alt="CurseForge" src="https://img.shields.io/curseforge/dt/1734835?logo=curseforge&label=CurseForge&color=F16436"></a>
      <a href="https://github.com/Pocky-l/dhturbo"><img alt="GitHub" src="https://img.shields.io/badge/GitHub-source-181717?logo=github"></a>
    </td>
  </tr>
  <tr>
    <td align="center" width="112"><a href="https://www.curseforge.com/minecraft/mc-mods/holy-staff"><img src="https://raw.githubusercontent.com/Pocky-l/holy-staff/main/docs/icon.png" width="96" alt="Holy Staff"></a></td>
    <td>
      <a href="https://www.curseforge.com/minecraft/mc-mods/holy-staff"><b>Holy Staff</b></a><br>
      A holy staff with three healing skills, aim previews and flying heal numbers.<br>
      <a href="https://www.curseforge.com/minecraft/mc-mods/holy-staff"><img alt="CurseForge" src="https://img.shields.io/curseforge/dt/1725465?logo=curseforge&label=CurseForge&color=F16436"></a>
      <a href="https://github.com/Pocky-l/holy-staff"><img alt="GitHub" src="https://img.shields.io/badge/GitHub-source-181717?logo=github"></a>
    </td>
  </tr>
  <tr>
    <td align="center" width="112"><a href="https://www.curseforge.com/minecraft/mc-mods/lumen-rigs"><img src="https://raw.githubusercontent.com/Pocky-l/lumen-rigs/main/docs/icon.png" width="96" alt="Lumen Rigs"></a></td>
    <td>
      <a href="https://www.curseforge.com/minecraft/mc-mods/lumen-rigs"><b>Lumen Rigs</b></a><br>
      Aimable spotlights, floodlights, searchlights and soft panels with colored light and visible beams.<br>
      <a href="https://www.curseforge.com/minecraft/mc-mods/lumen-rigs"><img alt="CurseForge" src="https://img.shields.io/curseforge/dt/1727739?logo=curseforge&label=CurseForge&color=F16436"></a>
      <a href="https://github.com/Pocky-l/lumen-rigs"><img alt="GitHub" src="https://img.shields.io/badge/GitHub-source-181717?logo=github"></a>
    </td>
  </tr>
  <tr>
    <td align="center" width="112"><a href="https://www.curseforge.com/minecraft/mc-mods/neon-glowsticks"><img src="https://raw.githubusercontent.com/Pocky-l/neon-glowsticks/main/docs/icon.png" width="96" alt="Neon Glowsticks"></a></td>
    <td>
      <a href="https://www.curseforge.com/minecraft/mc-mods/neon-glowsticks"><b>Neon Glowsticks</b></a><br>
      Throwable glowsticks that bounce, roll and light up the dark with colored light.<br>
      <a href="https://www.curseforge.com/minecraft/mc-mods/neon-glowsticks"><img alt="CurseForge" src="https://img.shields.io/curseforge/dt/1727688?logo=curseforge&label=CurseForge&color=F16436"></a>
      <a href="https://github.com/Pocky-l/neon-glowsticks"><img alt="GitHub" src="https://img.shields.io/badge/GitHub-source-181717?logo=github"></a>
    </td>
  </tr>
  <tr>
    <td align="center" width="112"><a href="https://www.curseforge.com/minecraft/mc-mods/rustling-leaves"><img src="https://raw.githubusercontent.com/Pocky-l/rustling-leaves/main/docs/icon.png" width="96" alt="Rustling Leaves"></a></td>
    <td>
      <a href="https://www.curseforge.com/minecraft/mc-mods/rustling-leaves"><b>Rustling Leaves</b></a><br>
      Physically simulated leaves: falling leaves, leaf piles you can wade through, rake and blow away, gusts, whirlwinds and leaf tools.<br>
      <a href="https://www.curseforge.com/minecraft/mc-mods/rustling-leaves"><img alt="CurseForge" src="https://img.shields.io/curseforge/dt/1729578?logo=curseforge&label=CurseForge&color=F16436"></a>
      <a href="https://github.com/Pocky-l/rustling-leaves"><img alt="GitHub" src="https://img.shields.io/badge/GitHub-source-181717?logo=github"></a>
    </td>
  </tr>
  <tr>
    <td align="center" width="112"><a href="https://www.curseforge.com/minecraft/mc-mods/ranchers-vacpack"><img src="https://raw.githubusercontent.com/Pocky-l/ranchers-vacpack/main/docs/icon.png" width="96" alt="Rancher's Vacpack"></a></td>
    <td>
      <a href="https://www.curseforge.com/minecraft/mc-mods/ranchers-vacpack"><b>Rancher's Vacpack</b></a><br>
      A Slime Rancher inspired vacuum gun: suck up items and small mobs, store them in a tank and shoot them back out.<br>
      <a href="https://www.curseforge.com/minecraft/mc-mods/ranchers-vacpack"><img alt="CurseForge" src="https://img.shields.io/curseforge/dt/1725381?logo=curseforge&label=CurseForge&color=F16436"></a>
      <a href="https://github.com/Pocky-l/ranchers-vacpack"><img alt="GitHub" src="https://img.shields.io/badge/GitHub-source-181717?logo=github"></a>
    </td>
  </tr>
</table>
<!-- more-mods:end -->

## License

[MIT](LICENSE)
