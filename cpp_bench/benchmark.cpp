#include <iostream>
#include <chrono>
#include <vector>
#include <string>
#include "piper.h"

int main(int argc, char *argv[]) {
    const char* model_path = "en_US-lessac-high.onnx";
    const char* config_path = "en_US-lessac-high.onnx.json";
    const char* espeak_data_path = "/data/data/com.termux/files/usr/share/espeak-ng-data";

    piper_synthesizer *synth = piper_create(model_path, config_path, espeak_data_path, 4);
    if (!synth) {
        std::cerr << "Failed to create synthesizer" << std::endl;
        return 1;
    }

    std::string text = "This is a comprehensive test of the piper text-to-speech engine running directly on a mobile device environment. We are measuring the real-time factor to ensure the performance is acceptable for interactive applications.";
    if (argc > 1) {
        text = argv[1];
    }
    
    piper_synthesize_options options = piper_default_synthesize_options(synth);

    std::cout << "Text: " << text << std::endl;
    std::cout << "Warming up..." << std::endl;
    piper_synthesize_start(synth, text.c_str(), &options);
    piper_audio_chunk chunk;
    while (piper_synthesize_next(synth, &chunk) != PIPER_DONE) {
        // Just consume
    }

    std::cout << "Benchmarking (3 runs)..." << std::endl;
    int num_runs = 3;
    double total_rtf = 0;
    
    for (int i = 0; i < num_runs; ++i) {
        auto start = std::chrono::high_resolution_clock::now();
        
        piper_synthesize_start(synth, text.c_str(), &options);
        
        size_t total_samples = 0;
        int sample_rate = 0;
        
        while (piper_synthesize_next(synth, &chunk) != PIPER_DONE) {
            total_samples += chunk.num_samples;
            sample_rate = chunk.sample_rate;
        }
        
        auto end = std::chrono::high_resolution_clock::now();
        std::chrono::duration<double> diff = end - start;
        
        double audio_duration = static_cast<double>(total_samples) / sample_rate;
        double rtf = diff.count() / audio_duration;
        
        std::cout << "Run " << (i + 1) << ": " << diff.count() << "s (Audio: " << audio_duration << "s, RTF: " << rtf << ")" << std::endl;
        total_rtf += rtf;
    }

    std::cout << "----------------------------------------" << std::endl;
    std::cout << "Average RTF (C++): " << (total_rtf / num_runs) << std::endl;
    std::cout << "----------------------------------------" << std::endl;

    piper_free(synth);
    return 0;
}
