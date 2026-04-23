use anyhow::{Context, Result};
use std::ffi::{CString};
use std::os::raw::{c_char, c_int};
use std::path::Path;
use std::ptr;
use std::fs::File;
use std::io::BufReader;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PiperConfig {
    pub audio: AudioConfig,
    pub espeak: EspeakConfig,
    pub inference: InferenceConfig,
    pub phoneme_id_map: HashMap<String, Vec<i64>>,
    pub num_symbols: usize,
    pub num_speakers: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AudioConfig {
    pub sample_rate: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EspeakConfig {
    pub voice: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InferenceConfig {
    pub noise_scale: f32,
    pub length_scale: f32,
    pub noise_w: f32,
}

#[repr(C)]
pub struct PiperSynthesizer { _private: [u8; 0] }

#[repr(C)]
pub struct PiperAudioChunk {
    pub samples: *const f32,
    pub num_samples: usize,
    pub sample_rate: c_int,
    pub is_last: bool,
    pub phonemes: *const u32,
    pub num_phonemes: usize,
    pub phoneme_ids: *const c_int,
    pub num_phoneme_ids: usize,
    pub alignments: *const c_int,
    pub num_alignments: usize,
}

#[repr(C)]
pub struct PiperSynthesizeOptions {
    pub speaker_id: c_int,
    pub length_scale: f32,
    pub noise_scale: f32,
    pub noise_w_scale: f32,
}

extern "C" {
    fn piper_create(
        model_path: *const c_char,
        config_path: *const c_char,
        espeak_data_path: *const c_char,
        threads: c_int,
    ) -> *mut PiperSynthesizer;

    fn piper_free(synth: *mut PiperSynthesizer);

    fn piper_default_synthesize_options(synth: *mut PiperSynthesizer) -> PiperSynthesizeOptions;

    fn piper_synthesize_start(
        synth: *mut PiperSynthesizer,
        text: *const c_char,
        options: *const PiperSynthesizeOptions,
    ) -> c_int;

    fn piper_synthesize_next(
        synth: *mut PiperSynthesizer,
        chunk: *mut PiperAudioChunk,
    ) -> c_int;
}

const PIPER_OK: c_int = 0;
const PIPER_DONE: c_int = 1;

pub struct PiperEngine {
    pub config: PiperConfig,
    synth: *mut PiperSynthesizer,
}

impl PiperEngine {
    pub fn new<P: AsRef<Path>>(
        model_path: P,
        config_path: P,
        espeak_data_path: P,
        threads: usize,
    ) -> Result<Self> {
        let model_path_ref = model_path.as_ref();
        let config_path_ref = config_path.as_ref();
        
        let file = File::open(config_path_ref).context("Failed to open config file")?;
        let reader = BufReader::new(file);
        let config: PiperConfig = serde_json::from_reader(reader).context("Failed to parse config JSON")?;

        let model_cstr = CString::new(model_path_ref.to_str().context("Invalid model path")?)?;
        let config_cstr = CString::new(config_path_ref.to_str().context("Invalid config path")?)?;
        let espeak_cstr = CString::new(espeak_data_path.as_ref().to_str().context("Invalid espeak path")?)?;

        let synth = unsafe {
            piper_create(model_cstr.as_ptr(), config_cstr.as_ptr(), espeak_cstr.as_ptr(), threads as c_int)
        };

        if synth.is_null() {
            anyhow::bail!("Failed to create Piper synthesizer");
        }

        Ok(PiperEngine { config, synth })
    }

    pub fn synthesize<F>(&self, text: &str, speed: f32, mut callback: F) -> Result<(Vec<f32>, f32)>
    where
        F: FnMut(Option<&[f32]>) -> bool,
    {
        let text_cstr = CString::new(text)?;
        let mut options = unsafe { piper_default_synthesize_options(self.synth) };
        let clamped_speed = speed.clamp(0.5, 2.0);
        options.length_scale = 1.0 / clamped_speed;

        let status = unsafe {
            piper_synthesize_start(self.synth, text_cstr.as_ptr(), &options)
        };

        if status != PIPER_OK {
            anyhow::bail!("Failed to start synthesis");
        }

        let mut wav_cat = Vec::new();
        let mut sample_rate = 22050;
        
        loop {
            let mut chunk = unsafe { std::mem::zeroed::<PiperAudioChunk>() };
            let next_status = unsafe { piper_synthesize_next(self.synth, &mut chunk) };
            
            if next_status == PIPER_DONE {
                break;
            }

            if next_status != PIPER_OK {
                anyhow::bail!("Synthesis next failed with status: {}", next_status);
            }

            if !chunk.samples.is_null() && chunk.num_samples > 0 {
                let samples_slice = unsafe {
                    std::slice::from_raw_parts(chunk.samples, chunk.num_samples)
                };
                
                sample_rate = chunk.sample_rate;
                if !callback(Some(samples_slice)) {
                    return Err(anyhow::anyhow!("Cancelled"));
                }
                wav_cat.extend_from_slice(samples_slice);
            }

            if chunk.is_last {
                break;
            }
        }

        let duration = wav_cat.len() as f32 / sample_rate as f32;
        Ok((wav_cat, duration))
    }
}

impl Drop for PiperEngine {
    fn drop(&mut self) {
        unsafe {
            piper_free(self.synth);
        }
    }
}

unsafe impl Send for PiperEngine {}
unsafe impl Sync for PiperEngine {}
