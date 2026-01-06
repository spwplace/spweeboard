//! UniFFI bindings for iOS/Android integration.
//!
//! Provides a simplified API for mobile platforms to:
//! - Parse and validate SPW expressions
//! - Manage expression buffer state
//! - Run LLM inference for SPW interpretation

#[cfg(feature = "uniffi")]
use uniffi;

use crate::spw::{parse, Expression, ParseError};
use crate::compiler::PromptCompiler;

#[cfg(feature = "llama")]
use crate::inference::{InferenceConfig, InferenceEngine, GenerationParams, LlamaEngine, LlamaError};

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
        let runtime = tokio::runtime::Builder::new_current_thread()
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
                Some(crate::ground::Ground::natural(&name, &name, &name))
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
    }
}
