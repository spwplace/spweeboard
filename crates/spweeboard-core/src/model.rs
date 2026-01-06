//! Model registry for LLM models available for download.
//!
//! Provides a centralized list of models with hardcoded URLs
//! for use by both Android and iOS platforms.

/// Information about an available LLM model.
#[derive(Debug, Clone)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct SpwModelInfo {
    /// Unique identifier for the model.
    pub id: String,
    /// Human-readable display name.
    pub name: String,
    /// Filename for storage (e.g., "model.gguf").
    pub filename: String,
    /// Full download URL (HuggingFace CDN).
    pub url: String,
    /// Expected file size in bytes.
    pub size_bytes: u64,
    /// Short description of the model.
    pub description: String,
}

/// Returns the list of available models for download.
///
/// These are small, quantized GGUF models suitable for mobile inference.
/// All models are from ungated HuggingFace repos.
#[cfg_attr(feature = "uniffi", uniffi::export)]
pub fn available_models() -> Vec<SpwModelInfo> {
    vec![
        SpwModelInfo {
            id: "qwen2.5-0.5b".into(),
            name: "Qwen2.5 0.5B".into(),
            filename: "qwen2.5-0.5b-instruct-q4_k_m.gguf".into(),
            url: "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf".into(),
            size_bytes: 400_000_000,
            description: "Small and fast, good for SPW interpretation".into(),
        },
        SpwModelInfo {
            id: "tinyllama-1.1b".into(),
            name: "TinyLlama 1.1B".into(),
            filename: "tinyllama-1.1b-chat-v1.0.Q4_K_S.gguf".into(),
            url: "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_S.gguf".into(),
            size_bytes: 644_000_000,
            description: "Better quality, slightly larger".into(),
        },
        SpwModelInfo {
            id: "qwen2.5-0.5b-q8".into(),
            name: "Qwen2.5 0.5B Q8".into(),
            filename: "qwen2.5-0.5b-instruct-q8_0.gguf".into(),
            url: "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q8_0.gguf".into(),
            size_bytes: 530_000_000,
            description: "Higher precision Qwen (better quality)".into(),
        },
    ]
}

/// Returns the default model ID for initial setup.
#[cfg_attr(feature = "uniffi", uniffi::export)]
pub fn default_model_id() -> String {
    "qwen2.5-0.5b".into()
}

/// Returns model info by ID, or None if not found.
#[cfg_attr(feature = "uniffi", uniffi::export)]
pub fn get_model_by_id(id: String) -> Option<SpwModelInfo> {
    available_models().into_iter().find(|m| m.id == id)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_available_models() {
        let models = available_models();
        assert!(!models.is_empty());
        assert!(models.iter().any(|m| m.id == "qwen2.5-0.5b"));
    }

    #[test]
    fn test_default_model() {
        let default_id = default_model_id();
        let model = get_model_by_id(default_id.clone());
        assert!(model.is_some());
        assert_eq!(model.unwrap().id, default_id);
    }

    #[test]
    fn test_get_model_by_id() {
        assert!(get_model_by_id("qwen2.5-0.5b".into()).is_some());
        assert!(get_model_by_id("nonexistent".into()).is_none());
    }
}
