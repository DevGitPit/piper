fn main() {
    let piper_src = "/data/data/com.termux/files/home/piper/piper_src";
    let piper_include = "/data/data/com.termux/files/home/piper1-gpl/libpiper/include";
    let ort_include = "/data/data/com.termux/files/home/onnxruntime/include/onnxruntime/core/session";
    let espeak_include = "/data/data/com.termux/files/usr/include/espeak-ng";
    let piper_cpp = format!("{}/piper.cpp", piper_src);

    cc::Build::new()
        .cpp(true)
        .file(&piper_cpp)
        .include(piper_include)
        .include(ort_include)
        .include(espeak_include)
        .flag("-std=c++17")
        .flag("-O3")
        .compile("piper_bridge");

    println!("cargo:rustc-link-lib=onnxruntime");
    println!("cargo:rustc-link-lib=espeak-ng");
    println!("cargo:rerun-if-changed={}", piper_cpp);
}
