#pragma once

#include <cstddef>

namespace beatnet {

inline constexpr int kFrames = 1600;
inline constexpr int kFeatures = 272;
inline constexpr int kOutputs = 2;

// features: [1600,272], activations: [1600,2] (beat, downbeat probabilities).
// Both buffers are caller-owned. Returns false only for an invalid pointer.
bool Predict(const float* features, float* activations);

std::size_t EmbeddedWeightBytes();

}  // namespace beatnet
