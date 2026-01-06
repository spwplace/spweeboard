//! llama.cpp-based inference engine implementation.
//!
//! Provides a full LLM inference backend using llama-cpp-2, with support for:
//! - Local GGUF model files
//! - GPU acceleration (Metal on iOS, Vulkan on Android)
//! - Model hot-swapping with progress reporting

use crate::inference::{GenerationParams, InferenceConfig, InferenceEngine};
use llama_cpp_2::context::params::LlamaContextParams;
use llama_cpp_2::llama_backend::LlamaBackend;
use llama_cpp_2::llama_batch::LlamaBatch;
use llama_cpp_2::model::params::LlamaModelParams;
use llama_cpp_2::model::{AddBos, LlamaChatMessage, LlamaModel, Special};
use llama_cpp_2::sampling::LlamaSampler;
use regex::Regex;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, LazyLock, Mutex, OnceLock};
use std::time::{Duration, Instant};

/// Global singleton for the llama.cpp backend.
/// llama.cpp only allows one backend initialization per process.
static LLAMA_BACKEND: OnceLock<Result<LlamaBackend, String>> = OnceLock::new();

/// Get or initialize the global llama.cpp backend.
fn get_backend() -> Result<&'static LlamaBackend, LlamaError> {
    let result = LLAMA_BACKEND.get_or_init(|| {
        LlamaBackend::init().map_err(|e| format!("Backend init failed: {e}"))
    });
    match result {
        Ok(backend) => Ok(backend),
        Err(e) => Err(LlamaError::ModelLoad(e.clone())),
    }
}
use thiserror::Error;
use tokio::sync::mpsc;
use tracing::{debug, info, trace, warn};

/// Regex to match and remove <think>...</think> blocks (including partial/unclosed).
static THINK_REGEX: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?s)<think>.*?(</think>|$)").expect("valid regex")
});

/// Strip thinking tags from model output.
#[must_use]
pub fn strip_thinking(output: &str) -> String {
    THINK_REGEX.replace_all(output, "").trim().to_string()
}

// =============================================================================
// Streaming Types
// =============================================================================

/// Phase of streaming generation.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StreamingPhase {
    /// Model is generating `<think>...</think>` content (hidden from user).
    Thinking,
    /// Model is generating visible content.
    Content,
    /// Generation complete.
    Complete,
}

/// A chunk of streamed output.
#[derive(Debug, Clone)]
pub struct StreamChunk {
    /// The text content of this chunk (empty during phase transitions).
    pub text: String,
    /// Current phase of generation.
    pub phase: StreamingPhase,
    /// Whether this is the final chunk.
    pub is_final: bool,
}

/// Filters tokens to detect and buffer thinking blocks in real-time.
///
/// Accumulates output and detects `<think>...</think>` tags, allowing
/// the caller to show a thinking indicator without exposing the content.
struct ThinkingFilter {
    /// Accumulated raw output.
    buffer: String,
    /// Whether we're currently inside `<think>...</think>`.
    inside_thinking: bool,
    /// Position of last emitted content in buffer.
    emit_cursor: usize,
}

impl ThinkingFilter {
    fn new() -> Self {
        Self {
            buffer: String::new(),
            inside_thinking: false,
            emit_cursor: 0,
        }
    }

    /// Feed a new token, returns (phase, optional text to emit).
    ///
    /// During thinking phase, returns `(Thinking, None)`.
    /// During content phase, returns `(Content, Some(text))` with new content.
    fn feed(&mut self, token: &str) -> (StreamingPhase, Option<String>) {
        self.buffer.push_str(token);

        // Check for <think> opening tag
        if !self.inside_thinking {
            if let Some(pos) = self.buffer[self.emit_cursor..].find("<think>") {
                // Emit anything before the tag
                let pre_tag = self.buffer[self.emit_cursor..self.emit_cursor + pos].to_string();
                self.emit_cursor += pos + "<think>".len();
                self.inside_thinking = true;

                // If there's pre-tag content, emit it as Content first
                // The next call will return Thinking phase
                if !pre_tag.is_empty() {
                    return (StreamingPhase::Content, Some(pre_tag));
                }
                return (StreamingPhase::Thinking, None);
            }
        }

        // Check for </think> closing tag
        if self.inside_thinking {
            if let Some(pos) = self.buffer[self.emit_cursor..].find("</think>") {
                self.emit_cursor += pos + "</think>".len();
                self.inside_thinking = false;
                return (StreamingPhase::Content, None);
            }
            // Still inside thinking - don't emit anything
            return (StreamingPhase::Thinking, None);
        }

        // Normal content - emit the new content since last cursor
        let to_emit = self.buffer[self.emit_cursor..].to_string();
        self.emit_cursor = self.buffer.len();

        if !to_emit.is_empty() {
            (StreamingPhase::Content, Some(to_emit))
        } else {
            (StreamingPhase::Content, None)
        }
    }
}

