#!/system/bin/sh
cmd overlay disable com.r1lockscreen.overlay 2>/dev/null
cmd overlay disable com.r1lockscreen.pattern 2>/dev/null
settings put secure lock_screen_show_notifications 1 2>/dev/null
