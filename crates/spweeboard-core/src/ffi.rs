//! UniFFI bindings for iOS/Android integration.
//!
//! Provides a simplified API for mobile platforms to:
//! - Parse and validate SPW expressions
//! - Manage expression buffer state
//! - Run LLM inference for SPW interpretation
//! - Manage ground contexts (CRUD operations)

#[cfg(feature = "uniffi")]
use uniffi;

use crate::spw::{parse, Expression, ParseError};
use crate::compiler::PromptCompiler;
use crate::ground::{Ground, GroundContent, GroundStore};

#[cfg(feature = "llama")]
use crate::inference::{GenerationParams, InferenceConfig, InferenceEngine, LlamaEngine, LlamaError};

/// Error type for FFI operations.
#[derive(Debug, thiserror::Error)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Error))]
pub enum SpwError {
    #[error("Store error: {reason}")]
    Store { reason: String },
    #[error("Invalid ground: {reason}")]
    InvalidGround { reason: String },
}

/// Result of parsing a SPW expression.
#[derive(Debug, Clone)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct SpwParseResult {
    /// Whether the expression is valid.
    pub is_valid: bool,
    /// The rendered expression (if valid).
    pub rendered: Option<String>,
    /// Error message (if invalid).
    pub error: Option<String>,
    /// Number of nodes in the expression.
    pub node_count: u32,
}

/// Parse state for UI feedback.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Enum))]
pub enum SpwParseState {
    /// Buffer is empty.
    Empty,
    /// Expression is valid and complete.
    Valid,
    /// Expression has errors (e.g., unmatched brackets).
    Invalid,
}

/// Buffer for incremental SPW expression composition.
/// Thread-safe wrapper around the internal ExpressionBuffer.
#[cfg_attr(feature = "uniffi", derive(uniffi::Object))]
pub struct SpwBuffer {
    raw: std::sync::Mutex<String>,
}

#[cfg_attr(feature = "uniffi", uniffi::export)]
impl SpwBuffer {
    /// Creates a new empty buffer.
    #[cfg_attr(feature = "uniffi", uniffi::constructor)]
    pub fn new() -> Self {
        Self {
            raw: std::sync::Mutex::new(String::new()),
        }
    }

    /// Pushes a character to the buffer.
    pub fn push(&self, c: String) {
        if let Ok(mut raw) = self.raw.lock() {
            raw.push_str(&c);
        }
    }

    /// Removes the last character from the buffer.
    pub fn pop(&self) {
        if let Ok(mut raw) = self.raw.lock() {
            raw.pop();
        }
    }

    /// Clears the buffer.
    pub fn clear(&self) {
        if let Ok(mut raw) = self.raw.lock() {
            raw.clear();
        }
    }

    /// Returns the current raw input.
    pub fn get_raw(&self) -> String {
        self.raw.lock().map(|r| r.clone()).unwrap_or_default()
    }

    /// Returns whether the buffer is empty.
    pub fn is_empty(&self) -> bool {
        self.raw.lock().map(|r| r.is_empty()).unwrap_or(true)
    }

    /// Returns the current parse state.
    pub fn get_parse_state(&self) -> SpwParseState {
        let raw = match self.raw.lock() {
            Ok(r) => r.clone(),
            Err(_) => return SpwParseState::Empty,
        };

        if raw.is_empty() {
            return SpwParseState::Empty;
        }

        match parse(&raw) {
            Ok(_) => SpwParseState::Valid,
            Err(_) => SpwParseState::Invalid,
        }
    }

    /// Parses the current buffer and returns the result.
    pub fn parse(&self) -> SpwParseResult {
        let raw = match self.raw.lock() {
            Ok(r) => r.clone(),
            Err(_) => {
                return SpwParseResult {
                    is_valid: false,
                    rendered: None,
                    error: Some("Lock error".to_string()),
                    node_count: 0,
                }
            }
        };

        if raw.is_empty() {
            return SpwParseResult {
                is_valid: true,
                rendered: Some(String::new()),
                error: None,
                node_count: 0,
            };
        }

        match parse(&raw) {
            Ok(expr) => SpwParseResult {
                is_valid: true,
                rendered: Some(expr.render()),
                error: None,
                node_count: expr.len() as u32,
            },
            Err(e) => SpwParseResult {
                is_valid: false,
                rendered: None,
                error: Some(e.to_string()),
                node_count: 0,
            },
        }
    }
}

