#!/system/bin/sh
cmd overlay disable com.r1nightlight.overlay 2>/dev/null
# Put the temperature back inside the stock range so the slider isn't stuck off-scale.
settings put secure night_display_color_temperature 2596 2>/dev/null
