//! Inference engine trait and configuration.

use std::future::Future;
use tokio::sync::mpsc::Receiver;

/// Configuration for loading an inference engine.
#[derive(Debug, Clone)]
pub struct InferenceConfig {
    /// Path to the model file (GGUF format).
    pub model_path: String,
    /// Number of threads for CPU inference.
    pub n_threads: u32,
    /// Context window size.
    pub n_ctx: u32,
    /// Whether to use GPU acceleration.
    pub use_gpu: bool,
    /// Number of GPU layers to offload (if use_gpu is true).
    pub n_gpu_layers: u32,
}

impl Default for InferenceConfig {
    fn default() -> Self {
        Self {
            model_path: String::new(),
            n_threads: 4,
            n_ctx: 2048,
            use_gpu: true,
            n_gpu_layers: 99, // Offload all layers by default
        }
    }
}

/// Parameters for text generation.
#[derive(Debug, Clone)]
pub struct GenerationParams {
    /// Maximum tokens to generate.
    pub max_tokens: u32,
    /// Temperature for sampling (0.0 = deterministic, 1.0+ = creative).
    pub temperature: f32,
    /// Top-p (nucleus) sampling threshold.
    pub top_p: f32,
    /// Top-k sampling (0 = disabled).
    pub top_k: u32,
    /// Repetition penalty.
    pub repeat_penalty: f32,
    /// Stop sequences.
    pub stop: Vec<String>,
}

impl Default for GenerationParams {
    fn default() -> Self {
        Self {
            max_tokens: 128,
            temperature: 0.7,
            top_p: 0.9,
            top_k: 40,
            repeat_penalty: 1.1,
            stop: vec!["</task>".to_string(), "\n\n".to_string()],
        }
    }
}

/// Trait for LLM inference backends.
///
/// Implementations should handle platform-specific acceleration:
/// - iOS: Metal backend
/// - Android: Vulkan/OpenCL backend
/// - Fallback: CPU with NEON/ARM optimizations
pub trait InferenceEngine: Send + Sync {
    /// Error type for inference operations.
    type Error: std::error::Error + Send + Sync + 'static;

    /// Generates text from a prompt (blocking until complete).
    fn generate(
        &self,
        prompt: &str,
    ) -> impl Future<Output = Result<String, Self::Error>> + Send;

    /// Streams generated tokens as they're produced.
    fn stream(
        &self,
        prompt: &str,
    ) -> impl Future<Output = Result<Receiver<String>, Self::Error>> + Send;
}

// Note: Actual llama.cpp integration would be feature-gated:
//
// #[cfg(feature = "metal")]
// mod metal_engine { ... }
//
// #[cfg(feature = "vulkan")]
// mod vulkan_engine { ... }
//
// The implementation would wrap llama-cpp-2 crate with appropriate
// backend configuration based on the target platform.