/// Batches tokens into chunks for efficient FFI callbacks.
///
/// Emits chunks based on time interval (~50ms) or sentence boundaries.
struct ChunkBatcher {
    /// Accumulated text since last emit.
    pending: String,
    /// Time of last emit.
    last_emit: Instant,
    /// Target batch interval.
    batch_interval: Duration,
}

impl ChunkBatcher {
    fn new(batch_interval_ms: u64) -> Self {
        Self {
            pending: String::new(),
            last_emit: Instant::now(),
            batch_interval: Duration::from_millis(batch_interval_ms),
        }
    }

    /// Add text, returns chunk if ready to emit.
    ///
    /// Emits when time threshold exceeded OR at sentence boundaries.
    fn add(&mut self, text: &str) -> Option<String> {
        self.pending.push_str(text);

        let elapsed = self.last_emit.elapsed();
        let has_sentence_end = self.pending.ends_with('.')
            || self.pending.ends_with('!')
            || self.pending.ends_with('?')
            || self.pending.ends_with('\n');

        // Emit if: time threshold exceeded OR sentence boundary (with min content)
        if elapsed >= self.batch_interval || (has_sentence_end && self.pending.len() > 10) {
            let chunk = std::mem::take(&mut self.pending);
            self.last_emit = Instant::now();
            Some(chunk)
        } else {
            None
        }
    }

    /// Force emit any remaining content (call at end of generation).
    fn flush(&mut self) -> Option<String> {
        if !self.pending.is_empty() {
            Some(std::mem::take(&mut self.pending))
        } else {
            None
        }
    }
}

/// Errors that can occur during LLM operations.
#[derive(Error, Debug)]
pub enum LlamaError {
    /// Model file not found or failed to load.
    #[error("Model loading failed: {0}")]
    ModelLoad(String),

    /// Inference operation failed.
    #[error("Inference failed: {0}")]
    Inference(String),

    /// Model is currently busy with another request.
    #[error("Model is busy, please try again")]
    ModelBusy,

    /// Generation was cancelled by the user.
    #[error("Generation cancelled")]
    Cancelled,
}

/// Progress updates during model loading.
#[derive(Debug, Clone)]
pub enum ModelLoadProgress {
    /// Starting to resolve/download the model.
    Resolving {
        /// Model identifier being resolved.
        model_id: String,
    },
    /// Download in progress (if from HuggingFace).
    Downloading {
        /// Model identifier.
        model_id: String,
        /// Filename being downloaded.
        filename: String,
    },
    /// Loading model into memory.
    Loading {
        /// Path to the model file.
        path: String,
    },
    /// Model loaded successfully.
    Loaded {
        /// Vocabulary size.
        vocab: i32,
        /// Number of parameters.
        params: u64,
    },
    /// Loading failed.
    Failed {
        /// Error description.
        error: String,
    },
}

/// Resolve a model identifier to a local GGUF file path.
///
/// Only supports local file paths. Model downloads should be handled
/// by the mobile platform (Kotlin/Swift) for better UX and progress reporting.
fn resolve_model_path(model_id: &str, _quantization: &str) -> Result<PathBuf, LlamaError> {
    let path = PathBuf::from(model_id);

    if path.exists() {
        info!("Using local model file: {}", model_id);
        return Ok(path);
    }

    Err(LlamaError::ModelLoad(format!(
        "Model file not found: {model_id}. Download the model first using the app settings."
    )))
}

/// Async version of resolve_model_path with progress reporting.
async fn resolve_model_path_with_progress(
    model_id: &str,
    quantization: &str,
    _progress_tx: mpsc::Sender<ModelLoadProgress>,
) -> Result<PathBuf, LlamaError> {
    // Just delegate to the sync version - no downloads needed
    resolve_model_path(model_id, quantization)
}

