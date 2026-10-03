#include "beatnet_native.h"

#include <jni.h>

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_ljyh_mei_playback_BeatNetNative_predict(JNIEnv* env, jclass,
                                                  jfloatArray input) {
  if (input == nullptr || env->GetArrayLength(input) !=
                              beatnet::kFrames * beatnet::kFeatures) {
    return nullptr;
  }
  jfloat* features = env->GetFloatArrayElements(input, nullptr);
  if (features == nullptr) return nullptr;
  jfloatArray result = env->NewFloatArray(beatnet::kFrames * beatnet::kOutputs);
  if (result != nullptr) {
    jfloat* output = env->GetFloatArrayElements(result, nullptr);
    if (output != nullptr) {
      beatnet::Predict(features, output);
      env->ReleaseFloatArrayElements(result, output, 0);
    }
  }
  env->ReleaseFloatArrayElements(input, features, JNI_ABORT);
  return result;
}
