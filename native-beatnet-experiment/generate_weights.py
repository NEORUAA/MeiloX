#!/usr/bin/env python3
"""Extract BeatNet's learned parameters into a stable flat float32 blob."""

from pathlib import Path
import argparse
import json

import numpy as np
import onnx
from onnx import numpy_helper


ORDER = (
    "conv1.weight",
    "conv1.bias",
    "linear0.weight",
    "linear0.bias",
    "lstm.weight_ih_l0",
    "lstm.weight_hh_l0",
    "val_78",
    "lstm.weight_ih_l1",
    "lstm.weight_hh_l1",
    "val_141",
    "val_156",
    "linear.bias",
)

LSTM_WEIGHTS = {
    "lstm.weight_ih_l0",
    "lstm.weight_hh_l0",
    "lstm.weight_ih_l1",
    "lstm.weight_hh_l1",
}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("model", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    model = onnx.load(args.model)
    tensors = {item.name: numpy_helper.to_array(item) for item in model.graph.initializer}
    missing = set(ORDER) - tensors.keys()
    if missing:
        raise SystemExit(f"missing tensors: {sorted(missing)}")

    offsets: dict[str, dict[str, object]] = {}
    flat_parts = []
    offset = 0
    for name in ORDER:
        tensor = tensors[name]
        # PyTorch stores gates as I,F,C,O. The exported graph rearranges its
        # four slices to ONNX's I,O,F,C before each LSTM node. Bake that graph
        # operation into the blob so the native runtime has no Slice/Concat.
        if name in LSTM_WEIGHTS:
            tensor = np.concatenate(
                (tensor[0:150], tensor[450:600], tensor[150:300], tensor[300:450]),
                axis=0,
            )
        value = np.asarray(tensor, dtype="<f4").ravel()
        offsets[name] = {
            "float_offset": offset,
            "float_count": value.size,
            "shape": list(tensors[name].shape),
        }
        flat_parts.append(value)
        offset += value.size

    args.output.parent.mkdir(parents=True, exist_ok=True)
    np.concatenate(flat_parts).tofile(args.output)
    args.output.with_suffix(".json").write_text(
        json.dumps({"float_count": offset, "tensors": offsets}, indent=2) + "\n"
    )
    print(f"wrote {args.output}: {offset} float32 values, {offset * 4} bytes")


if __name__ == "__main__":
    main()
