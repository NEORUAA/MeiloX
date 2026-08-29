#pragma once

namespace beatnet {
inline constexpr int kFrames = 1600;
inline constexpr int kFeatures = 272;
inline constexpr int kOutputs = 2;
bool Predict(const float* features, float* activations);
}
