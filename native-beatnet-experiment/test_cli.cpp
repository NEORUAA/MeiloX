#include "beatnet_native.h"

#include <fstream>
#include <iostream>
#include <vector>

int main(int argc, char** argv) {
  if (argc != 3) {
    std::cerr << "usage: beatnet_cli features.f32 activations.f32\n";
    return 2;
  }
  std::vector<float> input(beatnet::kFrames * beatnet::kFeatures);
  std::vector<float> output(beatnet::kFrames * beatnet::kOutputs);
  std::ifstream source(argv[1], std::ios::binary);
  source.read(reinterpret_cast<char*>(input.data()),
              static_cast<std::streamsize>(input.size() * sizeof(float)));
  if (source.gcount() != static_cast<std::streamsize>(input.size() * sizeof(float))) {
    std::cerr << "invalid input size\n";
    return 2;
  }
  beatnet::Predict(input.data(), output.data());
  std::ofstream destination(argv[2], std::ios::binary);
  destination.write(reinterpret_cast<const char*>(output.data()),
                    static_cast<std::streamsize>(output.size() * sizeof(float)));
  std::cout << "weights=" << beatnet::EmbeddedWeightBytes() << "\n";
}
