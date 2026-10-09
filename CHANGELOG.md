# Changelog

All notable changes to this mod are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.2.0] - 2026-10-10
### Added
- Sun showers: some light rains in daytime fall while the sun keeps shining - the sky stays blue, the light bright and
  the drops glint. Their chance can be set with the new "Sun shower chance" option.
- Rainbows: a rainbow with a fainter second bow appears opposite the sun in every sun shower and sometimes when a
  rain clears up in daylight. Can be turned off with the new "Rainbows" option.
- `/petrichor weather sun_shower` starts a sun shower.
- A lightning strike right next to you leaves you half deaf for a moment: the rain goes faint and dull and comes back
  over a few seconds. Can be turned off with the new "Deafening close strikes" option.
### Changed
- The rain builds up and eases off gradually: every rain starts as a light drizzle, grows step by step into rain, a
  downpour or a thunderstorm over about a minute, and dies down to a drizzle again before it stops. Changes of the rain
  type during a long rain pass through the steps in between too, and the extra lightning strikes come as the
  thunderstorm builds up. How fast the rain changes is set by the new "Minutes per change of rain" option.
- `/petrichor status` shows how far the rain has built up.
- Each kind of rain now matches real rain: the number of drops, their size and how fast they fall follow measurements of
  real light rain, rain, downpours and thunderstorms. Light rain no longer drifts down slowly with more drops than
  normal rain; a thunderstorm is the heaviest rain. Puddles fill as fast as the rain looks: slowly in a drizzle,
  quickly in a downpour or a storm.
### Fixed
- Lightning bolts stopped in mid-air and never reached the ground, so a strike right next to the player showed no
  bolt at all. The whole channel is drawn now, down to the ground.
- A lightning strike close to the player had no thunderclap, only a distant-sounding rumble: the crack of the
  vanilla strike was replaced with a recording of a far one. A strike nearby now sounds like the real thing - a
  deafening bang the moment the sound arrives, a long tearing crash and then the roll of the rest of the bolt, as loud
  as the vanilla strike (built on recordings made within 100 metres of real strikes).

## [1.1.0] - 2026-10-09
### Added
- Rain hisses on hot blocks: drops falling on lava, magma blocks and lit campfires boil away in small puffs of steam
  with a quiet sizzle, and a lava lake steams faintly in the rain. Can be turned off with the new "Steam on hot blocks"
  option.
### Fixed
- The rainy haze and sky work correctly with [Veil](https://www.curseforge.com/minecraft/mc-mods/veil-lib) installed.

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