impl Default for SpwBuffer {
    fn default() -> Self {
        Self::new()
    }
}

/// Validates a SPW expression string.
/// Returns true if the expression is syntactically valid.
#[cfg_attr(feature = "uniffi", uniffi::export)]
pub fn validate_spw(input: String) -> bool {
    if input.is_empty() {
        return true;
    }
    parse(&input).is_ok()
}

/// Parses a SPW expression and returns detailed result.
#[cfg_attr(feature = "uniffi", uniffi::export)]
pub fn parse_spw(input: String) -> SpwParseResult {
    if input.is_empty() {
        return SpwParseResult {
            is_valid: true,
            rendered: Some(String::new()),
            error: None,
            node_count: 0,
        };
    }

    match parse(&input) {
        Ok(expr) => SpwParseResult {
            is_valid: true,
            rendered: Some(expr.render()),
            error: None,
            node_count: expr.len() as u32,
        },
        Err(e) => SpwParseResult {
            is_valid: false,
            rendered: None,
            error: Some(e.to_string()),
            node_count: 0,
        },
    }
}

// =============================================================================
// Ground Management FFI
// =============================================================================

/// Content type for a ground.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Enum))]
pub enum SpwGroundContentType {
    /// Natural language description.
    Natural,
    /// SPW expression.
    Spw,
}

/// FFI-friendly ground representation.
#[derive(Debug, Clone)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct SpwGround {
    /// Unique identifier.
    pub id: String,
    /// Human-readable name.
    pub name: String,
    /// Content type (natural or spw).
    pub content_type: SpwGroundContentType,
    /// The ground content (natural language text or SPW expression).
    pub content: String,
    /// Human-readable description of what this ground does.
    pub description: String,
    /// Category for grouping (e.g., "Work", "Creative", "Philosophical").
    pub category: String,
}

impl From<Ground> for SpwGround {
    fn from(ground: Ground) -> Self {
        let (content_type, content) = match &ground.content {
            GroundContent::Natural(text) => (SpwGroundContentType::Natural, text.to_string()),
            GroundContent::Spw(expr) => (SpwGroundContentType::Spw, expr.render()),
        };
        Self {
            id: ground.id.to_string(),
            name: ground.name.to_string(),
            content_type,
            content,
            description: ground.description.to_string(),
            category: ground.category.to_string(),
        }
    }
}

impl SpwGround {
    /// Converts to the internal Ground type.
    pub fn to_ground(&self) -> Result<Ground, SpwError> {
        match self.content_type {
            SpwGroundContentType::Natural => {
                Ok(Ground::natural(&self.id, &self.name, &self.content, &self.description, &self.category))
            }
            SpwGroundContentType::Spw => {
                Ground::spw(&self.id, &self.name, &self.content, &self.description, &self.category)
                    .map_err(|e| SpwError::InvalidGround { reason: format!("Invalid SPW expression: {}", e) })
            }
        }
    }
}

/// Result of a ground operation.
#[derive(Debug, Clone)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct SpwGroundResult {
    /// Whether the operation succeeded.
    pub success: bool,
    /// Error message (if failed).
    pub error: Option<String>,
}

/// Persistent storage for ground contexts.
/// Thread-safe wrapper around SQLite-backed GroundStore.
#[cfg_attr(feature = "uniffi", derive(uniffi::Object))]
pub struct SpwGroundStore {
    inner: std::sync::Mutex<GroundStore>,
}

#[cfg_attr(feature = "uniffi", uniffi::export)]
impl SpwGroundStore {
    /// Opens or creates a ground store at the given path.
    #[cfg_attr(feature = "uniffi", uniffi::constructor)]
    pub fn open(path: String) -> Result<Self, SpwError> {
        GroundStore::open(&path)
            .map(|store| Self {
                inner: std::sync::Mutex::new(store),
            })
            .map_err(|e| SpwError::Store { reason: format!("Failed to open ground store: {}", e) })
    }

    /// Saves a ground to the store (insert or update).
    pub fn save(&self, ground: SpwGround) -> SpwGroundResult {
        let internal_ground = match ground.to_ground() {
            Ok(g) => g,
            Err(e) => {
                return SpwGroundResult {
                    success: false,
                    error: Some(e.to_string()),
                }
            }
        };

        match self.inner.lock() {
            Ok(store) => match store.save(&internal_ground) {
                Ok(()) => SpwGroundResult {
                    success: true,
                    error: None,
                },
                Err(e) => SpwGroundResult {
                    success: false,
                    error: Some(format!("Database error: {}", e)),
                },
            },
            Err(_) => SpwGroundResult {
                success: false,
                error: Some("Failed to acquire lock".to_string()),
            },
        }
    }

