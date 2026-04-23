import os
import sys
import json
import time
import subprocess
import numpy as np
import onnxruntime as ort

def get_phonemes(text, voice="en-us"):
    # Run espeak-ng to get IPA phonemes, removing newlines
    res = subprocess.run(
        ["espeak-ng", "-v", voice, "-q", "--ipa", "--sep=", text],
        capture_output=True,
        text=True,
        check=True
    )
    # Join multiline IPA output and remove extra spaces
    return " ".join(res.stdout.strip().splitlines())

def phonemes_to_ids(phonemes, id_map):
    ids = []
    # Piper start symbol
    ids.append(id_map.get("^", [1])[0])
    ids.append(id_map.get("_", [0])[0])
    
    for p in phonemes:
        if p in id_map:
            ids.append(id_map[p][0])
            ids.append(id_map.get("_", [0])[0])
        # Skip characters not in the phoneme map
            
    # Piper end symbol
    ids.append(id_map.get("$", [2])[0])
    return ids

def main():
    onnx_path = "en_US-lessac-high.onnx"
    json_path = "en_US-lessac-high.onnx.json"
    
    if not os.path.exists(onnx_path) or not os.path.exists(json_path):
        print("Model files not found. Ensure en_US-lessac-high.onnx and .json are present.")
        return

    with open(json_path, "r") as f:
        config = json.load(f)
        
    sample_rate = config["audio"]["sample_rate"]
    id_map = config["phoneme_id_map"]
    
    # Load model with XNNPACK optimized for ARM
    sess_opts = ort.SessionOptions()
    # Optimize for mobile ARM CPUs
    sess_opts.intra_op_num_threads = 4
    # Enable XNNPACK if available in this build
    sess_opts.add_session_config_entry("session.intra_op.allow_spinning", "0")
    session = ort.InferenceSession(onnx_path, sess_opts, providers=['CPUExecutionProvider'])
    
    # Use a longer, more representative text for RTF measurement
    text = "This is a comprehensive test of the piper text-to-speech engine running directly on a mobile device environment. We are measuring the real-time factor to ensure the performance is acceptable for interactive applications."
    
    phonemes = get_phonemes(text, config["espeak"]["voice"])
    phoneme_ids = phonemes_to_ids(phonemes, id_map)
    
    phoneme_ids_np = np.array(phoneme_ids, dtype=np.int64)[None, :]
    phoneme_lengths = np.array([len(phoneme_ids)], dtype=np.int64)
    
    scales = np.array([
        config["inference"].get("noise_scale", 0.667),
        config["inference"].get("length_scale", 1.0),
        config["inference"].get("noise_w", 0.8)
    ], dtype=np.float32)
    
    print(f"Text length: {len(text)} characters")
    print(f"Phoneme count: {len(phoneme_ids)}")
    print("Warming up...")
    # Warm up run
    session.run(None, {
        "input": phoneme_ids_np,
        "input_lengths": phoneme_lengths,
        "scales": scales
    })
    
    print("Benchmarking (3 runs)...")
    num_runs = 3
    times = []
    for i in range(num_runs):
        start = time.perf_counter()
        outputs = session.run(None, {
            "input": phoneme_ids_np,
            "input_lengths": phoneme_lengths,
            "scales": scales
        })
        times.append(time.perf_counter() - start)
        print(f"Run {i+1}: {times[-1]:.4f}s")
    
    avg_inference_time = sum(times) / num_runs
    audio = outputs[0]
    # Handle output shape (1, 1, 1, N) or (1, N) etc.
    num_samples = audio.shape[-1]
    audio_duration = num_samples / sample_rate
    
    rtf = avg_inference_time / audio_duration
    
    print("-" * 40)
    print(f"Average Inference Time: {avg_inference_time:.4f} s")
    print(f"Audio Duration:        {audio_duration:.4f} s")
    print(f"Real-Time Factor (RTF): {rtf:.4f}")
    print("-" * 40)
    
    if rtf < 1.0:
        print(f"SUCCESS: Piper is {1.0/rtf:.2f}x faster than real-time.")
    else:
        print(f"CAUTION: Piper is {rtf:.2f}x slower than real-time.")

if __name__ == "__main__":
    main()
