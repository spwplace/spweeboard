//! LLM inference engine abstraction.
//!
//! Provides a platform-agnostic trait for text generation, with implementations
//! for different backends (llama.cpp with Metal/Vulkan, mock for testing).

mod engine;

pub use engine::{InferenceEngine, InferenceConfig, GenerationParams};

/// A mock inference engine for testing.
#[derive(Debug, Clone, Default)]
pub struct MockEngine {
    /// Fixed response to return.
    pub response: String,
}

impl MockEngine {
    /// Creates a mock engine that returns the given response.
    #[must_use]
    pub fn new(response: impl Into<String>) -> Self {
        Self {
            response: response.into(),
        }
    }
}

impl InferenceEngine for MockEngine {
    type Error = std::convert::Infallible;

    async fn generate(&self, _prompt: &str) -> Result<String, Self::Error> {
        Ok(self.response.clone())
    }

    async fn stream(
        &self,
        _prompt: &str,
    ) -> Result<tokio::sync::mpsc::Receiver<String>, Self::Error> {
        let (tx, rx) = tokio::sync::mpsc::channel(1);
        let response = self.response.clone();

        tokio::spawn(async move {
            // Simulate streaming by sending words
            for word in response.split_whitespace() {
                let _ = tx.send(format!("{word} ")).await;
            }
        });

        Ok(rx)
    }
}
