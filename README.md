## Build [whisper-cli](https://github.com/ggml-org/whisper.cpp/tree/2a2d21c75d069221a5dfd8828eed44a1699861a7) with Android NDK r27c

```bash
export PATH=~/Android/Sdk/cmake/3.22.1/bin:~/Android/Sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH

for abi in arm64-v8a armeabi-v7a x86_64 x86; do
    mkdir -p jniLibs/$abi
    cmake -DCMAKE_TOOLCHAIN_FILE=~/Android/Sdk/ndk/27.2.12479018/build/cmake/android.toolchain.cmake -DANDROID_ABI=$abi -DANDROID_PLATFORM=android-21 -DBUILD_SHARED_LIBS=OFF -B build
    grep -rl "libomp.so" build | xargs sed -i 's/libomp.so/libomp.a/g'
    cmake --build build --config Release -j$(nproc)
    llvm-strip build/bin/whisper-cli
    mv build/bin/whisper-cli jniLibs/$abi/libwhisper.so
    rm -rf build
done
```

## Build [vosk-cli](https://github.com/alphacep/vosk-api/tree/4bf3370826d32a21728cc9aa7d32364ad036ea73) with Android NDK r27c

```bash
export ANDROID_NDK_HOME=/home/jzinferno/Android/Sdk/ndk/27.2.12479018
export PATH=~/Android/Sdk/cmake/3.22.1/bin:$PATH

# patch -p1 < .../vosk-api.patch
cd android/lib
./build-vosk.sh
```
