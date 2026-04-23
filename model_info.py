#!/usr/bin/env python3
# model_info.py
import onnx
import sys
from collections import Counter

if len(sys.argv) < 2:
    print("Usage: python model_info.py model.onnx")
    sys.exit(1)

model_path = sys.argv[1]
model = onnx.load(model_path)

# Get all operators
ops = [node.op_type for node in model.graph.node]
op_counts = Counter(ops)

# XNNPACK supported ops (from your research)
xnnpack_ops = {
    'AveragePool', 'Conv', 'ConvTranspose', 'MaxPool', 
    'Softmax', 'QLinearConv', 'Resize', 'Gemm', 'MatMul',
    # Common elementwise ops (also supported)
    'Add', 'Mul', 'Relu', 'Sigmoid', 'Tanh', 'Clip',
    'Div', 'Sub', 'Neg', 'Abs', 'Sqrt', 'Exp',
    # Utility ops (usually supported)
    'Reshape', 'Transpose', 'Concat', 'Split', 'Squeeze',
    'Unsqueeze', 'Flatten', 'Pad', 'Slice'
}

print(f"\n{'='*60}")
print(f"Model: {model_path}")
print(f"{'='*60}")
print(f"Total nodes: {len(ops)}")
print(f"Unique operators: {len(op_counts)}")

xnnpack_count = sum(count for op, count in op_counts.items() if op in xnnpack_ops)
print(f"\nXNNPACK-accelerated: {xnnpack_count}/{len(ops)} ({xnnpack_count/len(ops)*100:.1f}%)")
print(f"CPU fallback: {len(ops)-xnnpack_count}/{len(ops)} ({(len(ops)-xnnpack_count)/len(ops)*100:.1f}%)")

print(f"\n{'Operator':<30} {'Count':<10} {'Accel'}")
print(f"{'-'*60}")
for op, count in sorted(op_counts.items(), key=lambda x: -x[1]):
    accel = "✓ XNNPACK" if op in xnnpack_ops else "✗ CPU"
    print(f"{op:<30} {count:<10} {accel}")

# Show input/output info
print(f"\n{'='*60}")
print("Inputs:")
for inp in model.graph.input:
    print(f"  {inp.name}: {inp.type}")
    
print("\nOutputs:")
for out in model.graph.output:
    print(f"  {out.name}: {out.type}")
