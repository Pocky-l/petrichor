# Changelog

All notable changes to this mod are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.1+1.20.1] - 2026-10-08
### Changed
- Ported to Minecraft 1.20.1 (Forge). Everything of 1.0.1 for Minecraft 1.21.1 is included.
- The config editor (*Mods -> Petrichor: Rain & Storms -> Config*) is a simple screen of the mod's own, since Forge for
  1.20.1 has none: switches for options, text fields for numbers (shown in red while a number is out of range).
- Shader packs work through [Oculus](https://www.curseforge.com/minecraft/mc-mods/oculus).

## [1.0.1] - 2026-10-07
### Added
- Under water the rain, drips and thunder above sound dull and faint.
### Fixed
- Lightning no longer flashes the screen underground or deep indoors: flashes, the glare of close strikes and the
  darkening before a strike are only seen where the sky can be seen, and fade in and out at cave mouths and windows.
- The rain's haze, grey fog and curtains of rain in the middle distance no longer fill caves and enclosed rooms
  during rain; the curtains now stand on the ground the rain reaches and follow the land.
- Wind, distant rain and thunder are no longer heard underground, and rain is no longer heard through the rock of a
  cave. Walking out of a cave, the sounds of the storm rise smoothly from silence, the wind blowing in from the exit.
  Buildings keep their sound.

## [1.0.0] - 2026-10-06
### Added
- Rain types (drizzle, rain, downpour, thunderstorm) that blend into each other, with gusts and wind.
- Drops with depth, curtains of rain sweeping past in the distance, overcast light.
- Rainy air: layered haze over the distance, drifting showers, an overcast sky with rain shafts on the horizon.
- Snowy and dry land keeps the vanilla weather.
- Splashes, rings on water, spray from leaves and mobs, mist in heavy rain.
- Slanted rain does not pass through walls: lee sides stay dry.
- Cinematic storm: the view darkens before a strike, close flashes overexpose it and leave an afterimage, drops freeze
  in the flash.
- Puddles with reflections and ripples, wet and drying ground, water running off edges and dripping from roofs and leaves.
- A rain soundscape placed in the world: every surface (earth, stone, wood, metal, glass, wool, puddles, water) sounds
  like itself, light and heavy rain sound different, roofs drum by material, rain beats on windows, drops fall,
  and rain behind walls sounds muffled.
- Branching lightning with delayed thunder recorded at different distances, muffled indoors.
- Works with shader packs ([Iris](https://modrinth.com/mod/iris)): the rain is drawn through the pack's weather program and puddles
  as the pack's water; the mod's haze gives way to the pack's own.
