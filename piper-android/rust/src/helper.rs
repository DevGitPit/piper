use ndarray::{Array, Array3};
use serde::{Deserialize, Serialize};
use std::fs::File;
use std::io::BufReader;
use std::path::Path;
use anyhow::{Result, Context};
use unicode_normalization::UnicodeNormalization;
use rand_distr::{Distribution, Normal};
use regex::Regex;
use std::time::Instant;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    pub ae: AEConfig,
    pub ttl: TTLConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AEConfig {
    pub sample_rate: i32,
    pub base_chunk_size: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TTLConfig {
    pub chunk_compress_factor: i32,
    pub latent_dim: i32,
}

pub fn load_cfgs<P: AsRef<Path>>(onnx_dir: P) -> Result<Config> {
    let cfg_path = onnx_dir.as_ref().join("tts.json");
    let file = File::open(cfg_path)?;
    let reader = BufReader::new(file);
    let cfgs: Config = serde_json::from_reader(reader)?;
    Ok(cfgs)
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VoiceStyleData {
    pub style_ttl: StyleComponent,
    pub style_dp: StyleComponent,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StyleComponent {
    pub data: Vec<Vec<Vec<f32>>>,
    pub dims: Vec<usize>,
    #[serde(rename = "type")]
    pub dtype: String,
}

pub struct UnicodeProcessor {
    indexer: Vec<i64>,
}

impl UnicodeProcessor {
    pub fn new<P: AsRef<Path>>(unicode_indexer_json_path: P) -> Result<Self> {
        let file = File::open(unicode_indexer_json_path)?;
        let reader = BufReader::new(file);
        let indexer: Vec<i64> = serde_json::from_reader(reader)?;
        Ok(UnicodeProcessor { indexer })
    }

    pub fn call(&self, text_list: &[String], lang_list: &[String]) -> Result<(Vec<Vec<i64>>, Array3<f32>)> {
        let mut processed_texts: Vec<String> = Vec::new();
        for (text, lang) in text_list.iter().zip(lang_list.iter()) {
            processed_texts.push(preprocess_text(text, lang)?);
        }

        let text_ids_lengths: Vec<usize> = processed_texts
            .iter()
            .map(|t| t.chars().count())
            .collect();

        let max_len = *text_ids_lengths.iter().max().unwrap_or(&0);

        let mut text_ids = Vec::new();
        for text in &processed_texts {
            let mut row = vec![0i64; max_len];
            let unicode_vals = text_to_unicode_values(text);
            for (j, &val) in unicode_vals.iter().enumerate() {
                if val < self.indexer.len() {
                    let id = self.indexer[val];
                    row[j] = if id == -1 { 0 } else { id };
                } else {
                    row[j] = 0;
                }
            }
            text_ids.push(row);
        }

        let text_mask = get_text_mask(&text_ids_lengths);
        Ok((text_ids, text_mask))
    }
}

pub fn preprocess_text(text: &str, lang: &str) -> Result<String> {
    let mut text: String = text.nfkd().collect();
    if lang == "en" {
        let emoji_pattern = Regex::new(r"[\x{1F600}-\x{1F64F}\x{1F300}-\x{1F5FF}\x{1F680}-\x{1F6FF}\x{1F700}-\x{1F77F}\x{1F780}-\x{1F7FF}\x{1F800}-\x{1F8FF}\x{1F900}-\x{1F9FF}\x{1FA00}-\x{1FA6F}\x{1FA70}-\x{1FAFF}\x{2600}-\x{26FF}\x{2700}-\x{27BF}\x{1F1E6}-\x{1F1FF}]+").unwrap();
        text = emoji_pattern.replace_all(&text, "").to_string();
        let replacements = [("_", " "), ("\u{201C}", "\""), ("\u{201D}", "\"")];
        for (from, to) in &replacements { text = text.replace(from, to); }
        text = Regex::new(r"\s+").unwrap().replace_all(&text, " ").to_string();
        text = text.trim().to_string();
    }
    Ok(text)
}

pub fn text_to_unicode_values(text: &str) -> Vec<usize> {
    text.chars().map(|c| c as usize).collect()
}

pub fn length_to_mask(lengths: &[usize], max_len: Option<usize>) -> Array3<f32> {
    let bsz = lengths.len();
    let max_len = max_len.unwrap_or_else(|| *lengths.iter().max().unwrap_or(&0));
    let mut mask = Array3::<f32>::zeros((bsz, 1, max_len));
    for (i, &len) in lengths.iter().enumerate() {
        for j in 0..len.min(max_len) { mask[[i, 0, j]] = 1.0; }
    }
    mask
}

pub fn get_text_mask(text_ids_lengths: &[usize]) -> Array3<f32> {
    let max_len = *text_ids_lengths.iter().max().unwrap_or(&0);
    length_to_mask(text_ids_lengths, Some(max_len))
}

pub fn sample_noisy_latent(
    duration: &[f32],
    sample_rate: i32,
    base_chunk_size: i32,
    chunk_compress: i32,
    latent_dim: i32,
) -> (Array3<f32>, Array3<f32>) {
    let bsz = duration.len();
    let max_dur = duration.iter().fold(0.0f32, |a, &b| a.max(b));
    let wav_len_max = (max_dur * sample_rate as f32) as usize;
    let chunk_size = (base_chunk_size * chunk_compress) as usize;
    let latent_len = (wav_len_max + chunk_size - 1) / chunk_size;
    let latent_dim_val = (latent_dim * chunk_compress) as usize;

    let mut noisy_latent = Array3::<f32>::zeros((bsz, latent_dim_val, latent_len));
    let normal = Normal::new(0.0, 0.667).unwrap();
    let mut rng = rand::rng();

    for b in 0..bsz {
        for d in 0..latent_dim_val {
            for t in 0..latent_len {
                noisy_latent[[b, d, t]] = normal.sample(&mut rng);
            }
        }
    }

    let wav_lengths: Vec<usize> = duration.iter().map(|&d| (d * sample_rate as f32) as usize).collect();
    let latent_lengths: Vec<usize> = wav_lengths.iter().map(|&len| (len + chunk_size - 1) / chunk_size).collect();
    let latent_mask = length_to_mask(&latent_lengths, Some(latent_len));

    for b in 0..bsz {
        for d in 0..latent_dim_val {
            for t in 0..latent_len {
                noisy_latent[[b, d, t]] *= latent_mask[[b, 0, t]];
            }
        }
    }
    (noisy_latent, latent_mask)
}

use ort::{
    session::{builder::GraphOptimizationLevel, Session},
    value::Value,
};

pub struct Style {
    pub ttl: Array3<f32>,
    pub dp: Array3<f32>,
}

pub struct TextToSpeech {
    pub cfgs: Config,
    text_processor: UnicodeProcessor,
    dp_ort: Session,
    text_enc_ort: Session,
    vector_est_ort: Session,
    vocoder_ort: Session,
    pub sample_rate: i32,
}

impl TextToSpeech {
    pub fn new(cfgs: Config, text_processor: UnicodeProcessor, dp_ort: Session, text_enc_ort: Session, vector_est_ort: Session, vocoder_ort: Session) -> Self {
        let sample_rate = cfgs.ae.sample_rate;
        TextToSpeech { cfgs, text_processor, dp_ort, text_enc_ort, vector_est_ort, vocoder_ort, sample_rate }
    }

    fn _infer(&mut self, text_list: &[String], lang_list: &[String], style: &Style, total_step: usize, speed: f32) -> Result<(Vec<f32>, Vec<f32>)> {
        let bsz = text_list.len();
        let (text_ids, text_mask) = self.text_processor.call(text_list, lang_list)?;
        let text_ids_shape = (bsz, text_ids[0].len());
        let mut flat = Vec::new();
        for row in &text_ids { flat.extend_from_slice(row); }
        let text_ids_array = Array::from_shape_vec(text_ids_shape, flat)?;
        let text_ids_value = Value::from_array(text_ids_array)?;
        let text_mask_value = Value::from_array(text_mask.clone())?;
        let style_dp_value = Value::from_array(style.dp.clone())?;

        let dp_outputs = self.dp_ort.run(ort::inputs!{"text_ids" => &text_ids_value, "style_dp" => &style_dp_value, "text_mask" => &text_mask_value})?;
        let duration_data = dp_outputs["duration"].try_extract_tensor::<f32>()?;
        let mut duration: Vec<f32> = duration_data.1.to_vec();
        for dur in duration.iter_mut() { *dur /= speed; }

        let style_ttl_value = Value::from_array(style.ttl.clone())?;
        let text_enc_outputs = self.text_enc_ort.run(ort::inputs!{"text_ids" => &text_ids_value, "style_ttl" => &style_ttl_value, "text_mask" => &text_mask_value})?;
        let text_emb_data = text_enc_outputs["text_emb"].try_extract_tensor::<f32>()?;
        let text_emb_shape = text_emb_data.0;
        let text_emb = Array3::from_shape_vec((text_emb_shape[0] as usize, text_emb_shape[1] as usize, text_emb_shape[2] as usize), text_emb_data.1.to_vec())?;

        let (mut xt, latent_mask) = sample_noisy_latent(&duration, self.sample_rate, self.cfgs.ae.base_chunk_size, self.cfgs.ttl.chunk_compress_factor, self.cfgs.ttl.latent_dim);
        let total_step_array = Array::from_elem(bsz, total_step as f32);

        for step in 0..total_step {
            let current_step_array = Array::from_elem(bsz, step as f32);
            let xt_value = Value::from_array(xt.clone())?;
            let text_emb_value = Value::from_array(text_emb.clone())?;
            let latent_mask_value = Value::from_array(latent_mask.clone())?;
            let text_mask_value2 = Value::from_array(text_mask.clone())?;
            let current_step_value = Value::from_array(current_step_array)?;
            let total_step_value = Value::from_array(total_step_array.clone())?;

            let vector_est_outputs = self.vector_est_ort.run(ort::inputs!{
                "noisy_latent" => &xt_value, "text_emb" => &text_emb_value, "style_ttl" => &style_ttl_value,
                "latent_mask" => &latent_mask_value, "text_mask" => &text_mask_value2,
                "current_step" => &current_step_value, "total_step" => &total_step_value
            })?;
            let denoised_data = vector_est_outputs["denoised_latent"].try_extract_tensor::<f32>()?;
            let denoised_shape = denoised_data.0;
            xt = Array3::from_shape_vec((denoised_shape[0] as usize, denoised_shape[1] as usize, denoised_shape[2] as usize), denoised_data.1.to_vec())?;
        }

        let final_latent_value = Value::from_array(xt)?;
        let vocoder_outputs = self.vocoder_ort.run(ort::inputs!{"latent" => &final_latent_value})?;
        let wav_data = vocoder_outputs["wav_tts"].try_extract_tensor::<f32>()?;
        Ok((wav_data.1.to_vec(), duration))
    }

    pub fn call<F>(&mut self, text: &str, lang: &str, style: &Style, total_step: usize, speed: f32, silence_duration: f32, mut callback: F) -> Result<(Vec<f32>, f32)> 
    where F: FnMut(usize, usize, Option<&[f32]>) -> bool {
        let (wav, duration) = self._infer(&[text.to_string()], &[lang.to_string()], style, total_step, speed)?;
        Ok((wav, duration[0]))
    }
}

pub fn load_voice_style(voice_style_paths: &[String], _verbose: bool) -> Result<Style> {
    let file = File::open(&voice_style_paths[0])?;
    let data: VoiceStyleData = serde_json::from_reader(BufReader::new(file))?;
    let ttl = Array3::from_shape_vec((1, data.style_ttl.dims[1], data.style_ttl.dims[2]), data.style_ttl.data[0].clone().into_iter().flatten().collect())?;
    let dp = Array3::from_shape_vec((1, data.style_dp.dims[1], data.style_dp.dims[2]), data.style_dp.data[0].clone().into_iter().flatten().collect())?;
    Ok(Style { ttl, dp })
}

pub fn load_and_mix_voice_styles(p1: &str, p2: &str, alpha: f32) -> Result<Style> {
    let s1 = load_voice_style(&[p1.to_string()], false)?;
    let s2 = load_voice_style(&[p2.to_string()], false)?;
    Ok(Style { ttl: &s1.ttl * (1.0 - alpha) + &s2.ttl * alpha, dp: &s1.dp * (1.0 - alpha) + &s2.dp * alpha })
}

fn create_session(path: &str, threads: usize) -> Result<Session> {
    Session::builder()?
        .with_optimization_level(GraphOptimizationLevel::Level3)?
        .with_intra_threads(threads)?
        .with_config_entry("session.intra_op.allow_spinning", "0")?
        .commit_from_file(path).context("Failed to load model")
}

pub fn load_text_to_speech(onnx_dir: &str, _gpu: bool, _xnn: bool, ort_threads: usize, _xnn_threads: usize) -> Result<TextToSpeech> {
    let cfgs = load_cfgs(onnx_dir)?;
    let dp_ort = create_session(&format!("{}/duration_predictor.onnx", onnx_dir), ort_threads)?;
    let text_enc_ort = create_session(&format!("{}/text_encoder.onnx", onnx_dir), ort_threads)?;
    let vector_est_ort = create_session(&format!("{}/vector_estimator.onnx", onnx_dir), ort_threads)?;
    let vocoder_ort = create_session(&format!("{}/vocoder.onnx", onnx_dir), ort_threads)?;
    let text_processor = UnicodeProcessor::new(&format!("{}/unicode_indexer.json", onnx_dir))?;
    Ok(TextToSpeech::new(cfgs, text_processor, dp_ort, text_enc_ort, vector_est_ort, vocoder_ort))
}
