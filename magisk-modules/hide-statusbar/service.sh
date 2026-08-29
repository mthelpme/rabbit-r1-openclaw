#!/system/bin/sh
# Hide the status bar globally via immersive policy. It stays hidden; swiping down from the
# top edge reveals it transiently. The setting is persistent, so this is belt-and-suspenders
# to re-assert it each boot in case something resets policy_control.
( until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
  sleep 6
  for i in 1 2 3 4 5; do
      settings put global policy_control 'immersive.status=*' && break
      sleep 3
  done ) &
