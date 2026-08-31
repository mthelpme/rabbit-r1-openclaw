#!/system/bin/sh
# Enable the lockscreen RROs once the package manager is up, then apply the settings-level
# cleanups no overlay can do. Mirrors magisk-modules/hide-ime-navbar's approach.
( until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
  sleep 8

  # Both overlays ship in /system/product/overlay, so they are trusted system overlays; they
  # still have to be enabled explicitly because neither is static.
  #   com.r1lockscreen.overlay  -> com.android.systemui  (clock size, gutters)
  #   com.r1lockscreen.pattern  -> android               (pattern dots + colours)
  # Enabled separately so either can be turned off on its own.
  for ov in com.r1lockscreen.overlay com.r1lockscreen.pattern; do
      for i in 1 2 3 4 5; do
          cmd overlay enable "$ov" && break
          sleep 3
      done
  done

  # Declutter the lockscreen. Reverted by uninstall.sh.
  settings put secure lock_screen_show_notifications 0 ) &
