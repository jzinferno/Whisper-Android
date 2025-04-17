#!/bin/bash

if [ -z "$ANDROID_NDK_HOME" ]; then
    echo "ANDROID_NDK_HOME is not set!"
    exit 1
fi

set -x

PATH=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH
SCRIPTS_DIR=$(realpath `dirname $0`)

for arch in armeabi-v7a arm64-v8a x86_64 x86; do
    case $arch in
        armeabi-v7a)
            BLAS_ARCH=ARMV7
            CC=armv7a-linux-androideabi21-clang
            AR=llvm-ar
            ;;
        arm64-v8a)
            BLAS_ARCH=CORTEXA57
            CC=aarch64-linux-android21-clang
            AR=llvm-ar
            ;;
        x86_64)
            BLAS_ARCH=ATOM
            CC=x86_64-linux-android21-clang
            AR=llvm-ar
            ;;
        x86)
            BLAS_ARCH=ATOM
            CC=i686-linux-android21-clang
            AR=llvm-ar
            ;;
    esac

    WORKDIR=$SCRIPTS_DIR/build_whisper/$arch
    mkdir -p $WORKDIR && cd $WORKDIR

    # OpenBLAS
    git clone --depth=1 https://github.com/OpenMathLib/OpenBLAS -b v0.3.29
    make -C OpenBLAS TARGET=$BLAS_ARCH ONLY_CBLAS=1 AR=$AR CC=$CC HOSTCC=gcc ARM_SOFTFP_ABI=1 USE_THREAD=0 NUM_THREADS=1 NO_SHARED=1 -j$(nproc)
    make -C OpenBLAS install PREFIX=$WORKDIR/local NO_SHARED=1


    # Whisper.cpp
    git clone --depth=1 https://github.com/ggml-org/whisper.cpp -b v1.7.5
    cd $WORKDIR/whisper.cpp
    cmake -B build \
        -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake \
        -DANDROID_ABI=$arch \
        -DANDROID_PLATFORM=android-21 \
        -DBUILD_SHARED_LIBS=OFF \
        -DGGML_BLAS=ON \
        -DGGML_BLAS_VENDOR=OpenBLAS \
        -DBLAS_INCLUDE_DIRS=$WORKDIR/local/include \
        -DBLAS_LIBRARIES=$WORKDIR/local/lib/libopenblas.a
    grep -rl "libomp.so" build | xargs sed -i 's/libomp.so/libomp.a/g'
    cmake --build build --config Release -j$(nproc)
    llvm-strip build/bin/whisper-cli
    mkdir -p $SCRIPTS_DIR/jniLibs/$arch
    cp $WORKDIR/whisper.cpp/build/bin/whisper-cli $SCRIPTS_DIR/jniLibs/$arch/libwhisper.so

done
