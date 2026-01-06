//! IPC protocol types shared between server and client.

use serde::{Deserialize, Serialize};

/// Request to generate text from a prompt.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InferRequest {
    /// The compiled prompt to send to the LLM.
    pub prompt: String,

    /// Maximum tokens to generate.
    #[serde(default = "default_max_tokens")]
    pub max_tokens: u32,

    /// Sampling temperature (0.0 = deterministic).
    #[serde(default = "default_temperature")]
    pub temperature: f32,

    /// Whether to stream tokens as they're generated.
    #[serde(default = "default_stream")]
    pub stream: bool,

    /// Optional request ID for correlation.
    #[serde(default)]
    pub request_id: Option<String>,
}

fn default_max_tokens() -> u32 {
    128
}
fn default_temperature() -> f32 {
    0.7
}
fn default_stream() -> bool {
    true
}

impl InferRequest {
    /// Creates a new inference request.
    #[must_use]
    pub fn new(prompt: impl Into<String>) -> Self {
        Self {
            prompt: prompt.into(),
            max_tokens: default_max_tokens(),
            temperature: default_temperature(),
            stream: default_stream(),
            request_id: None,
        }
    }

    /// Sets the maximum tokens to generate.
    #[must_use]
    pub fn with_max_tokens(mut self, max_tokens: u32) -> Self {
        self.max_tokens = max_tokens;
        self
    }

    /// Sets the sampling temperature.
    #[must_use]
    pub fn with_temperature(mut self, temperature: f32) -> Self {
        self.temperature = temperature;
        self
    }

    /// Sets whether to stream the response.
    #[must_use]
    pub fn with_stream(mut self, stream: bool) -> Self {
        self.stream = stream;
        self
    }

    /// Sets the request ID for correlation.
    #[must_use]
    pub fn with_request_id(mut self, id: impl Into<String>) -> Self {
        self.request_id = Some(id.into());
        self
    }
}

/// A chunk of streamed inference output (Server-Sent Event format).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "event", content = "data")]
pub enum InferEvent {
    /// A token was generated.
    #[serde(rename = "token")]
    Token {
        /// The generated text chunk.
        text: String,
        /// Token index in this generation.
        index: u32,
    },

    /// Generation completed successfully.
    #[serde(rename = "done")]
    Done {
        /// Total tokens generated.
        total_tokens: u32,
        /// Generation time in milliseconds.
        elapsed_ms: u64,
    },

    /// An error occurred during generation.
    #[serde(rename = "error")]
    Error {
        /// Error message.
        message: String,
        /// Error code for programmatic handling.
        code: ErrorCode,
    },
}

/// Error codes for IPC errors.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ErrorCode {
    /// Model is not loaded.
    ModelNotLoaded,
    /// Model is busy with another request.
    ModelBusy,
    /// Inference failed.
    InferenceFailed,
    /// Invalid request.
    InvalidRequest,
    /// Server is shutting down.
    ServerShutdown,
    /// Unknown error.
    Unknown,
}

/// Non-streaming inference response.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InferResponse {
    /// The generated text.
    pub text: String,

    /// Total tokens generated.
    pub total_tokens: u32,

    /// Generation time in milliseconds.
    pub elapsed_ms: u64,

    /// Request ID if provided in the request.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub request_id: Option<String>,
}

/// Health check response.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HealthResponse {
    /// Whether the server is ready to accept requests.
    pub ready: bool,

    /// Current model ID if loaded.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub model_id: Option<String>,

    /// Model information string.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub model_info: Option<String>,

    /// Server version.
    pub version: String,

    /// Number of pending requests in queue.
    pub pending_requests: u32,
}

impl HealthResponse {
    /// Creates a "not ready" health response.
    #[must_use]
    pub fn not_ready() -> Self {
        Self {
            ready: false,
            model_id: None,
            model_info: None,
            version: env!("CARGO_PKG_VERSION").to_string(),
            pending_requests: 0,
        }
    }

    /// Creates a "ready" health response with model info.
    #[must_use]
    pub fn ready(model_id: impl Into<String>, model_info: impl Into<String>) -> Self {
        Self {
            ready: true,
            model_id: Some(model_id.into()),
            model_info: Some(model_info.into()),
            version: env!("CARGO_PKG_VERSION").to_string(),
            pending_requests: 0,
        }
    }

    /// Sets the number of pending requests.
    #[must_use]
    pub fn with_pending(mut self, count: u32) -> Self {
        self.pending_requests = count;
        self
    }
}

/// Model switch request.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SwitchModelRequest {
    /// New model ID (local path or HuggingFace repo).
    pub model_id: String,

    /// Quantization level (e.g., "Q4_K_M").
    #[serde(default = "default_quantization")]
    pub quantization: String,
}

fn default_quantization() -> String {
    "Q4_K_M".to_string()
}

/// Model switch progress event.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "status")]
pub enum SwitchModelEvent {
    /// Resolving model location.
    #[serde(rename = "resolving")]
    Resolving { model_id: String },

    /// Downloading model.
    #[serde(rename = "downloading")]
    Downloading {
        model_id: String,
        filename: String,
        /// Progress 0.0 to 1.0 if known.
        progress: Option<f32>,
    },

    /// Loading model into memory.
    #[serde(rename = "loading")]
    Loading { path: String },

    /// Model loaded successfully.
    #[serde(rename = "loaded")]
    Loaded { vocab: i32, params: u64 },

    /// Switch failed.
    #[serde(rename = "failed")]
    Failed { error: String },
}