/// Internal state for the LLM engine.
struct LlamaInner {
    model: LlamaModel,
}

/// Thread-safe llama.cpp inference engine.
///
/// Wraps llama-cpp-2 with async support and model hot-swapping.
pub struct LlamaEngine {
    inner: Arc<Mutex<LlamaInner>>,
    params: GenerationParams,
    model_id: Arc<Mutex<String>>,
    quantization: Arc<Mutex<String>>,
    config: InferenceConfig,
    /// Cancellation token - set to true to abort generation.
    cancel_token: Arc<AtomicBool>,
}

impl LlamaEngine {
    /// Creates a new engine, loading the specified model.
    ///
    /// # Arguments
    /// * `model_id` - Local path to GGUF file, or HuggingFace repo ID (e.g., "Qwen/Qwen2.5-0.5B-Instruct")
    /// * `quantization` - Quantization level (e.g., "Q4_K_M", "Q8_0")
    /// * `config` - Inference configuration
    /// * `params` - Generation parameters
    ///
    /// # Errors
    /// Returns error if model cannot be loaded.
    pub async fn new(
        model_id: &str,
        quantization: &str,
        config: InferenceConfig,
        params: GenerationParams,
    ) -> Result<Self, LlamaError> {
        info!("Initializing llama.cpp backend");

        let path = resolve_model_path(model_id, quantization)?;
        let model_id_owned = model_id.to_string();
        let quantization_owned = quantization.to_string();
        let n_gpu_layers = if config.use_gpu {
            config.n_gpu_layers
        } else {
            0
        };

        // Run blocking initialization in a separate thread
        let inner = tokio::task::spawn_blocking(move || {
            let backend = get_backend()?;

            info!("Loading model: {}", path.display());

            let model_params = LlamaModelParams::default().with_n_gpu_layers(n_gpu_layers);

            let model = LlamaModel::load_from_file(backend, &path, &model_params)
                .map_err(|e| LlamaError::ModelLoad(format!("Model load failed: {e}")))?;

            info!(
                "Model loaded: vocab={}, params={}",
                model.n_vocab(),
                model.n_params()
            );

            Ok::<_, LlamaError>(LlamaInner { model })
        })
        .await
        .map_err(|e| LlamaError::ModelLoad(format!("Task join error: {e}")))??;

        info!("Model loaded successfully");

        Ok(Self {
            inner: Arc::new(Mutex::new(inner)),
            params,
            model_id: Arc::new(Mutex::new(model_id_owned)),
            quantization: Arc::new(Mutex::new(quantization_owned)),
            config,
            cancel_token: Arc::new(AtomicBool::new(false)),
        })
    }

    /// Creates a new engine from an `InferenceConfig`.
    ///
    /// Uses default quantization "Q4_K_M".
    ///
    /// # Errors
    /// Returns error if model cannot be loaded.
    pub async fn from_config(config: InferenceConfig) -> Result<Self, LlamaError> {
        let model_path = config.model_path.clone();
        Self::new(&model_path, "Q4_K_M", config, GenerationParams::default()).await
    }

    /// Returns the current model ID.
    #[must_use]
    pub fn current_model_id(&self) -> String {
        self.model_id
            .lock()
            .map(|g| g.clone())
            .unwrap_or_default()
    }

    /// Returns the current quantization level.
    #[must_use]
    pub fn current_quantization(&self) -> String {
        self.quantization
            .lock()
            .map(|g| g.clone())
            .unwrap_or_default()
    }

    /// Returns model information as a formatted string.
    #[must_use]
    pub fn model_info(&self) -> String {
        let inner = self.inner.lock().ok();
        let (vocab, params) = inner
            .as_ref()
            .map(|i| (i.model.n_vocab(), i.model.n_params()))
            .unwrap_or((0, 0));

        format!(
            "Vocab: {}, Params: {}, Max tokens: {}, Temperature: {}",
            vocab, params, self.params.max_tokens, self.params.temperature
        )
    }

    /// Cancels any in-progress generation.
    ///
    /// The next token generation iteration will check this flag and return early.
    /// The flag is automatically reset when a new generation starts.
    pub fn cancel(&self) {
        self.cancel_token.store(true, Ordering::SeqCst);
        info!("Generation cancellation requested");
    }

