#!/system/bin/sh
# Enable the framework RRO once the package manager is up.
( until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
  sleep 8
  for i in 1 2 3 4 5; do
      cmd overlay enable com.r1nightlight.overlay && break
      sleep 3
  done ) &
