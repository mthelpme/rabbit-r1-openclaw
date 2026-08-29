#!/system/bin/sh
# Restore the status bar. The setting is persistent, so if this doesn't take at removal time
# (settings provider not up yet), run manually once after reboot:
#   settings delete global policy_control
settings delete global policy_control 2>/dev/null
