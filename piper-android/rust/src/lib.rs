use jni::JNIEnv;
use jni::objects::{JClass, JString, JObject, JValue};
use jni::sys::{jlong, jint, jfloat, jbyteArray};
use android_logger::Config;
use log::LevelFilter;
use std::time::Instant;
use std::path::Path;
use std::panic;

mod helper;
mod thermal;
mod piper;

use helper::{load_text_to_speech, TextToSpeech};
use piper::PiperEngine;
use thermal::{UnifiedThermalManager, SocClass};

enum EngineType {
    Supertonic(TextToSpeech),
    Piper(PiperEngine),
}

struct SupertonicEngine {
    engine: EngineType,
    thermal: UnifiedThermalManager,
    last_rtf: f32,
}

#[no_mangle]
pub extern "system" fn Java_com_brahmadeo_piper_tts_SupertonicTTS_init(
    mut env: JNIEnv,
    _instance: JObject, // It's an instance method of the singleton object
    model_path: JString,
    _lib_path: JString,
    ort_threads: jint,
    xnn_threads: jint,
) -> jlong {
    android_logger::init_once(
        Config::default().with_max_level(LevelFilter::Info),
    );

    log::info!("JNI init called");

    panic::set_hook(Box::new(|panic_info| {
        log::error!("RUST PANIC: {}", panic_info);
    }));

    let model_path_str: String = match env.get_string(&model_path) {
        Ok(s) => s.into(),
        Err(_) => {
            log::error!("Failed to get model_path string");
            return 0;
        }
    };
    
    log::info!("Initializing Engine (ORT: {}, XNN: {}) with path: {}", ort_threads, xnn_threads, model_path_str);

    // ORT initialization
    if !ort::init().with_name("piper-tts").commit() {
        log::warn!("ORT initialization: Already initialized or failed");
    }

    // Detect if it's a Piper model or Supertonic model
    let engine_type = if model_path_str.ends_with(".onnx") {
        log::info!("Piper model detected");
        let config_path = format!("{}.json", model_path_str);
        let model_path_obj = Path::new(&model_path_str);
        let model_dir = model_path_obj.parent().unwrap_or(Path::new("."));
        
        // espeak_Initialize expects the directory CONTAINING espeak-ng-data
        let espeak_data_path = model_dir;
        
        log::info!("Piper config: {}, espeak-ng-data parent: {:?}", config_path, espeak_data_path);

        match PiperEngine::new(model_path_obj, Path::new(&config_path), espeak_data_path, ort_threads as usize) {
            Ok(p) => EngineType::Piper(p),
            Err(e) => {
                log::error!("Failed to load Piper: {:?}", e);
                return 0;
            }
        }
    } else {
        log::info!("Supertonic model directory detected");
        match load_text_to_speech(&model_path_str, false, false, ort_threads as usize, xnn_threads as usize) {
            Ok(t) => EngineType::Supertonic(t),
            Err(e) => {
                log::error!("Failed to load Supertonic: {:?}", e);
                return 0;
            }
        }
    };

    let engine = SupertonicEngine {
        engine: engine_type,
        thermal: UnifiedThermalManager::new(),
        last_rtf: 1.0,
    };

    log::info!("Engine structure created successfully");
    Box::into_raw(Box::new(engine)) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_brahmadeo_piper_tts_SupertonicTTS_synthesize(
    mut env: JNIEnv,
    instance: JObject,
    ptr: jlong,
    text: JString,
    lang: JString,
    speed: jfloat,
    buffer_seconds: jfloat,
) -> jbyteArray {
    if ptr == 0 {
        log::error!("synthesize called with null pointer");
        return env.new_byte_array(0).unwrap().into_raw();
    }
    let engine = unsafe { &mut *(ptr as *mut SupertonicEngine) };
    
    let text: String = match env.get_string(&text) {
        Ok(s) => s.into(),
        Err(_) => {
            log::error!("Failed to get text string");
            return env.new_byte_array(0).unwrap().into_raw();
        }
    };
    let lang: String = match env.get_string(&lang) {
        Ok(s) => s.into(),
        Err(_) => {
            log::error!("Failed to get lang string");
            return env.new_byte_array(0).unwrap().into_raw();
        }
    };

    log::info!("Synthesize request: len={}, lang={}, speed={}", text.len(), lang, speed);

    engine.thermal.update(buffer_seconds, engine.last_rtf);
    let start = Instant::now();

    match &mut engine.engine {
        EngineType::Piper(p) => {
            log::info!("Synthesizing with Piper (streaming)...");
            let mut last_progress_call = Instant::now();
            
            let result = p.synthesize(&text, speed, |audio_chunk| {
                // Check for cancellation
                let is_cancelled = match env.call_method(&instance, "isCancelled", "()Z", &[]) {
                    Ok(val) => val.z().unwrap_or(false),
                    Err(e) => {
                        log::error!("Failed to call isCancelled: {:?}", e);
                        false
                    }
                };
                if is_cancelled { 
                    log::info!("Synthesis cancelled by Java");
                    return false; 
                }

                if let Some(audio) = audio_chunk {
                    // Use a local frame to prevent local reference leak
                    let res: jni::errors::Result<()> = env.with_local_frame(16, |env| {
                        let mut pcm_data = Vec::with_capacity(audio.len() * 2);
                        for &sample in audio {
                            let clamped = sample.max(-1.0).min(1.0);
                            let val = (clamped * 32767.0) as i16;
                            pcm_data.extend_from_slice(&val.to_le_bytes());
                        }
                        
                        let output = env.new_byte_array(pcm_data.len() as i32)?;
                        let i8_slice = unsafe { std::slice::from_raw_parts(pcm_data.as_ptr() as *const i8, pcm_data.len()) };
                        env.set_byte_array_region(&output, 0, i8_slice)?;
                        env.call_method(&instance, "notifyAudioChunk", "([B)V", &[JValue::Object(&output)])?;
                        Ok(())
                    });

                    if let Err(e) = res {
                        log::error!("Error in notifyAudioChunk callback: {:?}", e);
                    }
                }

                if audio_chunk.is_some() || last_progress_call.elapsed().as_millis() > 200 {
                    if let Err(e) = env.call_method(&instance, "notifyProgress", "(II)V", &[JValue::Int(0), JValue::Int(100)]) {
                        log::warn!("Failed to call notifyProgress: {:?}", e);
                    }
                    last_progress_call = Instant::now();
                }
                true
            });

            match result {
                Ok((wav_data, duration)) => {
                    let elapsed = start.elapsed().as_secs_f32();
                    if duration > 0.0 {
                        engine.last_rtf = duration / elapsed;
                        log::info!("Piper RTF: {:.2}x, duration: {:.2}s, elapsed: {:.2}s", engine.last_rtf, duration, elapsed);
                    }
                    
                    let mut pcm_data = Vec::with_capacity(wav_data.len() * 2);
                    for &sample in &wav_data {
                        let clamped = sample.max(-1.0).min(1.0);
                        let val = (clamped * 32767.0) as i16;
                        pcm_data.extend_from_slice(&val.to_le_bytes());
                    }
                    
                    match env.new_byte_array(pcm_data.len() as i32) {
                        Ok(output) => {
                            let i8_slice = unsafe { std::slice::from_raw_parts(pcm_data.as_ptr() as *const i8, pcm_data.len()) };
                            if let Err(e) = env.set_byte_array_region(&output, 0, i8_slice) {
                                log::error!("Final set_byte_array_region failed: {:?}", e);
                            }
                            output.into_raw()
                        },
                        Err(e) => {
                            log::error!("Failed to create final byte array: {:?}", e);
                            env.new_byte_array(0).unwrap().into_raw()
                        }
                    }
                },
                Err(e) => {
                    log::error!("Piper synthesis failed: {:?}", e);
                    env.new_byte_array(0).unwrap().into_raw()
                }
            }
        },
        EngineType::Supertonic(_) => {
            log::error!("Supertonic engine path is not supported in this Piper build");
            env.new_byte_array(0).unwrap().into_raw()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_brahmadeo_piper_tts_SupertonicTTS_getSampleRate(
    _env: JNIEnv,
    _instance: JObject,
    ptr: jlong,
) -> jint {
    if ptr == 0 { return 22050; }
    let engine = unsafe { &mut *(ptr as *mut SupertonicEngine) };
    match &engine.engine {
        EngineType::Supertonic(tts) => tts.sample_rate as jint,
        EngineType::Piper(p) => p.config.audio.sample_rate as jint,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_brahmadeo_piper_tts_SupertonicTTS_getSocClass(
    _env: JNIEnv,
    _instance: JObject,
    ptr: jlong,
) -> jint {
    if ptr == 0 { return -1; }
    let engine = unsafe { &mut *(ptr as *mut SupertonicEngine) };
    match engine.thermal.get_soc_class() {
        SocClass::Flagship => 3,
        SocClass::HighEnd => 2,
        SocClass::MidRange => 1,
        SocClass::LowEnd => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_brahmadeo_piper_tts_SupertonicTTS_reset(
    _env: JNIEnv,
    _instance: JObject,
    ptr: jlong,
) {
    if ptr != 0 {
        let engine = unsafe { &mut *(ptr as *mut SupertonicEngine) };
        engine.last_rtf = 1.0;
        log::info!("Engine state reset");
    }
}

#[no_mangle]
pub extern "system" fn Java_com_brahmadeo_piper_tts_SupertonicTTS_close(
    _env: JNIEnv,
    _instance: JObject,
    ptr: jlong,
) {
    if ptr != 0 {
        unsafe {
            let _ = Box::from_raw(ptr as *mut SupertonicEngine);
        }
    }
}
