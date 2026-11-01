# SDP identity hook regression tests

This standalone Android executable exercises the production hook with a fake
Bluetooth registration function. It does not install hooks or change Bluetooth
settings. Tests cover both preference states, exactly one registration per call,
all return statuses, output handles, null arguments, and caller record ownership.

From the `android` directory, with the Android SDK's CMake/Ninja and `adb` on PATH
and `ANDROID_NDK_HOME` pointing to the installed NDK:

```sh
cmake -S app/src/main/cpp -B build/native-tests -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-33 \
  -DANDROID_STL=c++_static -DLIBREPODS_BUILD_NATIVE_TESTS=ON
cmake --build build/native-tests --target sdp_identity_hook_test
adb push build/native-tests/sdp_identity_hook_test /data/local/tmp/librepods-sdp-identity-test
adb shell /data/local/tmp/librepods-sdp-identity-test
adb shell rm /data/local/tmp/librepods-sdp-identity-test
```

Select the ABI and ADB device/server for the test device as needed. A passing run
prints `8/8 tests passed; 0 failed assertions` and exits with status zero. The
optional test target is disabled in normal APK builds.