    /// Loads a ground by ID.
    pub fn load(&self, id: String) -> Option<SpwGround> {
        self.inner
            .lock()
            .ok()
            .and_then(|store| store.load(&id).ok().flatten())
            .map(SpwGround::from)
    }

    /// Lists all grounds.
    pub fn list(&self) -> Vec<SpwGround> {
        self.inner
            .lock()
            .ok()
            .and_then(|store| store.list().ok())
            .unwrap_or_default()
            .into_iter()
            .map(SpwGround::from)
            .collect()
    }

    /// Deletes a ground by ID.
    pub fn delete(&self, id: String) -> bool {
        self.inner
            .lock()
            .ok()
            .and_then(|store| store.delete(&id).ok())
            .unwrap_or(false)
    }

    /// Returns the number of grounds in the store.
    pub fn count(&self) -> u32 {
        self.inner
            .lock()
            .ok()
            .and_then(|store| store.list().ok())
            .map(|grounds| grounds.len() as u32)
            .unwrap_or(0)
    }

    // =========================================================================
    // History Methods
    // =========================================================================

    /// Pushes an expression to history.
    ///
    /// Avoids consecutive duplicates.
    pub fn push_history(&self, expression: String) {
        if let Ok(store) = self.inner.lock() {
            let _ = store.push_history(&expression);
        }
    }

    /// Lists history entries (newest first).
    ///
    /// Pass 0 for limit to get all entries (up to 100).
    pub fn list_history(&self, limit: u32) -> Vec<String> {
        self.inner
            .lock()
            .ok()
            .and_then(|store| store.list_history(limit).ok())
            .unwrap_or_default()
    }

    /// Recalls a specific history entry by index (0 = newest).
    pub fn recall_history(&self, index: u32) -> Option<String> {
        self.inner
            .lock()
            .ok()
            .and_then(|store| store.recall_history(index).ok())
            .flatten()
    }

    /// Clears all history entries.
    pub fn clear_history(&self) {
        if let Ok(store) = self.inner.lock() {
            let _ = store.clear_history();
        }
    }

    /// Returns the number of history entries.
    pub fn history_count(&self) -> u32 {
        self.inner
            .lock()
            .ok()
            .and_then(|store| store.history_count().ok())
            .unwrap_or(0)
    }
}

/// Validates an SPW expression for use as ground content.
/// Returns an error message if invalid, or None if valid.
#[cfg_attr(feature = "uniffi", uniffi::export)]
pub fn validate_spw_ground(spw: String) -> Option<String> {
    if spw.is_empty() {
        return Some("SPW expression cannot be empty".to_string());
    }
    match parse(&spw) {
        Ok(_) => None,
        Err(e) => Some(format!("Invalid SPW: {}", e)),
    }
}

/// Returns the preset ground contexts available out of the box.
///
/// These provide common contextual foundations for SPW interpretation.
#[cfg_attr(feature = "uniffi", uniffi::export)]
pub fn preset_grounds() -> Vec<SpwGround> {
    crate::ground::preset_grounds()
        .into_iter()
        .map(SpwGround::from)
        .collect()
}

// =============================================================================
// Symbol Info
// =============================================================================

/// Information about a SPW symbol for UI display.
#[derive(Debug, Clone)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct SpwSymbolInfo {
    /// The character representation (e.g., "~").
    pub char_repr: String,
    /// The semantic name (e.g., "potential").
    pub name: String,
    /// The deeper meaning/lore (e.g., "latent possibility, becoming").
    pub lore: String,
}

/// Returns information about all SPW symbols.
///
/// Use this to build symbol keyboards or help screens.
#[cfg_attr(feature = "uniffi", uniffi::export)]
pub fn get_symbols() -> Vec<SpwSymbolInfo> {
    use crate::spw::Symbol;

    Symbol::ALL.iter().map(|s: &Symbol| SpwSymbolInfo {
        char_repr: s.as_char().to_string(),
        name: s.name().to_string(),
        lore: s.lore().to_string(),
    }).collect()
}

