#!/bin/bash
# Build already done; export APK to device with version in filename.
set -e
cd /root/dsh-pet-android
VER=$(grep -o 'versionName="[^"]*"' app/src/main/AndroidManifest.xml | head -1 | cut -d'"' -f2)
OUT="pet-$VER.apk"
cp build/pet.apk "build/$OUT"
curl -s -H @/root/.dsh/.bridge_headers "http://127.0.0.1:3090/app/export?path=$PWD/build/$OUT" >/dev/null
echo "exported $OUT"
