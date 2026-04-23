#!/usr/bin/env python3
# model_info_strict.py - Only officially documented XNNPACK ops
import onnx
import sys
from collections import Counter

if len(sys.argv) < 2:
    print("Usage: python model_info_strict.py model.onnx")
    sys.exit(1)

model_path = sys.argv[1]
model = onnx.load(model_path)

# Get all operators
ops = [node.op_type for node in model.graph.node]
op_counts = Counter(ops)

# ONLY officially documented XNNPACK ops from Microsoft docs
xnnpack_ops_strict = {
    'AveragePool',      # Only 2D
    'Conv',             # Only 2D
    'ConvTranspose',    # Only 2D
    'MaxPool',          # Only 2D
    'Softmax',          # With restrictions
    'QLinearConv',      # Quantized conv
    'Resize',           # 2D/4D bilinear
    'Gemm',             # Only 2D
    'MatMul',           # Only 2D
    # Microsoft custom ops
    'QLinearAveragePool',
    'QLinearSoftmax',
    'QLinearConvTranspose',
}

print(f"\n{'='*60}")
print(f"Model: {model_path}")
print(f"{'='*60}")
print(f"Total nodes: {len(ops)}")
print(f"Unique operators: {len(op_counts)}")

xnnpack_count = sum(count for op, count in op_counts.items() if op in xnnpack_ops_strict)
print(f"\nXNNPACK-accelerated (STRICT): {xnnpack_count}/{len(ops)} ({xnnpack_count/len(ops)*100:.1f}%)")
print(f"CPU fallback: {len(ops)-xnnpack_count}/{len(ops)} ({(len(ops)-xnnpack_count)/len(ops)*100:.1f}%)")

print(f"\n{'Operator':<30} {'Count':<10} {'Accel'}")
print(f"{'-'*60}")
for op, count in sorted(op_counts.items(), key=lambda x: -x[1]):
    accel = "✓ XNNPACK" if op in xnnpack_ops_strict else "✗ CPU"
    print(f"{op:<30} {count:<10} {accel}")

print(f"\n{'='*60}")
print("XNNPACK-accelerated operators breakdown:")
for op in sorted(xnnpack_ops_strict):
    if op in op_counts:
        print(f"  {op}: {op_counts[op]}")