/// Returns information about SPW bracket types.
#[derive(Debug, Clone)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct SpwBracketInfo {
    /// The opening bracket character.
    pub open: String,
    /// The closing bracket character.
    pub close: String,
    /// The semantic name (e.g., "concept").
    pub name: String,
    /// The deeper meaning.
    pub lore: String,
}

/// Returns information about all SPW bracket types.
#[cfg_attr(feature = "uniffi", uniffi::export)]
pub fn get_brackets() -> Vec<SpwBracketInfo> {
    vec![
        SpwBracketInfo {
            open: "<".into(),
            close: ">".into(),
            name: "concept".into(),
            lore: "named concept or abstraction".into(),
        },
        SpwBracketInfo {
            open: "(".into(),
            close: ")".into(),
            name: "scene".into(),
            lore: "concrete scene or context".into(),
        },
        SpwBracketInfo {
            open: "[".into(),
            close: "]".into(),
            name: "mode".into(),
            lore: "operational mode or state".into(),
        },
        SpwBracketInfo {
            open: "{".into(),
            close: "}".into(),
            name: "direction".into(),
            lore: "intention or goal vector".into(),
        },
    ]
}

// =============================================================================
// Simple Interpretation
// =============================================================================

/// Interprets SPW symbols into human-readable text.
/// This is a simple placeholder until LLM inference is available.
#[cfg_attr(feature = "uniffi", uniffi::export)]
pub fn interpret_spw_simple(input: String, ground_name: Option<String>) -> String {
    let mut parts: Vec<String> = Vec::new();

    // Add ground prefix if provided
    if let Some(name) = ground_name {
        if !name.is_empty() && name != "None" {
            parts.push(format!("[{}]", name));
        }
    }

    let mut in_bracket = false;
    let mut bracket_content = String::new();
    let mut bracket_type = ' ';

    for c in input.chars() {
        match c {
            '<' | '(' | '[' | '{' => {
                in_bracket = true;
                bracket_type = c;
                bracket_content.clear();
            }
            '>' | ')' | ']' | '}' => {
                if in_bracket {
                    let wrapper = match bracket_type {
                        '<' => format!("⟨{}⟩", bracket_content),
                        '(' => format!("({})", bracket_content),
                        '[' => format!("[{}]", bracket_content),
                        '{' => format!("→{}", bracket_content),
                        _ => bracket_content.clone(),
                    };
                    parts.push(wrapper);
                    in_bracket = false;
                }
            }
            _ if in_bracket => {
                bracket_content.push(c);
            }
            '~' => parts.push("becoming".to_string()),
            '#' => parts.push("vibrating".to_string()),
            '.' => parts.push("grounded".to_string()),
            '?' => parts.push("wondering".to_string()),
            '!' => parts.push("asserting".to_string()),
            '*' => parts.push("valued".to_string()),
            '&' => parts.push("self".to_string()),
            '@' => parts.push("seeing".to_string()),
            '^' => parts.push("integrating".to_string()),
            ' ' => {}
            _ => parts.push(c.to_string()),
        }
    }

    parts.join(" ")
}

// =============================================================================
// LLM Inference FFI (feature-gated)
// =============================================================================

/// Configuration for the inference engine.
#[derive(Debug, Clone)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct SpwInferenceConfig {
    /// Path to the GGUF model file.
    pub model_path: String,
    /// Number of CPU threads for inference.
    pub n_threads: u32,
    /// Context window size.
    pub n_ctx: u32,
    /// Whether to use GPU acceleration.
    pub use_gpu: bool,
    /// Number of layers to offload to GPU.
    pub n_gpu_layers: u32,
    /// Maximum tokens to generate.
    pub max_tokens: u32,
    /// Temperature for sampling (0.0 = deterministic).
    pub temperature: f32,
    /// Top-p (nucleus) sampling threshold.
    pub top_p: f32,
    /// Top-k sampling (0 = disabled).
    pub top_k: u32,
    /// Repetition penalty (1.0 = no penalty).
    pub repeat_penalty: f32,
}

impl Default for SpwInferenceConfig {
    fn default() -> Self {
        Self {
            model_path: String::new(),
            n_threads: 4,
            n_ctx: 2048,
            use_gpu: true,
            n_gpu_layers: 99,
            max_tokens: 128,
            temperature: 0.7,
            top_p: 0.9,
            top_k: 40,
            repeat_penalty: 1.1,
        }
    }
}