    /// Returns whether cancellation has been requested.
    #[must_use]
    pub fn is_cancelled(&self) -> bool {
        self.cancel_token.load(Ordering::SeqCst)
    }

    /// Resets the cancellation token (called at start of generation).
    fn reset_cancel(&self) {
        self.cancel_token.store(false, Ordering::SeqCst);
    }

    /// Switches to a new model with progress reporting.
    ///
    /// On failure, keeps the original model loaded.
    ///
    /// # Errors
    /// Returns error if the new model cannot be loaded.
    pub async fn switch_model(
        &self,
        new_model_id: &str,
        new_quantization: &str,
        progress_tx: mpsc::Sender<ModelLoadProgress>,
    ) -> Result<(), LlamaError> {
        let new_model_id_owned = new_model_id.to_string();
        let new_quantization_owned = new_quantization.to_string();

        // Send resolving progress
        let _ = progress_tx
            .send(ModelLoadProgress::Resolving {
                model_id: new_model_id_owned.clone(),
            })
            .await;

        // Resolve the path first (this may trigger download)
        let path = match resolve_model_path_with_progress(
            &new_model_id_owned,
            &new_quantization_owned,
            progress_tx.clone(),
        )
        .await
        {
            Ok(p) => p,
            Err(e) => {
                let _ = progress_tx
                    .send(ModelLoadProgress::Failed {
                        error: e.to_string(),
                    })
                    .await;
                return Err(e);
            }
        };

        let path_str = path.display().to_string();
        let _ = progress_tx
            .send(ModelLoadProgress::Loading {
                path: path_str.clone(),
            })
            .await;

        // Load the new model in a blocking task
        let progress_tx_clone = progress_tx.clone();
        let inner = self.inner.clone();
        let n_gpu_layers = if self.config.use_gpu {
            self.config.n_gpu_layers
        } else {
            0
        };

        let load_result = tokio::task::spawn_blocking(move || {
            let mut inner_guard = inner
                .lock()
                .map_err(|e| LlamaError::ModelLoad(format!("Lock error: {e}")))?;

            info!("Loading new model: {}", path.display());

            let backend = get_backend()?;
            let model_params = LlamaModelParams::default().with_n_gpu_layers(n_gpu_layers);

            let model = LlamaModel::load_from_file(backend, &path, &model_params)
                .map_err(|e| LlamaError::ModelLoad(format!("Model load failed: {e}")))?;

            let vocab = model.n_vocab();
            let params = model.n_params();

            info!("New model loaded: vocab={}, params={}", vocab, params);

            // Replace the model
            inner_guard.model = model;

            Ok::<_, LlamaError>((vocab, params))
        })
        .await
        .map_err(|e| LlamaError::ModelLoad(format!("Task join error: {e}")))?;

        match load_result {
            Ok((vocab, params)) => {
                // Update model info
                if let Ok(mut model_id) = self.model_id.lock() {
                    *model_id = new_model_id_owned;
                }
                if let Ok(mut quantization) = self.quantization.lock() {
                    *quantization = new_quantization_owned;
                }

                let _ = progress_tx
                    .send(ModelLoadProgress::Loaded { vocab, params })
                    .await;
                info!("Model switch completed successfully");
                Ok(())
            }
            Err(e) => {
                let _ = progress_tx_clone
                    .send(ModelLoadProgress::Failed {
                        error: e.to_string(),
                    })
                    .await;
                warn!("Model switch failed, keeping original model: {}", e);
                Err(e)
            }
        }
    }

