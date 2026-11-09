# Vanilla-look reference art

`generic_54.png` is a 256 × 256 sheet with the 176 × 222 six-row chest screen at its top-left,
drawn to match the vanilla client's container texture (rounded corners cut by transparent pixels).
It is the project owner's own artwork, contributed with the rights to use it in Materione; it is
not a copy of Mojang's asset.

The GUI studio draws it under every chest GUI as the screen the player's client shows
(docs/architecture/gui.md §11). It is reference art for the editor only: the plugin never ships it
in a resource pack, because the client already draws its own screens.

## How a container gets art

A container's profile (`shared/gui/containers.json`, gui.md §12.5.1) lists the blits that compose its
screen: `{ sheet, src, dst }`, where `sheet` is a file name here without `.png` (`generic_54` for the
chests). The studio loads every PNG in this folder and uses a profile's art when all of its sheets exist.
A container with no sheet here, today the `hopper` and the `dispenser`, is drawn as a generated
diagram (frame, slot wells, player inventory block) under a note saying so. To give one art, draw the
sheet yourself (never copy Mojang's files), name it as the profile's blit does, and add its blit to the
profile; no studio code changes.
