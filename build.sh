#!/bin/bash
set -e
cd /root/dsh-pet-android
AJ=/opt/android/android-34/android.jar
D8JAR=/opt/android/android-14/lib/d8.jar
rm -rf build && mkdir -p build/classes build/gen build/dex

# 0. regenerate pet.html (animation list + work-status mapping)
python3 scripts/gen_html.py

# 1. resources + assets -> base.apk, and R.java
aapt package -f -m -J build/gen -M app/src/main/AndroidManifest.xml \
  -S app/src/main/res -I "$AJ" --min-sdk-version 26 --target-sdk-version 34
aapt package -f -M app/src/main/AndroidManifest.xml \
  -S app/src/main/res -I "$AJ" -A app/src/main/assets \
  --min-sdk-version 26 --target-sdk-version 34 -F build/base.apk

# 2. java -> class -> dex
javac -encoding UTF-8 -source 1.8 -target 1.8 -classpath "$AJ" \
  -d build/classes app/src/main/java/com/dsha/pet/*.java build/gen/com/dsha/pet/R.java
java -cp "$D8JAR" com.android.tools.r8.D8 --release --min-api 26 \
  --lib "$AJ" --output build/dex build/classes/com/dsha/pet/*.class

# 3. dex into apk, align, sign
cd build
cp base.apk unsigned.apk
(cd dex && zip -0 -u ../unsigned.apk classes.dex >/dev/null)
zipalign -f 4 unsigned.apk aligned.apk
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -alias debug \
  -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android \
  -dname "CN=Pet, OU=Dev, O=DSHA, L=CN, ST=CN, C=CN" >/dev/null 2>&1
apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out pet.apk aligned.apk
apksigner verify pet.apk
ls -la pet.apk