    /// Generates text from a prompt.
    async fn generate_impl(&self, prompt: &str) -> Result<String, LlamaError> {
        debug!(prompt_len = prompt.len(), "Starting LLM generation");
        trace!(prompt = %prompt, "Full prompt");

        // Reset cancellation token at start of generation
        self.reset_cancel();

        let prompt = prompt.to_string();
        let max_tokens = self.params.max_tokens;
        let temperature = self.params.temperature;
        let top_p = self.params.top_p;
        let top_k = self.params.top_k;
        let repeat_penalty = self.params.repeat_penalty;
        let n_ctx = self.config.n_ctx;
        let inner = self.inner.clone();
        let cancel_token = self.cancel_token.clone();

        // Run blocking generation in a separate thread
        let output = tokio::task::spawn_blocking(move || {
            let inner = inner.lock().map_err(|e| {
                LlamaError::Inference(format!("Failed to acquire lock: {e}"))
            })?;

            let start = std::time::Instant::now();

            // Format as chat message if model has a template
            let formatted_prompt = if let Ok(template) = inner.model.chat_template(None) {
                let messages = vec![LlamaChatMessage::new("user".to_string(), prompt.clone())
                    .map_err(|e| {
                        LlamaError::Inference(format!("Message creation failed: {e}"))
                    })?];

                inner
                    .model
                    .apply_chat_template(&template, &messages, true)
                    .map_err(|e| LlamaError::Inference(format!("Template apply failed: {e}")))?
            } else {
                prompt.clone()
            };

            trace!(formatted = %formatted_prompt, "Formatted prompt");

            // Create context for this generation
            let backend = get_backend()?;
            let ctx_params =
                LlamaContextParams::default().with_n_ctx(std::num::NonZeroU32::new(n_ctx));
            let mut ctx = inner
                .model
                .new_context(backend, ctx_params)
                .map_err(|e| LlamaError::Inference(format!("Context creation failed: {e}")))?;

            // Tokenize
            let tokens = inner
                .model
                .str_to_token(&formatted_prompt, AddBos::Always)
                .map_err(|e| LlamaError::Inference(format!("Tokenization failed: {e}")))?;

            debug!(token_count = tokens.len(), "Tokenized prompt");

            // Create batch and add prompt tokens
            let mut batch = LlamaBatch::new(n_ctx as usize, 1);
            for (i, token) in tokens.iter().enumerate() {
                let is_last = i == tokens.len() - 1;
                batch
                    .add(*token, i as i32, &[0], is_last)
                    .map_err(|e| LlamaError::Inference(format!("Batch add failed: {e}")))?;
            }

            // Process prompt
            ctx.decode(&mut batch)
                .map_err(|e| LlamaError::Inference(format!("Prompt decode failed: {e}")))?;

            // Set up sampler chain with all parameters
            let mut sampler = if temperature <= 0.0 {
                LlamaSampler::greedy()
            } else {
                // Build sampler chain: penalties -> top_k -> top_p -> temp -> dist
                let mut samplers: Vec<LlamaSampler> = Vec::new();

                // Repetition penalty (applied first)
                if repeat_penalty > 1.0 {
                    // Last 64 tokens, no newline penalty
                    samplers.push(LlamaSampler::penalties(
                        64,              // last_n tokens to consider
                        repeat_penalty,  // repeat penalty
                        0.0,             // frequency penalty
                        0.0,             // presence penalty
                    ));
                }

                // Top-K sampling
                if top_k > 0 {
                    samplers.push(LlamaSampler::top_k(top_k as i32));
                }

                // Top-P (nucleus) sampling
                if top_p < 1.0 {
                    samplers.push(LlamaSampler::top_p(top_p, 1));
                }

                // Temperature
                samplers.push(LlamaSampler::temp(temperature));

                // Final distribution sampler
                samplers.push(LlamaSampler::dist(rand::random()));

                LlamaSampler::chain_simple(samplers)
            };

            // Generate tokens
            let mut output = String::new();
            let mut kv_pos = tokens.len() as i32;
            let mut logits_idx = (tokens.len() - 1) as i32;
            let mut was_cancelled = false;

            for _ in 0..max_tokens {
                // Check for cancellation
                if cancel_token.load(Ordering::SeqCst) {
                    info!("Generation cancelled by user");
                    was_cancelled = true;
                    break;
                }

                let new_token = sampler.sample(&ctx, logits_idx);
                sampler.accept(new_token);

                // Check for end-of-generation
                if inner.model.is_eog_token(new_token) {
                    break;
                }

                // Decode token to string
                if let Ok(token_str) = inner.model.token_to_str(new_token, Special::Tokenize) {
                    output.push_str(&token_str);
                }

                // Prepare next iteration
                batch.clear();
                batch
                    .add(new_token, kv_pos, &[0], true)
                    .map_err(|e| LlamaError::Inference(format!("Batch add failed: {e}")))?;

                ctx.decode(&mut batch)
                    .map_err(|e| LlamaError::Inference(format!("Decode failed: {e}")))?;

                kv_pos += 1;
                logits_idx = 0;
            }

            // Return error if cancelled (even if we got partial output)
            if was_cancelled {
                return Err(LlamaError::Cancelled);
            }

            let elapsed = start.elapsed();

            info!(
                elapsed_ms = elapsed.as_millis(),
                output_len = output.len(),
                tokens_generated = kv_pos as usize - tokens.len(),
                "LLM generation complete"
            );
            trace!(output = %output, "Full output");

            Ok::<_, LlamaError>(output)
        })
        .await
        .map_err(|e| LlamaError::Inference(format!("Task join error: {e}")))??;

        Ok(output)
    }

