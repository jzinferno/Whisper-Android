#!/bin/bash

if [ -z "$ANDROID_NDK_HOME" ]; then
    echo "ANDROID_NDK_HOME is not set!"
    exit 1
fi

set -x

PATH=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH
SCRIPTS_DIR=$(realpath `dirname $0`)

for arch in arm64-v8a; do #armeabi-v7a arm64-v8a x86_64 x86; do
    case $arch in
        armeabi-v7a)
            BLAS_ARCH=ARMV7
            CC=armv7a-linux-androideabi23-clang
            AR=llvm-ar
            HOST=arm-linux-androideabi
            RANLIB=llvm-ranlib
            CXX=armv7a-linux-androideabi23-clang++
            ARCHFLAGS="-mfloat-abi=softfp -mfpu=neon"
            ;;
        arm64-v8a)
            BLAS_ARCH=CORTEXA57
            CC=aarch64-linux-android23-clang
            AR=llvm-ar
            HOST=aarch64-linux-android
            RANLIB=llvm-ranlib
            CXX=aarch64-linux-android23-clang++
            ARCHFLAGS=""
            ;;
        x86_64)
            BLAS_ARCH=ATOM
            CC=x86_64-linux-android23-clang
            AR=llvm-ar
            HOST=x86_64-linux-android
            RANLIB=llvm-ranlib
            CXX=x86_64-linux-android23-clang++
            ARCHFLAGS=""
            ;;
        x86)
            BLAS_ARCH=ATOM
            CC=i686-linux-android23-clang
            AR=llvm-ar
            HOST=i686-linux-android
            RANLIB=llvm-ranlib
            CXX=i686-linux-android23-clang++
            ARCHFLAGS=""
            ;;
    esac

    WORKDIR=$SCRIPTS_DIR/build_vosk/$arch
    mkdir -p $WORKDIR

     # OpenBLAS
    cd $WORKDIR
    git clone --depth=1 https://github.com/OpenMathLib/OpenBLAS -b v0.3.20
    make -C OpenBLAS TARGET=$BLAS_ARCH ONLY_CBLAS=1 AR=$AR CC=$CC HOSTCC=gcc ARM_SOFTFP_ABI=1 USE_THREAD=0 NUM_THREADS=1 NO_SHARED=1 -j$(nproc)
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
    CXX=$CXX AR=$AR RANLIB=$RANLIB CXXFLAGS="$ARCHFLAGS -O3 -DFST_NO_DYNAMIC_LINKING -Wno-unused-but-set-variable" ./configure --use-cuda=no \
      --mathlib=OPENBLAS_CLAPACK --shared \
      --android-incdir=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include \
      --host=$HOST --openblas-root=${WORKDIR}/local \
      --fst-root=${WORKDIR}/local --fst-version=1.8.0
    make depend -j$(nproc)
    cd $WORKDIR/kaldi/src
    make online2 rnnlm -j$(nproc)

    # Vosk-api
    cd $WORKDIR
    git clone --depth=1 https://github.com/alphacep/vosk-api
    cd ${WORKDIR}/vosk-api && patch -p1 < $SCRIPTS_DIR/../patches/vosk.patch
    make -j$(nproc) -C ${WORKDIR}/vosk-api/src \
      KALDI_ROOT=${WORKDIR}/kaldi \
      OPENFST_ROOT=${WORKDIR}/local \
      OPENBLAS_ROOT=${WORKDIR}/local \
      CXX=$CXX \
      EXTRA_LDFLAGS="-llog -static-libstdc++ -Wl,-soname,libvosk.so"

    mkdir -p $SCRIPTS_DIR/jniLibs/$arch
    cp $WORKDIR/vosk-api/src/vosk-cli $SCRIPTS_DIR/jniLibs/$arch/libvosk.so

done
