#!/bin/bash

# NordicFTMS Installer for Mac and Linux

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ADB="$SCRIPT_DIR/adb"
APK="$SCRIPT_DIR/NordicFTMS.apk"

# Verify ADB exists
if [ ! -f "$ADB" ]; then
    echo "Error: adb not found in $(dirname "$ADB")"
    echo "Make sure the adb binary is in the same folder as this script."
    exit 1
fi

# Verify APK exists
if [ ! -f "$APK" ]; then
    echo "Error: NordicFTMS.apk not found in $(dirname "$APK")"
    exit 1
fi

echo ""
echo "==================================="
echo "  NordicFTMS Installer"
echo "==================================="
echo ""
echo "Enter the IP address of your treadmill/bike."
echo "You can find this in Settings > Network on your machine."
echo ""
read -p "Device IP address: " DEVICE_IP

if [ -z "$DEVICE_IP" ]; then
    echo "Error: No IP address entered."
    exit 1
fi

# Connect to device
echo ""
echo "Connecting to $DEVICE_IP:5555..."
"$ADB" connect "$DEVICE_IP:5555"
if [ $? -ne 0 ]; then
    echo "Error: Could not connect to $DEVICE_IP:5555"
    echo "Make sure USB debugging is enabled and the device is on the same network."
    exit 1
fi

# Install APK
echo ""
echo "Installing NordicFTMS..."
"$ADB" install -r "$APK"
if [ $? -ne 0 ]; then
    echo "Error: Installation failed."
    exit 1
fi

# Launch the app once to enable auto-start on boot
echo ""
echo "Launching NordicFTMS..."
"$ADB" shell am start -n com.nordicftms.app/.MainActivity

echo ""
echo "==================================="
echo "  Installation complete!"
echo "==================================="
echo ""
echo "NordicFTMS is now running. It will auto-start on future reboots."
echo "Open your FTMS app and scan for Bluetooth devices."
echo ""
echo "Once you confirm the FTMS service appears in your device list,"
echo "reboot your treadmill/bike to return to the iFit screen."
echo ""
