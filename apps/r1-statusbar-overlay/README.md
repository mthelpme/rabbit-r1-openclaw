# R1 Status Bar Overlay

A **Runtime Resource Overlay (RRO)** that blanks the mobile **data-type indicator** — the `LTE`,
`5G`, `H+` label next to the signal bars — while leaving the bars and the roaming indicator alone.

Resource-only: no code, no Xposed, no hooks.

## How

SystemUI draws the data-type label from 16 vector drawables (`ic_lte_mobiledata`,
`ic_5g_mobiledata`, `ic_h_plus_mobiledata`, …). The overlay replaces each with a 1dp vector
containing no paths, so nothing is drawn.

Replacing an existing drawable is squarely what an RRO is for — unlike *adding* one, which it
cannot do.

### Why not `icon_blacklist`

`Settings.Secure.icon_blacklist` hides whole status-bar **slots**, and the only slot covering this
is `mobile` — which takes the signal bars with it. There is no separate slot for the data-type
label, and this build exposes no bool for it either (checked: the only related resource is
`config_hspa_data_distinguishable`, which merely distinguishes H from H+).

### Safety

All 16 originals were confirmed to be plain `<vector>` with `aapt2 dump xmltree` against the
device's own `SystemUI.apk`. That matters: had any been an `<animated-vector>`, replacing it with a
static vector could throw where SystemUI casts to `AnimatedVectorDrawable`.

## Build

```sh
tools/recon-lockscreen.sh          # with the R1 attached — enables name validation
./build.sh                         # → ../../magisk-modules/statusbar-overlay/system/product/overlay/
../../magisk-modules/build-all.sh  # → magisk-modules/dist/statusbar-overlay.zip
```

`build.sh` checks every drawable filename against the recon dump and prints `ok` / `MISS`, so a
name that doesn't exist in your build is caught at build time rather than silently ignored at
runtime.

Flash the zip in Magisk → reboot.

## Recovery

```sh
adb shell su -c "cmd overlay disable com.r1statusbar.overlay"
```

## Verified on device

LineageOS `21.0-20250621-UNOFFICIAL-arm64_bvN`. All 16 drawables validate `ok`; after a cold boot
the `LTE` label is absent while the signal bars and the roaming `R` remain.
