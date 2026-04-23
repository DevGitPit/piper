import onnxruntime as ort
import sys
from collections import Counter

if len(sys.argv) < 2:
    print("Usage: python inspect_model_no_onnx.py model.onnx")
    sys.exit(1)

model_path = sys.argv[1]

# We can't easily get all nodes from InferenceSession without the onnx package,
# but we can try to look for the "op_type" in the model bytes if it's small,
# or use a different approach.

# Let's try to use the session's internal graph representation if possible.
try:
    sess = ort.InferenceSession(model_path)
    # ORT doesn't expose the full graph nodes to Python easily.
    # However, we can use the 'get_modelmeta' to see some info.
    print(f"Model Metadata: {sess.get_modelmeta().custom_metadata_map}")
    
    print("\nSince the 'onnx' package is missing, I will perform a binary scan")
    print("of the ONNX file to identify operator strings. This is a heuristic.")
    
    with open(model_path, 'rb') as f:
        content = f.read()
    
    # Common ONNX operators are strings in the binary.
    # This is a very rough list for Piper/VITS.
    possible_ops = [
        'Conv', 'Relu', 'Add', 'Mul', 'BatchNormalization', 'Concat', 
        'Tanh', 'Sigmoid', 'MatMul', 'Gemm', 'Reshape', 'Transpose',
        'Unsqueeze', 'Squeeze', 'Slice', 'Split', 'Cast', 'Constant',
        'Identity', 'Gather', 'Shape', 'Softmax', 'PRelu', 'LeakyRelu'
    ]
    
    found_ops = {}
    for op in possible_ops:
        count = content.count(op.encode('ascii'))
        if count > 0:
            found_ops[op] = count
            
    print(f"\nHeuristic Operator Counts (from binary scan):")
    for op, count in sorted(found_ops.items(), key=lambda x: -x[1]):
        print(f"  {op}: ~{count}")

except Exception as e:
    print(f"Error: {e}")
