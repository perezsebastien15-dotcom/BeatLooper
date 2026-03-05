@echo off
"C:\\Program Files\\Android\\Android Studio\\jbr\\bin\\java" ^
  --class-path ^
  "C:\\Users\\seperez1\\.gradle\\caches\\modules-2\\files-2.1\\com.google.prefab\\cli\\2.0.0\\f2702b5ca13df54e3ca92f29d6b403fb6285d8df\\cli-2.0.0-all.jar" ^
  com.google.prefab.cli.AppKt ^
  --build-system ^
  cmake ^
  --platform ^
  android ^
  --abi ^
  x86_64 ^
  --os-version ^
  27 ^
  --stl ^
  c++_shared ^
  --ndk-version ^
  25 ^
  --output ^
  "C:\\Users\\seperez1\\AppData\\Local\\Temp\\agp-prefab-staging10533165147572846071\\staged-cli-output" ^
  "C:\\Users\\seperez1\\.gradle\\caches\\transforms-3\\8ea5b5a13cb34a46b429b09026a3ae16\\transformed\\oboe-1.10.0\\prefab"
