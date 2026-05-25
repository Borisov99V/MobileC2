#!/bin/bash

if [ -z "$1" ]; then
  echo "Usage: ./build_apk.sh <SERVER_IP>"
  echo "Example: ./build_apk.sh 10.125.150.103"
  exit 1
fi

SERVER_IP=$1
ROOT_DIR=$(pwd)
MAIN_FILE="$ROOT_DIR/agent-android/app/src/main/java/com/example/c2agent/MainActivity.kt"
APK_OUT="$ROOT_DIR/agent-android/app/build/outputs/apk/debug/app-debug.apk"

echo "[*] Setting server IP to $SERVER_IP..."
sed -i "s|http://[0-9.]*:8000/|http://$SERVER_IP:8000/|g" $MAIN_FILE

echo "[*] Building APK..."
cd agent-android && ./gradlew assembleDebug
BUILD_STATUS=$?
cd $ROOT_DIR

if [ $BUILD_STATUS -eq 0 ]; then
  echo "[+] Build successful!"
  cp $APK_OUT .
  echo "[+] APK copied to $ROOT_DIR/app-debug.apk"
else
  echo "[-] Build failed"
  exit 1
fi