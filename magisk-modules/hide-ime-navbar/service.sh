#!/system/bin/sh
# Wait until boot completes and OverlayManager is ready, then apply.
( until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
  sleep 6
  cmd overlay fabricate --target android --name HideImeNavBar \
      android:bool/config_imeDrawsImeNavBar 0x12 0x0
  cmd overlay enable com.android.shell:HideImeNavBar ) &
