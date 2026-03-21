#!/bin/bash

# NordicFTMS Uninstaller for Mac and Linux

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ADB="$SCRIPT_DIR/adb"

# Verify ADB exists
if [ ! -f "$ADB" ]; then
    echo "Error: adb not found in $(dirname "$ADB")"
    echo "Make sure the adb binary is in the same folder as this script."
    exit 1
fi

echo ""
echo "==================================="
echo "  NordicFTMS Uninstaller"
echo "==================================="
echo ""
echo "Enter the IP address of your treadmill/bike."
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

# Stop the service
echo ""
echo "Stopping NordicFTMS..."
"$ADB" shell am force-stop com.nordicftms.app

# Uninstall
echo ""
echo "Uninstalling NordicFTMS..."
"$ADB" uninstall com.nordicftms.app
if [ $? -ne 0 ]; then
    echo "Error: Uninstall failed. NordicFTMS may not be installed."
    exit 1
fi

echo ""
echo "==================================="
echo "  Uninstall complete!"
echo "==================================="
echo ""
echo "NordicFTMS has been removed from your device."
echo ""