    /// Streams generated tokens as chunked callbacks.
    ///
    /// This is true streaming - tokens are emitted as they're generated,
    /// batched for efficiency (~50ms chunks or sentence boundaries).
    ///
    /// Thinking blocks (`<think>...</think>`) are detected and signaled via
    /// the `StreamingPhase::Thinking` state, but their content is not emitted.
    ///
    /// # Arguments
    /// * `prompt` - The prompt to generate from.
    /// * `chunk_tx` - Channel sender to receive `StreamChunk` updates.
    ///
    /// # Errors
    /// Returns error if inference fails or is cancelled.
    pub async fn stream_chunked(
        &self,
        prompt: &str,
        chunk_tx: mpsc::Sender<StreamChunk>,
    ) -> Result<(), LlamaError> {
        debug!(prompt_len = prompt.len(), "Starting streaming generation");
        trace!(prompt = %prompt, "Full prompt");

        // Reset cancellation token at start
        self.reset_cancel();

        let prompt = prompt.to_string();
        let max_tokens = self.params.max_tokens;
        let temperature = self.params.temperature;
        let top_p = self.params.top_p;
        let top_k = self.params.top_k;
        let repeat_penalty = self.params.repeat_penalty;
        let n_ctx = self.config.n_ctx;
        let inner = self.inner.clone();
        let cancel_token = self.cancel_token.clone();

        // Run blocking generation in a separate thread
        tokio::task::spawn_blocking(move || {
            let inner = inner.lock().map_err(|e| {
                LlamaError::Inference(format!("Failed to acquire lock: {e}"))
            })?;

            let start = std::time::Instant::now();

            // Format as chat message if model has a template
            let formatted_prompt = if let Ok(template) = inner.model.chat_template(None) {
                let messages = vec![LlamaChatMessage::new("user".to_string(), prompt.clone())
                    .map_err(|e| {
                        LlamaError::Inference(format!("Message creation failed: {e}"))
                    })?];

                inner
                    .model
                    .apply_chat_template(&template, &messages, true)
                    .map_err(|e| LlamaError::Inference(format!("Template apply failed: {e}")))?
            } else {
                prompt.clone()
            };

            trace!(formatted = %formatted_prompt, "Formatted prompt");

            // Create context for this generation
            let backend = get_backend()?;
            let ctx_params =
                LlamaContextParams::default().with_n_ctx(std::num::NonZeroU32::new(n_ctx));
            let mut ctx = inner
                .model
                .new_context(backend, ctx_params)
                .map_err(|e| LlamaError::Inference(format!("Context creation failed: {e}")))?;

            // Tokenize
            let tokens = inner
                .model
                .str_to_token(&formatted_prompt, AddBos::Always)
                .map_err(|e| LlamaError::Inference(format!("Tokenization failed: {e}")))?;

            debug!(token_count = tokens.len(), "Tokenized prompt");

            // Create batch and add prompt tokens
            let mut batch = LlamaBatch::new(n_ctx as usize, 1);
            for (i, token) in tokens.iter().enumerate() {
                let is_last = i == tokens.len() - 1;
                batch
                    .add(*token, i as i32, &[0], is_last)
                    .map_err(|e| LlamaError::Inference(format!("Batch add failed: {e}")))?;
            }

            // Process prompt
            ctx.decode(&mut batch)
                .map_err(|e| LlamaError::Inference(format!("Prompt decode failed: {e}")))?;

            // Set up sampler chain
            let mut sampler = if temperature <= 0.0 {
                LlamaSampler::greedy()
            } else {
                let mut samplers: Vec<LlamaSampler> = Vec::new();

                if repeat_penalty > 1.0 {
                    samplers.push(LlamaSampler::penalties(64, repeat_penalty, 0.0, 0.0));
                }
                if top_k > 0 {
                    samplers.push(LlamaSampler::top_k(top_k as i32));
                }
                if top_p < 1.0 {
                    samplers.push(LlamaSampler::top_p(top_p, 1));
                }
                samplers.push(LlamaSampler::temp(temperature));
                samplers.push(LlamaSampler::dist(rand::random()));

                LlamaSampler::chain_simple(samplers)
            };

            // Streaming state
            let mut thinking_filter = ThinkingFilter::new();
            let mut chunk_batcher = ChunkBatcher::new(50); // 50ms batches
            let mut kv_pos = tokens.len() as i32;
            let mut logits_idx = (tokens.len() - 1) as i32;
            let mut was_cancelled = false;
            let mut last_phase = StreamingPhase::Content;
            let mut tokens_generated = 0u32;

            // Token generation loop
            for _ in 0..max_tokens {
                // Check for cancellation
                if cancel_token.load(Ordering::SeqCst) {
                    info!("Streaming generation cancelled by user");
                    was_cancelled = true;
                    break;
                }

                let new_token = sampler.sample(&ctx, logits_idx);
                sampler.accept(new_token);

                // Check for end-of-generation
                if inner.model.is_eog_token(new_token) {
                    break;
                }

                // Decode token to string
                if let Ok(token_str) = inner.model.token_to_str(new_token, Special::Tokenize) {
                    // Process through thinking filter
                    let (phase, emittable) = thinking_filter.feed(&token_str);

                    // Notify phase transitions
                    if phase != last_phase {
                        let phase_chunk = StreamChunk {
                            text: String::new(),
                            phase,
                            is_final: false,
                        };
                        let _ = chunk_tx.blocking_send(phase_chunk);
                        last_phase = phase;
                    }

                    // Batch and emit content (only during Content phase)
                    if let Some(text) = emittable {
                        if let Some(chunk_text) = chunk_batcher.add(&text) {
                            let chunk = StreamChunk {
                                text: chunk_text,
                                phase: StreamingPhase::Content,
                                is_final: false,
                            };
                            let _ = chunk_tx.blocking_send(chunk);
                        }
                    }
                }

                // Prepare next iteration
                batch.clear();
                batch
                    .add(new_token, kv_pos, &[0], true)
                    .map_err(|e| LlamaError::Inference(format!("Batch add failed: {e}")))?;

                ctx.decode(&mut batch)
                    .map_err(|e| LlamaError::Inference(format!("Decode failed: {e}")))?;

                kv_pos += 1;
                logits_idx = 0;
                tokens_generated += 1;
            }

            // Handle cancellation
            if was_cancelled {
                // Send error chunk for cancellation
                let _ = chunk_tx.blocking_send(StreamChunk {
                    text: "Generation cancelled".to_string(),
                    phase: StreamingPhase::Complete,
                    is_final: true,
                });
                return Err(LlamaError::Cancelled);
            }

            // Flush remaining content
            if let Some(remaining) = chunk_batcher.flush() {
                let chunk = StreamChunk {
                    text: remaining,
                    phase: StreamingPhase::Content,
                    is_final: false,
                };
                let _ = chunk_tx.blocking_send(chunk);
            }

            // Send final completion chunk
            let final_chunk = StreamChunk {
                text: String::new(),
                phase: StreamingPhase::Complete,
                is_final: true,
            };
            let _ = chunk_tx.blocking_send(final_chunk);

            let elapsed = start.elapsed();
            info!(
                elapsed_ms = elapsed.as_millis(),
                tokens_generated,
                "Streaming generation complete"
            );

            Ok(())
        })
        .await
        .map_err(|e| LlamaError::Inference(format!("Task join error: {e}")))?
    }
}