/// Result of an inference operation.
#[derive(Debug, Clone)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct SpwInferenceResult {
    /// Whether inference succeeded.
    pub success: bool,
    /// The generated text (if successful).
    pub text: Option<String>,
    /// Error message (if failed).
    pub error: Option<String>,
}

// =============================================================================
// Streaming Inference Types
// =============================================================================

/// Phase of streaming generation (FFI-safe).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Enum))]
pub enum SpwStreamingPhase {
    /// Model is outputting thinking content (hidden from user).
    Thinking,
    /// Model is outputting visible content.
    Content,
    /// Generation complete.
    Complete,
}

#[cfg(feature = "llama")]
impl From<crate::inference::StreamingPhase> for SpwStreamingPhase {
    fn from(phase: crate::inference::StreamingPhase) -> Self {
        match phase {
            crate::inference::StreamingPhase::Thinking => Self::Thinking,
            crate::inference::StreamingPhase::Content => Self::Content,
            crate::inference::StreamingPhase::Complete => Self::Complete,
        }
    }
}

/// Callback interface for streaming inference results.
///
/// Implement this trait in Kotlin/Swift to receive streaming updates.
/// Callbacks may be invoked from any thread - handle accordingly.
#[cfg_attr(feature = "uniffi", uniffi::export(callback_interface))]
pub trait SpwStreamCallback: Send + Sync {
    /// Called with each chunk of generated text.
    ///
    /// # Arguments
    /// * `phase` - Current generation phase (Thinking/Content/Complete)
    /// * `text` - The chunk of text (may be empty during phase transitions)
    /// * `is_final` - True for the last chunk
    fn on_chunk(&self, phase: SpwStreamingPhase, text: String, is_final: bool);

    /// Called if an error occurs during streaming.
    fn on_error(&self, message: String);
}

/// Engine status for UI feedback.
#[derive(Debug, Clone)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct SpwEngineStatus {
    /// Whether a model is loaded.
    pub is_loaded: bool,
    /// Current model path (if loaded).
    pub model_path: Option<String>,
    /// Model info string.
    pub model_info: Option<String>,
}

/// LLM inference engine for SPW interpretation.
/// Thread-safe wrapper around the llama.cpp backend.
#[cfg(feature = "llama")]
#[cfg_attr(feature = "uniffi", derive(uniffi::Object))]
pub struct SpwInferenceEngine {
    inner: std::sync::Arc<tokio::sync::Mutex<Option<LlamaEngine>>>,
    config: std::sync::Mutex<SpwInferenceConfig>,
    compiler: std::sync::Mutex<PromptCompiler>,
    runtime: tokio::runtime::Runtime,
}

#[cfg(feature = "llama")]
#[cfg_attr(feature = "uniffi", uniffi::export)]
impl SpwInferenceEngine {
    /// Creates a new inference engine (model not yet loaded).
    #[cfg_attr(feature = "uniffi", uniffi::constructor)]
    pub fn new() -> Self {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .expect("Failed to create tokio runtime");

        Self {
            inner: std::sync::Arc::new(tokio::sync::Mutex::new(None)),
            config: std::sync::Mutex::new(SpwInferenceConfig::default()),
            compiler: std::sync::Mutex::new(PromptCompiler::default()),
            runtime,
        }
    }

    /// Loads a model from a GGUF file path.
    /// This is a blocking operation that may take several seconds.
    pub fn load_model(&self, config: SpwInferenceConfig) -> SpwInferenceResult {
        // Store config
        if let Ok(mut cfg) = self.config.lock() {
            *cfg = config.clone();
        }

        let inner = self.inner.clone();
        let model_path = config.model_path.clone();
        let inference_config = InferenceConfig {
            model_path: config.model_path.clone(),
            n_threads: config.n_threads,
            n_ctx: config.n_ctx,
            use_gpu: config.use_gpu,
            n_gpu_layers: config.n_gpu_layers,
        };
        let gen_params = GenerationParams {
            max_tokens: config.max_tokens,
            temperature: config.temperature,
            top_p: config.top_p,
            top_k: config.top_k,
            repeat_penalty: config.repeat_penalty,
            ..Default::default()
        };

        self.runtime.block_on(async move {
            match LlamaEngine::new(
                &model_path,
                "Q4_K_M",
                inference_config,
                gen_params,
            ).await {
                Ok(engine) => {
                    let mut guard = inner.lock().await;
                    *guard = Some(engine);
                    SpwInferenceResult {
                        success: true,
                        text: Some("Model loaded successfully".to_string()),
                        error: None,
                    }
                }
                Err(e) => SpwInferenceResult {
                    success: false,
                    text: None,
                    error: Some(format!("Failed to load model: {}", e)),
                },
            }
        })
    }

