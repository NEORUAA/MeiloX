#include "beatnet_native.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <vector>

extern "C" const unsigned char beatnet_weights_begin[];

namespace beatnet {
namespace {
constexpr int kConvChannels = 2;
constexpr int kConvWidth = 263;
constexpr int kPooledWidth = 131;
constexpr int kProjection = 150;
constexpr int kClasses = 3;

struct Weights {
  const float *conv_w, *conv_b, *projection_w, *projection_b;
  const float *lstm0_w, *lstm0_r, *lstm0_b;
  const float *lstm1_w, *lstm1_r, *lstm1_b;
  const float *output_w, *output_b;
};

Weights GetWeights() {
  const float* cursor = reinterpret_cast<const float*>(beatnet_weights_begin);
  auto take = [&cursor](int count) { const float* result = cursor; cursor += count; return result; };
  return {take(20), take(2), take(150 * 262), take(150),
          take(600 * 150), take(600 * 150), take(1200),
          take(600 * 150), take(600 * 150), take(1200),
          take(150 * 3), take(3)};
}

inline float Sigmoid(float value) { return 1.0f / (1.0f + std::exp(-value)); }

void Dense(const float* input, const float* weights, const float* bias,
           int rows, int columns, float* output) {
  for (int row = 0; row < rows; ++row) {
    const float* weight_row = weights + row * columns;
    float sum = bias[row];
    for (int column = 0; column < columns; ++column) sum += weight_row[column] * input[column];
    output[row] = sum;
  }
}

void LstmLayer(const float* input, const float* input_weights,
               const float* recurrent_weights, const float* biases, float* output) {
  std::array<float, kProjection> hidden{};
  std::array<float, kProjection> cell{};
  std::array<float, 4 * kProjection> gates{};
  for (int frame = 0; frame < kFrames; ++frame) {
    const float* frame_input = input + frame * kProjection;
    for (int gate_row = 0; gate_row < 4 * kProjection; ++gate_row) {
      const float* wx = input_weights + gate_row * kProjection;
      const float* rh = recurrent_weights + gate_row * kProjection;
      float sum = biases[gate_row] + biases[4 * kProjection + gate_row];
      for (int index = 0; index < kProjection; ++index)
        sum += wx[index] * frame_input[index] + rh[index] * hidden[index];
      gates[gate_row] = sum;
    }
    for (int index = 0; index < kProjection; ++index) {
      const float input_gate = Sigmoid(gates[index]);
      const float output_gate = Sigmoid(gates[kProjection + index]);
      const float forget_gate = Sigmoid(gates[2 * kProjection + index]);
      const float cell_gate = std::tanh(gates[3 * kProjection + index]);
      cell[index] = forget_gate * cell[index] + input_gate * cell_gate;
      hidden[index] = output_gate * std::tanh(cell[index]);
      output[frame * kProjection + index] = hidden[index];
    }
  }
}
}  // namespace

bool Predict(const float* features, float* activations) {
  if (features == nullptr || activations == nullptr) return false;
  const Weights weights = GetWeights();
  std::vector<float> projected(kFrames * kProjection);
  std::array<float, kConvChannels * kConvWidth> convolution{};
  std::array<float, kConvChannels * kPooledWidth> pooled{};
  for (int frame = 0; frame < kFrames; ++frame) {
    const float* feature = features + frame * kFeatures;
    for (int channel = 0; channel < kConvChannels; ++channel) {
      for (int position = 0; position < kConvWidth; ++position) {
        float sum = weights.conv_b[channel];
        for (int kernel = 0; kernel < 10; ++kernel)
          sum += weights.conv_w[channel * 10 + kernel] * feature[position + kernel];
        convolution[channel * kConvWidth + position] = std::max(0.0f, sum);
      }
      for (int position = 0; position < kPooledWidth; ++position)
        pooled[channel * kPooledWidth + position] = std::max(
            convolution[channel * kConvWidth + position * 2],
            convolution[channel * kConvWidth + position * 2 + 1]);
    }
    Dense(pooled.data(), weights.projection_w, weights.projection_b,
          kProjection, 2 * kPooledWidth, projected.data() + frame * kProjection);
  }
  std::vector<float> layer0(kFrames * kProjection);
  std::vector<float> layer1(kFrames * kProjection);
  LstmLayer(projected.data(), weights.lstm0_w, weights.lstm0_r, weights.lstm0_b, layer0.data());
  LstmLayer(layer0.data(), weights.lstm1_w, weights.lstm1_r, weights.lstm1_b, layer1.data());
  for (int frame = 0; frame < kFrames; ++frame) {
    std::array<float, kClasses> logits{};
    for (int output = 0; output < kClasses; ++output) {
      float sum = weights.output_b[output];
      for (int index = 0; index < kProjection; ++index)
        sum += layer1[frame * kProjection + index] * weights.output_w[index * kClasses + output];
      logits[output] = sum;
    }
    const float maximum = *std::max_element(logits.begin(), logits.end());
    float denominator = 0.0f;
    for (float& value : logits) { value = std::exp(value - maximum); denominator += value; }
    activations[frame * kOutputs] = logits[0] / denominator;
    activations[frame * kOutputs + 1] = logits[1] / denominator;
  }
  return true;
}
}  // namespace beatnet
