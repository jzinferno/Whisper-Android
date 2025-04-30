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
            CC=$(which armv7a-linux-androideabi21-clang)
            CXX=$(which armv7a-linux-androideabi21-clang++)
            FFMPEG_ARCH=arm
            FFMPEG_FLAGS=""
            ;;
        arm64-v8a)
            CC=$(which aarch64-linux-android21-clang)
            CXX=$(which aarch64-linux-android21-clang++)
            FFMPEG_ARCH=aarch64
            FFMPEG_FLAGS=""
            ;;
        x86_64)
            CC=$(which x86_64-linux-android21-clang)
            CXX=$(which x86_64-linux-android21-clang++)
            FFMPEG_ARCH=x86_64
            FFMPEG_FLAGS="--x86asmexe=yasm"
            ;;
        x86)
            CC=$(which i686-linux-android21-clang)
            CXX=$(which i686-linux-android21-clang++)
            FFMPEG_ARCH=i686
            FFMPEG_FLAGS="--disable-asm"
            ;;
    esac

    WORKDIR=$SCRIPTS_DIR/build_whisper/$arch
    mkdir -p $WORKDIR

    # FFmpeg
    cd $WORKDIR
    if [ ! -f ffmpeg-7.1.1.tar.xz ]; then
        wget https://www.ffmpeg.org/releases/ffmpeg-7.1.1.tar.xz
    fi
    rm -rf ffmpeg-7.1.1 && tar -xf ffmpeg-7.1.1.tar.xz && cd $WORKDIR/ffmpeg-7.1.1
    ./configure \
      --prefix=$WORKDIR/local \
      --enable-cross-compile \
      --target-os=android \
      --arch=$FFMPEG_ARCH \
      --sysroot=$(realpath "$(dirname `which lld`)"/../sysroot) \
      --cc=$CC \
      --cxx=$CXX \
      --ld=$CC \
      --as=$CC \
      --ar=$(which llvm-ar) \
      --nm=$(which llvm-nm) \
      --ranlib=$(which llvm-ranlib) \
      --strip=$(which llvm-strip) \
      --extra-cflags="-w -Oz -fPIC -fno-asynchronous-unwind-tables -fno-exceptions -ffunction-sections -fdata-sections" \
      --extra-ldflags="-Wl,-z,max-page-size=16384 -Wl,--gc-sections" \
      --disable-network \
      --disable-vulkan \
      --disable-debug \
      --disable-logging \
      --disable-programs \
      --disable-doc \
      --enable-lto \
      --enable-small \
      --pkg-config=/usr/bin/pkg-config \
      $FFMPEG_FLAGS
    make clean
    make -j$(nproc)
    make install

    # Whisper.cpp
    cd $WORKDIR
    git clone --depth=1 https://github.com/ggml-org/whisper.cpp
    cd $WORKDIR/whisper.cpp
    cmake -B build \
        -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake \
        -DANDROID_ABI=$arch \
        -DANDROID_PLATFORM=android-21 \
        -DBUILD_SHARED_LIBS=OFF \
        -DWHISPER_FFMPEG=ON \
        -DFFMPEG_LIBRARIES="$WORKDIR/local/lib/libavformat.a;$WORKDIR/local/lib/libavcodec.a;$WORKDIR/local/lib/libavutil.a;$WORKDIR/local/lib/libswresample.a" \
        -DFFMPEG_INCLUDE_DIRS=$WORKDIR/local/include \
        -DAVFORMAT_LIBRARIES=$WORKDIR/local/lib/libavformat.a \
        -DAVCODEC_LIBRARIES=$WORKDIR/local/lib/libavcodec.a \
        -DAVUTIL_LIBRARIES=$WORKDIR/local/lib/libavutil.a \
        -DSWRESAMPLE_LIBRARIES=$WORKDIR/local/lib/libswresample.a \
        -DAVFORMAT_INCLUDE_DIRS=$WORKDIR/local/include \
        -DAVCODEC_INCLUDE_DIRS=$WORKDIR/local/include \
        -DAVUTIL_INCLUDE_DIRS=$WORKDIR/local/include \
        -DSWRESAMPLE_INCLUDE_DIRS=$WORKDIR/local/include \
        -DCMAKE_EXE_LINKER_FLAGS="-lz"
    grep -rl "libomp.so" build | xargs sed -i 's/libomp.so/libomp.a/g'
    cmake --build build --config Release -j$(nproc)
    llvm-strip build/bin/whisper-cli
    mkdir -p $SCRIPTS_DIR/jniLibs/$arch
    cp $WORKDIR/whisper.cpp/build/bin/whisper-cli $SCRIPTS_DIR/jniLibs/$arch/libwhisper.so

done