    /// Unloads the current model to free memory.
    pub fn unload_model(&self) {
        let inner = self.inner.clone();
        self.runtime.block_on(async move {
            let mut guard = inner.lock().await;
            *guard = None;
        });
    }

    /// Cancels any in-progress interpretation.
    ///
    /// This will cause the current generation to stop at the next token
    /// and return a cancellation error.
    pub fn cancel(&self) {
        let inner = self.inner.clone();
        self.runtime.block_on(async move {
            let guard = inner.lock().await;
            if let Some(engine) = guard.as_ref() {
                engine.cancel();
            }
        });
    }

    /// Returns whether a cancellation is pending.
    pub fn is_cancelled(&self) -> bool {
        let inner = self.inner.clone();
        self.runtime.block_on(async move {
            let guard = inner.lock().await;
            guard.as_ref().map(|e| e.is_cancelled()).unwrap_or(false)
        })
    }

    /// Returns the current engine status.
    pub fn get_status(&self) -> SpwEngineStatus {
        let inner = self.inner.clone();
        self.runtime.block_on(async move {
            let guard = inner.lock().await;
            match guard.as_ref() {
                Some(engine) => SpwEngineStatus {
                    is_loaded: true,
                    model_path: Some(engine.current_model_id()),
                    model_info: Some(engine.model_info()),
                },
                None => SpwEngineStatus {
                    is_loaded: false,
                    model_path: None,
                    model_info: None,
                },
            }
        })
    }

    /// Interprets an SPW expression using the loaded LLM.
    /// Returns the generated interpretation text.
    pub fn interpret(&self, spw_input: String, ground_name: Option<String>) -> SpwInferenceResult {
        // First validate and parse the SPW
        if spw_input.is_empty() {
            return SpwInferenceResult {
                success: false,
                text: None,
                error: Some("Empty input".to_string()),
            };
        }

        let expression = match parse(&spw_input) {
            Ok(expr) => expr,
            Err(e) => {
                return SpwInferenceResult {
                    success: false,
                    text: None,
                    error: Some(format!("Parse error: {}", e)),
                };
            }
        };

        // Build ground if provided
        let ground = ground_name.and_then(|name| {
            if name.is_empty() || name == "None" {
                None
            } else {
                // Create a simple natural language ground
                Some(crate::ground::Ground::natural(&name, &name, &name, "", ""))
            }
        });

        // Compile prompt
        let prompt = match self.compiler.lock() {
            Ok(compiler) => compiler.compile(&expression, ground.as_ref()),
            Err(_) => {
                return SpwInferenceResult {
                    success: false,
                    text: None,
                    error: Some("Failed to acquire compiler lock".to_string()),
                };
            }
        };

        // Run inference
        let inner = self.inner.clone();
        self.runtime.block_on(async move {
            let guard = inner.lock().await;
            match guard.as_ref() {
                Some(engine) => {
                    match engine.generate(&prompt).await {
                        Ok(output) => {
                            // Strip thinking tags if present
                            let cleaned = crate::inference::strip_thinking(&output);
                            SpwInferenceResult {
                                success: true,
                                text: Some(cleaned),
                                error: None,
                            }
                        }
                        Err(e) => SpwInferenceResult {
                            success: false,
                            text: None,
                            error: Some(format!("Inference error: {}", e)),
                        },
                    }
                }
                None => SpwInferenceResult {
                    success: false,
                    text: None,
                    error: Some("No model loaded".to_string()),
                },
            }
        })
    }

    /// Generates raw text from a prompt (for advanced use).
    pub fn generate_raw(&self, prompt: String) -> SpwInferenceResult {
        let inner = self.inner.clone();
        self.runtime.block_on(async move {
            let guard = inner.lock().await;
            match guard.as_ref() {
                Some(engine) => {
                    match engine.generate(&prompt).await {
                        Ok(output) => SpwInferenceResult {
                            success: true,
                            text: Some(output),
                            error: None,
                        },
                        Err(e) => SpwInferenceResult {
                            success: false,
                            text: None,
                            error: Some(format!("Generation error: {}", e)),
                        },
                    }
                }
                None => SpwInferenceResult {
                    success: false,
                    text: None,
                    error: Some("No model loaded".to_string()),
                },
            }
        })
    }

