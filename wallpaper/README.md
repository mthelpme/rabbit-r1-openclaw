# Wallpapers

| File | Use |
|------|-----|
| `lockscreen.png` | Lock screen |
| `homescreen.png` | Home screen (inkOS) |

Both are 1086×1448 — a 3:4 aspect, the same as the R1's 480×640 reported panel, so they scale to
fit with no cropping and no letterboxing.

## Install

```sh
adb push wallpaper/lockscreen.png /sdcard/Pictures/
adb push wallpaper/homescreen.png /sdcard/Pictures/
```
Then on the R1: Files/Gallery → the image → **Set as wallpaper** → Lock screen / Home screen.

There is no supported adb command to set the *lock* wallpaper specifically, so the picker is the
reliable route.

## Relationship to tools/make-lock-wallpaper.py

[`tools/make-lock-wallpaper.py`](../tools/make-lock-wallpaper.py) composites `assets/mascot.png`
onto a flat background at panel resolution. It predates these and is now the fallback — useful for
generating a plain variant, or re-centring the mascot at a different scale. These hand-made
wallpapers are what the device actually runs.

## A note on the lockscreen art

The keyguard draws light text over this image with no scrim (the flat background the overlay was
originally tuned against made one unnecessary). Two spots to watch if you swap the art:

- the **top band**, where the centred clock and date sit
- the **bottom band**, where the indication text ("Charged") sits — `keyguard_indication_margin_bottom`
  is retuned to 16dp in [`apps/r1-lockscreen-overlay`](../apps/r1-lockscreen-overlay), which places
  it lower than stock

Keeping those two bands darker than the middle keeps the text legible.
