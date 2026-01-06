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
        // ============ TINY TIER (<500MB) ============
        SpwModelInfo {
            id: "gemma3-270m".into(),
            name: "Gemma 3 270M".into(),
            filename: "google_gemma-3-270m-it-Q4_K_M.gguf".into(),
            url: "https://huggingface.co/bartowski/google_gemma-3-270m-it-GGUF/resolve/main/google_gemma-3-270m-it-Q4_K_M.gguf".into(),
            size_bytes: 253_115_168,
            description: "Google's tiniest Gemma 3 (2025)".into(),
        },
        SpwModelInfo {
            id: "qwen3-0.6b".into(),
            name: "Qwen3 0.6B".into(),
            filename: "Qwen3-0.6B-Q4_K_M.gguf".into(),
            url: "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf".into(),
            size_bytes: 396_705_472,
            description: "Latest Qwen with thinking mode".into(),
        },
        SpwModelInfo {
            id: "smollm2-360m".into(),
            name: "SmolLM2 360M".into(),
            filename: "smollm2-360m-instruct-q8_0.gguf".into(),
            url: "https://huggingface.co/ngxson/SmolLM2-360M-Instruct-Q8_0-GGUF/resolve/main/smollm2-360m-instruct-q8_0.gguf".into(),
            size_bytes: 386_404_992,
            description: "HuggingFace's tiny model".into(),
        },
        SpwModelInfo {
            id: "qwen2.5-0.5b".into(),
            name: "Qwen2.5 0.5B".into(),
            filename: "qwen2.5-0.5b-instruct-q4_k_m.gguf".into(),
            url: "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf".into(),
            size_bytes: 491_400_032,
            description: "Small and fast".into(),
        },
        // ============ SMALL TIER (500MB-1GB) ============
        SpwModelInfo {
            id: "qwen2.5-0.5b-q8".into(),
            name: "Qwen2.5 0.5B Q8".into(),
            filename: "qwen2.5-0.5b-instruct-q8_0.gguf".into(),
            url: "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q8_0.gguf".into(),
            size_bytes: 675_710_816,
            description: "Higher precision Qwen (better quality)".into(),
        },
        SpwModelInfo {
            id: "tinyllama-1.1b".into(),
            name: "TinyLlama 1.1B".into(),
            filename: "tinyllama-1.1b-chat-v1.0.Q4_K_S.gguf".into(),
            url: "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_S.gguf".into(),
            size_bytes: 643_728_768,
            description: "Classic lightweight model".into(),
        },
        SpwModelInfo {
            id: "gemma3-1b".into(),
            name: "Gemma 3 1B".into(),
            filename: "gemma-3-1b-it-Q4_K_M.gguf".into(),
            url: "https://huggingface.co/ggml-org/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf".into(),
            size_bytes: 806_058_240,
            description: "Google's newest 1B model (2025)".into(),
        },
        SpwModelInfo {
            id: "llama3.2-1b".into(),
            name: "Llama 3.2 1B".into(),
            filename: "Llama-3.2-1B-Instruct-Q4_K_M.gguf".into(),
            url: "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf".into(),
            size_bytes: 807_694_464,
            description: "Meta's mobile-optimized model".into(),
        },
        // ============ MEDIUM TIER (1-2GB) ============
        SpwModelInfo {
            id: "smollm2-1.7b".into(),
            name: "SmolLM2 1.7B".into(),
            filename: "smollm2-1.7b-instruct-q4_k_m.gguf".into(),
            url: "https://huggingface.co/ngxson/SmolLM2-1.7B-Instruct-Q4_K_M-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf".into(),
            size_bytes: 1_055_609_536,
            description: "Great quality-to-size ratio".into(),
        },
        SpwModelInfo {
            id: "qwen3-1.7b".into(),
            name: "Qwen3 1.7B".into(),
            filename: "Qwen3-1.7B-Q4_K_M.gguf".into(),
            url: "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf".into(),
            size_bytes: 1_107_409_472,
            description: "Latest Qwen with thinking mode".into(),
        },
        SpwModelInfo {
            id: "qwen2.5-1.5b".into(),
            name: "Qwen2.5 1.5B".into(),
            filename: "qwen2.5-1.5b-instruct-q4_k_m.gguf".into(),
            url: "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf".into(),
            size_bytes: 1_117_320_736,
            description: "Strong multilingual, official Qwen".into(),
        },
        SpwModelInfo {
            id: "gemma2-2b".into(),
            name: "Gemma 2 2B".into(),
            filename: "gemma-2-2b-it-Q4_K_M.gguf".into(),
            url: "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf".into(),
            size_bytes: 1_708_582_752,
            description: "Google's mature 2B model".into(),
        },
        SpwModelInfo {
            id: "smollm3-3b".into(),
            name: "SmolLM3 3B".into(),
            filename: "SmolLM3-Q4_K_M.gguf".into(),
            url: "https://huggingface.co/ggml-org/SmolLM3-3B-GGUF/resolve/main/SmolLM3-Q4_K_M.gguf".into(),
            size_bytes: 1_915_305_312,
            description: "HuggingFace's newest (2025)".into(),
        },
        SpwModelInfo {
            id: "llama3.2-3b".into(),
            name: "Llama 3.2 3B".into(),
            filename: "Llama-3.2-3B-Instruct-Q4_K_M.gguf".into(),
            url: "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf".into(),
            size_bytes: 2_019_377_696,
            description: "Meta's larger mobile model".into(),
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