    /// Interprets an SPW expression with streaming output.
    ///
    /// The callback will be invoked on a background thread with chunks of output.
    /// During the thinking phase, `on_chunk` is called with empty text to signal state.
    ///
    /// This method returns immediately; interpretation happens asynchronously.
    ///
    /// # Arguments
    /// * `spw_input` - The SPW expression to interpret.
    /// * `ground_name` - Optional ground context name.
    /// * `callback` - Callback to receive streaming updates.
    pub fn interpret_streaming(
        &self,
        spw_input: String,
        ground_name: Option<String>,
        callback: Box<dyn SpwStreamCallback>,
    ) {
        // Validate and parse SPW
        if spw_input.is_empty() {
            callback.on_error("Empty input".to_string());
            return;
        }

        let expression = match parse(&spw_input) {
            Ok(expr) => expr,
            Err(e) => {
                callback.on_error(format!("Parse error: {}", e));
                return;
            }
        };

        // Build ground if provided
        let ground = ground_name.and_then(|name| {
            if name.is_empty() || name == "None" {
                None
            } else {
                Some(crate::ground::Ground::natural(&name, &name, &name, "", ""))
            }
        });

        // Compile prompt
        let prompt = match self.compiler.lock() {
            Ok(compiler) => compiler.compile(&expression, ground.as_ref()),
            Err(_) => {
                callback.on_error("Failed to acquire compiler lock".to_string());
                return;
            }
        };

        // Clone Arc for async block
        let inner = self.inner.clone();
        let callback = std::sync::Arc::new(callback);

        // Spawn the streaming task on our runtime
        self.runtime.spawn(async move {
            let guard = inner.lock().await;
            match guard.as_ref() {
                Some(engine) => {
                    let (tx, mut rx) = tokio::sync::mpsc::channel::<crate::inference::StreamChunk>(32);
                    let callback_fwd = callback.clone();

                    // Spawn task to forward chunks to callback
                    let forward_task = tokio::spawn(async move {
                        while let Some(chunk) = rx.recv().await {
                            callback_fwd.on_chunk(
                                chunk.phase.into(),
                                chunk.text,
                                chunk.is_final,
                            );
                        }
                    });

                    // Run streaming generation
                    if let Err(e) = engine.stream_chunked(&prompt, tx).await {
                        // Don't report cancellation as an error
                        if !matches!(e, LlamaError::Cancelled) {
                            callback.on_error(format!("Streaming error: {}", e));
                        }
                    }

                    let _ = forward_task.await;
                }
                None => {
                    callback.on_error("No model loaded".to_string());
                }
            }
        });
    }
}

#[cfg(feature = "llama")]
impl Default for SpwInferenceEngine {
    fn default() -> Self {
        Self::new()
    }
}

// Stub implementation when llama feature is not enabled
#[cfg(not(feature = "llama"))]
#[cfg_attr(feature = "uniffi", derive(uniffi::Object))]
pub struct SpwInferenceEngine;

#[cfg(not(feature = "llama"))]
#[cfg_attr(feature = "uniffi", uniffi::export)]
impl SpwInferenceEngine {
    #[cfg_attr(feature = "uniffi", uniffi::constructor)]
    pub fn new() -> Self {
        Self
    }

    pub fn load_model(&self, _config: SpwInferenceConfig) -> SpwInferenceResult {
        SpwInferenceResult {
            success: false,
            text: None,
            error: Some("LLM inference not available (compiled without llama feature)".to_string()),
        }
    }

    pub fn unload_model(&self) {}

    pub fn cancel(&self) {}

    pub fn is_cancelled(&self) -> bool {
        false
    }

    pub fn get_status(&self) -> SpwEngineStatus {
        SpwEngineStatus {
            is_loaded: false,
            model_path: None,
            model_info: Some("LLM not available".to_string()),
        }
    }

    pub fn interpret(&self, spw_input: String, ground_name: Option<String>) -> SpwInferenceResult {
        // Fall back to simple interpretation
        SpwInferenceResult {
            success: true,
            text: Some(interpret_spw_simple(spw_input, ground_name)),
            error: None,
        }
    }