impl InferenceEngine for LlamaEngine {
    type Error = LlamaError;

    async fn generate(&self, prompt: &str) -> Result<String, Self::Error> {
        self.generate_impl(prompt).await
    }

    async fn stream(
        &self,
        prompt: &str,
    ) -> Result<tokio::sync::mpsc::Receiver<String>, Self::Error> {
        // TODO: Implement true streaming with token-by-token output
        // For now, generate full result and send in chunks
        let result = self.generate_impl(prompt).await?;
        let (tx, rx) = tokio::sync::mpsc::channel(32);

        tokio::spawn(async move {
            // Simulate streaming by sending word-by-word
            for word in result.split_inclusive(char::is_whitespace) {
                if tx.send(word.to_string()).await.is_err() {
                    break;
                }
            }
        });

        Ok(rx)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_strip_thinking_complete() {
        let input = "Hello <think>internal thoughts</think> world";
        assert_eq!(strip_thinking(input), "Hello  world");
    }

    #[test]
    fn test_strip_thinking_unclosed() {
        let input = "Hello <think>internal thoughts that never end";
        assert_eq!(strip_thinking(input), "Hello");
    }

    #[test]
    fn test_strip_thinking_multiline() {
        let input = "Hello <think>\nline1\nline2\n</think> world";
        assert_eq!(strip_thinking(input), "Hello  world");
    }

    #[test]
    fn test_strip_thinking_none() {
        let input = "Hello world";
        assert_eq!(strip_thinking(input), "Hello world");
    }

    // ==========================================================================
    // ThinkingFilter tests
    // ==========================================================================

    #[test]
    fn test_thinking_filter_no_thinking() {
        let mut filter = ThinkingFilter::new();

        let (phase, text) = filter.feed("Hello ");
        assert_eq!(phase, StreamingPhase::Content);
        assert_eq!(text, Some("Hello ".to_string()));

        let (phase, text) = filter.feed("world!");
        assert_eq!(phase, StreamingPhase::Content);
        assert_eq!(text, Some("world!".to_string()));
    }

    #[test]
    fn test_thinking_filter_simple_thinking() {
        let mut filter = ThinkingFilter::new();

        // Content before thinking
        let (phase, text) = filter.feed("Hello ");
        assert_eq!(phase, StreamingPhase::Content);
        assert_eq!(text, Some("Hello ".to_string()));

        // Start thinking (tag split across tokens)
        let (phase, text) = filter.feed("<think>");
        assert_eq!(phase, StreamingPhase::Thinking);
        assert_eq!(text, None);

        // Inside thinking - no content emitted
        let (phase, text) = filter.feed("internal thoughts");
        assert_eq!(phase, StreamingPhase::Thinking);
        assert_eq!(text, None);

        // End thinking
        let (phase, text) = filter.feed("</think>");
        assert_eq!(phase, StreamingPhase::Content);
        assert_eq!(text, None);

        // Content after thinking
        let (phase, text) = filter.feed(" world!");
        assert_eq!(phase, StreamingPhase::Content);
        assert_eq!(text, Some(" world!".to_string()));
    }

    #[test]
    fn test_thinking_filter_content_before_tag() {
        let mut filter = ThinkingFilter::new();

        // Token contains content + start of thinking
        let (phase, text) = filter.feed("Hi <think>thoughts");
        assert_eq!(phase, StreamingPhase::Thinking);
        assert_eq!(text, Some("Hi ".to_string()));
    }

    // ==========================================================================
    // ChunkBatcher tests
    // ==========================================================================

    #[test]
    fn test_chunk_batcher_time_based() {
        let mut batcher = ChunkBatcher::new(50);

        // First add - too soon
        let chunk = batcher.add("Hello ");
        assert!(chunk.is_none());

        // Simulate time passage by manipulating internal state
        batcher.last_emit = Instant::now() - Duration::from_millis(60);

        // Should emit now
        let chunk = batcher.add("world");
        assert_eq!(chunk, Some("Hello world".to_string()));
    }

    #[test]
    fn test_chunk_batcher_sentence_boundary() {
        let mut batcher = ChunkBatcher::new(50);

        // Build up content
        batcher.add("This is a ");
        let chunk = batcher.add("sentence.");

        // Should emit at sentence boundary (if content > 10 chars)
        assert_eq!(chunk, Some("This is a sentence.".to_string()));
    }

    #[test]
    fn test_chunk_batcher_flush() {
        let mut batcher = ChunkBatcher::new(50);

        batcher.add("partial");
        let chunk = batcher.flush();

        assert_eq!(chunk, Some("partial".to_string()));

        // Second flush should return None
        let chunk = batcher.flush();
        assert!(chunk.is_none());
    }
}
