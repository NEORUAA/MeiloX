# Native BeatNet experiment

This directory is an isolated size experiment. It replaces the generic ONNX
Runtime dependency with the exact operators used by `beatnet_bda.onnx`:

`Conv1d -> ReLU -> MaxPool -> Linear -> LSTM(150) -> LSTM(150) -> Linear -> Softmax`

The learned float32 parameters are embedded into `libbeatnet_native.so`, so an
Android package needs the single `arm64-v8a` library and no ONNX model asset.

Generate the parameter blob (the script requires `onnx` and `numpy`):

```sh
uv run --with onnx python generate_weights.py \
  ../app/src/main/assets/beatnet/beatnet_bda.onnx beatnet_weights.bin
```

Build for Android arm64:

```sh
cmake -S . -B build-android -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-23 \
  -DCMAKE_BUILD_TYPE=MinSizeRel
cmake --build build-android
```

Integration deliberately remains outside this experiment directory. The app
would add this CMake project, declare `BeatNetNative.predict(FloatArray)`, load
`beatnet_native`, replace the ORT call, and remove the ONNX asset/dependency. The
returned pair matches the model's final slice (softmax classes 0 and 1).

This is a correctness-first scalar implementation. A production version should
tile the matrix multiplications and use ARM NEON (or a small GEMM kernel) before
shipping; the two recurrent layers perform about 288 million multiply-add pairs
for each 32-second analysis window.

Reference validation on deterministic random input compared all 3,200 returned
floats with ONNX Runtime 1.23: maximum absolute error `4.77e-7`, mean absolute
error `6.88e-8`. The stripped arm64 library is about 1.8 MiB (about 1.50 MiB
when deflated), including 1,609,300 bytes of float32 parameters.