    pub fn generate_raw(&self, _prompt: String) -> SpwInferenceResult {
        SpwInferenceResult {
            success: false,
            text: None,
            error: Some("LLM inference not available".to_string()),
        }
    }

    pub fn interpret_streaming(
        &self,
        spw_input: String,
        ground_name: Option<String>,
        callback: Box<dyn SpwStreamCallback>,
    ) {
        // Fall back to simple interpretation, delivered as a single chunk
        let result = interpret_spw_simple(spw_input, ground_name);
        callback.on_chunk(SpwStreamingPhase::Content, result, false);
        callback.on_chunk(SpwStreamingPhase::Complete, String::new(), true);
    }
}

#[cfg(not(feature = "llama"))]
impl Default for SpwInferenceEngine {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_validate_spw() {
        assert!(validate_spw("&@".to_string()));
        assert!(validate_spw("<hello>".to_string()));
        assert!(validate_spw("".to_string()));
        assert!(!validate_spw("<hello".to_string())); // unclosed
    }

    #[test]
    fn test_parse_spw() {
        let result = parse_spw("&@".to_string());
        assert!(result.is_valid);
        assert_eq!(result.rendered, Some("&@".to_string()));

        let result = parse_spw("<hello".to_string());
        assert!(!result.is_valid);
        assert!(result.error.is_some());
    }

    #[test]
    fn test_spw_buffer() {
        let buf = SpwBuffer::new();
        assert!(buf.is_empty());
        assert_eq!(buf.get_parse_state(), SpwParseState::Empty);

        buf.push("&".to_string());
        assert!(!buf.is_empty());
        assert_eq!(buf.get_parse_state(), SpwParseState::Valid);

        buf.push("<".to_string());
        assert_eq!(buf.get_parse_state(), SpwParseState::Invalid);

        buf.push("hi>".to_string());
        assert_eq!(buf.get_parse_state(), SpwParseState::Valid);
    }

    #[test]
    fn test_interpret_simple() {
        let result = interpret_spw_simple("&@".to_string(), None);
        assert!(result.contains("self"));
        assert!(result.contains("seeing"));

        let result = interpret_spw_simple("~!".to_string(), Some("Software".to_string()));
        assert!(result.contains("[Software]"));
        assert!(result.contains("becoming"));
    }

    #[test]
    fn test_inference_config_default() {
        let config = SpwInferenceConfig::default();
        assert!(config.use_gpu);
        assert_eq!(config.n_ctx, 2048);
        assert_eq!(config.max_tokens, 128);
        assert!((config.temperature - 0.7).abs() < 0.01);
        assert!((config.top_p - 0.9).abs() < 0.01);
        assert_eq!(config.top_k, 40);
        assert!((config.repeat_penalty - 1.1).abs() < 0.01);
    }

    #[test]
    fn test_validate_spw_ground() {
        // Valid SPW
        assert!(validate_spw_ground("@[work]".to_string()).is_none());
        assert!(validate_spw_ground("&@".to_string()).is_none());

        // Invalid SPW
        assert!(validate_spw_ground("<unclosed".to_string()).is_some());
        assert!(validate_spw_ground("".to_string()).is_some());
    }

    #[test]
    fn test_spw_ground_conversion() {
        // Natural ground
        let natural = SpwGround {
            id: "test".to_string(),
            name: "Test".to_string(),
            content_type: SpwGroundContentType::Natural,
            content: "hello world".to_string(),
            description: "Test desc".to_string(),
            category: "Test".to_string(),
        };
        let ground = natural.to_ground().unwrap();
        assert_eq!(ground.render(), "hello world");

        // SPW ground
        let spw = SpwGround {
            id: "spw-test".to_string(),
            name: "SPW Test".to_string(),
            content_type: SpwGroundContentType::Spw,
            content: "@[work]".to_string(),
            description: "SPW desc".to_string(),
            category: "Work".to_string(),
        };
        let ground = spw.to_ground().unwrap();
        assert_eq!(ground.render(), "@[work]");

        // Invalid SPW should error
        let invalid = SpwGround {
            id: "invalid".to_string(),
            name: "Invalid".to_string(),
            content_type: SpwGroundContentType::Spw,
            content: "<unclosed".to_string(),
            description: "".to_string(),
            category: "".to_string(),
        };
        assert!(invalid.to_ground().is_err());
    }
}
