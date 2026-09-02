# R1 Night Light Overlay

A **Runtime Resource Overlay (RRO)** against `framework-res` that lowers the night-light floor from
**2596K to 1000K**, so the built-in slider can reach a genuine orange instead of stopping at warm
white.

Resource-only: one integer. No code, no Xposed, no custom app.

## Why the stock night light can't get orange

`ColorDisplayService` clamps the colour temperature to `config_nightDisplayColorTemperatureMin`.
On this GSI:

```
config_nightDisplayColorTemperatureMin      2596
config_nightDisplayColorTemperatureMax      4082
config_nightDisplayColorTemperatureDefault  2850
```

2596K is warm *white*. Verified on-device: writing `settings put secure
night_display_color_temperature 1200` stores 1200, but `dumpsys color_display` still reports
`Color temp: 2596`. The clamp is enforced on read, so no amount of settings-poking helps.

## Why 1000K

The RGB scale is a per-channel quadratic in Kelvin, from
`config_nightDisplayColorTemperatureCoefficients`:

```
R = 1.0                                                      (constant)
G = -9.62353339e-9·x² + 1.53045476e-4·x + 0.390782778
B = -1.89359041e-8·x² + 3.02412211e-4·x − 0.198650895
```

| Temp | R | G | B | |
|---|---|---|---|---|
| 2596K | 1.00 | 0.72 | 0.46 | warm white — the stock floor |
| 1500K | 1.00 | 0.60 | 0.21 | warm orange |
| **1000K** | 1.00 | 0.53 | 0.08 | **strong orange — the new floor** |
| 686K | 1.00 | 0.47 | 0.00 | blue crosses zero; below this the fit is meaningless |

1000K stays inside the range where the stock polynomial is still well-behaved. The slider is
otherwise untouched — this only extends how far down it goes. Pick your warmth in
**Settings → Display → Night Light** as normal.

## Gotcha: needs TWO reboots to take effect

`ColorDisplayService` reads this config once, when `system_server` starts. The Magisk module's
`service.sh` enables the overlay ~8s **after** boot completes — by which point the service has
already cached the old floor. So:

- **First boot after flashing:** overlay enabled, but still clamped at 2596K
- **Second boot:** overlay is already enabled from the start, `system_server` reads 1000K

This differs from the SystemUI overlays in this repo, which take effect on the first boot because
SystemUI re-reads its resources. Any framework overlay that changes something `system_server`
caches at construction will behave this way.

## Build

```sh
tools/recon-lockscreen.sh          # with the R1 attached — enables name validation
./build.sh                         # → ../../magisk-modules/nightlight-overlay/system/product/overlay/
../../magisk-modules/build-all.sh  # → magisk-modules/dist/nightlight-overlay.zip
```

Flash in Magisk → reboot **twice**.

## Recovery

```sh
adb shell su -c "cmd overlay disable com.r1nightlight.overlay"
adb shell settings put secure night_display_color_temperature 2596
```

The module's `uninstall.sh` does both, so the slider isn't left stuck off-scale after removal.

## Why not a night-light app?

The obvious alternative — a `SYSTEM_ALERT_WINDOW` app painting a translucent orange layer over
everything — is worse in every way that matters. It is a colour *wash*: it crushes contrast, tints
screenshots, sits above some system surfaces and below others, and costs a permanent overlay
window. The RRO reuses Android's real colour transform, applied at composition, so it behaves
exactly like the stock feature because it *is* the stock feature.
