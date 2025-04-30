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
            CC=$(which armv7a-linux-androideabi23-clang)
            CXX=$(which armv7a-linux-androideabi23-clang++)
            HOST=arm-linux-androideabi
            ARCHFLAGS="-mfloat-abi=softfp -mfpu=neon"
            FFMPEG_ARCH=arm
            FFMPEG_FLAGS=""
            ;;
        arm64-v8a)
            BLAS_ARCH=CORTEXA57
            CC=$(which aarch64-linux-android23-clang)
            CXX=$(which aarch64-linux-android23-clang++)
            HOST=aarch64-linux-android
            ARCHFLAGS=""
            FFMPEG_ARCH=aarch64
            FFMPEG_FLAGS=""
            ;;
        x86_64)
            BLAS_ARCH=ATOM
            CC=$(which x86_64-linux-android23-clang)
            CXX=$(which x86_64-linux-android23-clang++)
            HOST=x86_64-linux-android
            ARCHFLAGS=""
            FFMPEG_ARCH=x86_64
            FFMPEG_FLAGS="--x86asmexe=yasm"
            ;;
        x86)
            BLAS_ARCH=ATOM
            CC=$(which i686-linux-android23-clang)
            CXX=$(which i686-linux-android23-clang++)
            HOST=i686-linux-android
            ARCHFLAGS=""
            FFMPEG_ARCH=i686
            FFMPEG_FLAGS="--disable-asm"
            ;;
    esac

    WORKDIR=$SCRIPTS_DIR/build_vosk/$arch
    mkdir -p $WORKDIR

     # OpenBLAS
    cd $WORKDIR
    git clone --depth=1 https://github.com/OpenMathLib/OpenBLAS -b v0.3.20
    make -C OpenBLAS TARGET=$BLAS_ARCH ONLY_CBLAS=1 AR=$(which llvm-ar) CC=$CC HOSTCC=gcc ARM_SOFTFP_ABI=1 USE_THREAD=0 NUM_THREADS=1 NO_SHARED=1 -j$(nproc)
    make -C OpenBLAS install PREFIX=$WORKDIR/local NO_SHARED=1

    # CLAPACK
    cd $WORKDIR
    git clone --depth=1 https://github.com/alphacep/clapack -b v3.2.1
    mkdir -p clapack/BUILD && cd clapack/BUILD
    cmake -DCMAKE_C_FLAGS="$ARCHFLAGS -Wno-deprecated-non-prototype -Wno-shift-op-parentheses" -DCMAKE_C_COMPILER_TARGET=$HOST \
      -DCMAKE_C_COMPILER=$CC -DCMAKE_SYSTEM_NAME=Generic -DCMAKE_AR=$(which llvm-ar) \
      -DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY \
      -DCMAKE_CROSSCOMPILING=True ..
    make -C F2CLIBS/libf2c -j$(nproc)
    make -C BLAS/SRC -j$(nproc)
    make -C SRC -j$(nproc)
    find . -name "*.a" | xargs cp -t $WORKDIR/local/lib

    # Openfst
    cd $WORKDIR
    git clone --depth=1 https://github.com/alphacep/openfst
    cd openfst && autoreconf -i
    CXX=$CXX CXXFLAGS="$ARCHFLAGS -O3 -DFST_NO_DYNAMIC_LINKING" ./configure --prefix=${WORKDIR}/local \
      --enable-shared --enable-static --with-pic --disable-bin \
      --enable-lookahead-fsts --enable-ngram-fsts --host=$HOST --build=$(uname -m)-linux-gnu
    make -j$(nproc)
    make install

    # Kaldi
    cd $WORKDIR
    git clone --depth=1 https://github.com/alphacep/kaldi -b vosk-android
    cd $WORKDIR/kaldi/src
    CXX=$CXX AR=$(which llvm-ar) RANLIB=$(which llvm-ranlib) CXXFLAGS="$ARCHFLAGS -O3 -DFST_NO_DYNAMIC_LINKING -Wno-unused-but-set-variable" ./configure --use-cuda=no \
      --mathlib=OPENBLAS_CLAPACK --shared \
      --android-incdir=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include \
      --host=$HOST --openblas-root=${WORKDIR}/local \
      --fst-root=${WORKDIR}/local --fst-version=1.8.0
    make depend -j$(nproc)
    cd $WORKDIR/kaldi/src
    make online2 rnnlm -j$(nproc)

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

    # Vosk-api
    cd $WORKDIR
    git clone --depth=1 https://github.com/alphacep/vosk-api
    cd ${WORKDIR}/vosk-api && patch -p1 < $SCRIPTS_DIR/../patches/vosk.patch
    make -j$(nproc) -C ${WORKDIR}/vosk-api/src \
      KALDI_ROOT=${WORKDIR}/kaldi \
      OPENFST_ROOT=${WORKDIR}/local \
      OPENBLAS_ROOT=${WORKDIR}/local \
      FFMPEG_ROOT=${WORKDIR}/local \
      CXX=$CXX \
      vosk-cli

    mkdir -p $SCRIPTS_DIR/jniLibs/$arch
    cp $WORKDIR/vosk-api/src/vosk-cli $SCRIPTS_DIR/jniLibs/$arch/libvosk.so

done
