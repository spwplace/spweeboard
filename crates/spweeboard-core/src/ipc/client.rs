//! HTTP inference client for the keyboard extension.
//!
//! Lightweight client that connects to the main app's inference server.
//! Designed to stay well under the 50MB iOS keyboard extension memory limit.

use crate::ipc::protocol::*;
use crate::ipc::{DEFAULT_PORT, LOCALHOST};
use futures_util::StreamExt;
use reqwest::Client;
use std::time::Duration;
use thiserror::Error;
use tokio::sync::mpsc;
use tracing::{debug, error, info, trace, warn};

/// Errors that can occur in the inference client.
#[derive(Error, Debug)]
pub enum ClientError {
    /// Server is not reachable.
    #[error("Server not reachable: {0}")]
    ServerUnreachable(String),

    /// Server returned an error.
    #[error("Server error: {0}")]
    ServerError(String),

    /// HTTP request failed.
    #[error("HTTP error: {0}")]
    Http(#[from] reqwest::Error),

    /// JSON parsing failed.
    #[error("JSON error: {0}")]
    Json(#[from] serde_json::Error),

    /// Stream ended unexpectedly.
    #[error("Stream ended unexpectedly")]
    StreamEnded,

    /// Request timed out.
    #[error("Request timed out")]
    Timeout,
}

/// Configuration for the inference client.
#[derive(Debug, Clone)]
pub struct ClientConfig {
    /// Server port.
    pub port: u16,
    /// Connection timeout.
    pub connect_timeout: Duration,
    /// Request timeout (for non-streaming).
    pub request_timeout: Duration,
}

impl Default for ClientConfig {
    fn default() -> Self {
        Self {
            port: DEFAULT_PORT,
            connect_timeout: Duration::from_secs(2),
            request_timeout: Duration::from_secs(30),
        }
    }
}

/// Lightweight inference client for keyboard extensions.
///
/// This client is designed to be memory-efficient for use in iOS keyboard
/// extensions (50MB limit). It communicates with the main app's inference
/// server over localhost HTTP.
pub struct InferenceClient {
    client: Client,
    base_url: String,
    config: ClientConfig,
}

impl InferenceClient {
    /// Creates a new inference client with default configuration.
    ///
    /// # Errors
    /// Returns error if the HTTP client cannot be created.
    pub fn new() -> Result<Self, ClientError> {
        Self::with_config(ClientConfig::default())
    }

    /// Creates a new inference client with custom configuration.
    ///
    /// # Errors
    /// Returns error if the HTTP client cannot be created.
    pub fn with_config(config: ClientConfig) -> Result<Self, ClientError> {
        let client = Client::builder()
            .connect_timeout(config.connect_timeout)
            .timeout(config.request_timeout)
            .build()?;

        let base_url = format!("http://{}:{}", LOCALHOST, config.port);

        Ok(Self {
            client,
            base_url,
            config,
        })
    }

    /// Checks if the server is reachable and ready.
    ///
    /// # Errors
    /// Returns error if server is unreachable.
    pub async fn health(&self) -> Result<HealthResponse, ClientError> {
        let url = format!("{}/health", self.base_url);
        debug!(url = %url, "Health check");

        let response = self
            .client
            .get(&url)
            .send()
            .await
            .map_err(|e| ClientError::ServerUnreachable(e.to_string()))?;

        let health: HealthResponse = response.json().await?;
        trace!(ready = health.ready, "Health response");

        Ok(health)
    }

    /// Checks if the server is ready for inference.
    pub async fn is_ready(&self) -> bool {
        match self.health().await {
            Ok(h) => h.ready,
            Err(_) => false,
        }
    }

    /// Generates text from a prompt (non-streaming).
    ///
    /// # Errors
    /// Returns error if inference fails.
    pub async fn generate(&self, request: InferRequest) -> Result<InferResponse, ClientError> {
        let request = InferRequest {
            stream: false,
            ..request
        };

        let url = format!("{}/infer", self.base_url);
        debug!(url = %url, prompt_len = request.prompt.len(), "Generate request");

        let response = self
            .client
            .post(&url)
            .json(&request)
            .send()
            .await
            .map_err(|e| ClientError::ServerUnreachable(e.to_string()))?;

        if !response.status().is_success() {
            let error: InferEvent = response.json().await?;
            if let InferEvent::Error { message, .. } = error {
                return Err(ClientError::ServerError(message));
            }
            return Err(ClientError::ServerError("Unknown error".to_string()));
        }

        let result: InferResponse = response.json().await?;
        info!(
            elapsed_ms = result.elapsed_ms,
            output_len = result.text.len(),
            "Generation complete"
        );

        Ok(result)
    }

    /// Generates text from a prompt with streaming.
    ///
    /// Returns a channel receiver that yields tokens as they're generated.
    ///
    /// # Errors
    /// Returns error if the connection fails.
    pub async fn stream(
        &self,
        request: InferRequest,
    ) -> Result<mpsc::Receiver<Result<InferEvent, ClientError>>, ClientError> {
        let request = InferRequest {
            stream: true,
            ..request
        };

        let url = format!("{}/infer", self.base_url);
        debug!(url = %url, prompt_len = request.prompt.len(), "Stream request");

        let response = self
            .client
            .post(&url)
            .json(&request)
            .send()
            .await
            .map_err(|e| ClientError::ServerUnreachable(e.to_string()))?;

        if !response.status().is_success() {
            let status = response.status();
            let text = response.text().await.unwrap_or_default();
            return Err(ClientError::ServerError(format!(
                "HTTP {}: {}",
                status, text
            )));
        }

        let (tx, rx) = mpsc::channel(32);

        // Spawn task to read SSE stream
        let mut byte_stream = response.bytes_stream();

        tokio::spawn(async move {
            let mut buffer = String::new();

            while let Some(chunk) = byte_stream.next().await {
                match chunk {
                    Ok(bytes) => {
                        if let Ok(text) = String::from_utf8(bytes.to_vec()) {
                            buffer.push_str(&text);

                            // Parse SSE events from buffer
                            while let Some(pos) = buffer.find("\n\n") {
                                let event_str = buffer[..pos].to_string();
                                buffer = buffer[pos + 2..].to_string();

                                // Parse "data: {...}" lines
                                for line in event_str.lines() {
                                    if let Some(data) = line.strip_prefix("data: ") {
                                        match serde_json::from_str::<InferEvent>(data) {
                                            Ok(event) => {
                                                let is_done =
                                                    matches!(event, InferEvent::Done { .. });
                                                if tx.send(Ok(event)).await.is_err() {
                                                    return;
                                                }
                                                if is_done {
                                                    return;
                                                }
                                            }
                                            Err(e) => {
                                                warn!("Failed to parse SSE event: {}", e);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Err(e) => {
                        let _ = tx.send(Err(ClientError::Http(e))).await;
                        return;
                    }
                }
            }
        });

        Ok(rx)
    }

    /// Convenience method to stream tokens as strings.
    ///
    /// Returns a channel that yields just the text tokens, ignoring metadata.
    ///
    /// # Errors
    /// Returns error if the connection fails.
    pub async fn stream_text(
        &self,
        request: InferRequest,
    ) -> Result<mpsc::Receiver<String>, ClientError> {
        let mut event_rx = self.stream(request).await?;
        let (tx, rx) = mpsc::channel(32);

        tokio::spawn(async move {
            while let Some(result) = event_rx.recv().await {
                match result {
                    Ok(InferEvent::Token { text, .. }) => {
                        if tx.send(text).await.is_err() {
                            break;
                        }
                    }
                    Ok(InferEvent::Done { .. }) => break,
                    Ok(InferEvent::Error { message, .. }) => {
                        error!("Stream error: {}", message);
                        break;
                    }
                    Err(e) => {
                        error!("Client error: {}", e);
                        break;
                    }
                }
            }
        });

        Ok(rx)
    }

    /// Requests the server to switch models.
    ///
    /// Returns a channel that yields progress events.
    ///
    /// # Errors
    /// Returns error if the request fails.
    pub async fn switch_model(
        &self,
        model_id: impl Into<String>,
        quantization: impl Into<String>,
    ) -> Result<mpsc::Receiver<Result<SwitchModelEvent, ClientError>>, ClientError> {
        let request = SwitchModelRequest {
            model_id: model_id.into(),
            quantization: quantization.into(),
        };

        let url = format!("{}/switch", self.base_url);
        info!(model_id = %request.model_id, "Requesting model switch");

        let response = self
            .client
            .post(&url)
            .json(&request)
            .send()
            .await
            .map_err(|e| ClientError::ServerUnreachable(e.to_string()))?;

        if !response.status().is_success() {
            let status = response.status();
            let text = response.text().await.unwrap_or_default();
            return Err(ClientError::ServerError(format!(
                "HTTP {}: {}",
                status, text
            )));
        }

        let (tx, rx) = mpsc::channel(16);
        let mut byte_stream = response.bytes_stream();

        tokio::spawn(async move {
            let mut buffer = String::new();

            while let Some(chunk) = byte_stream.next().await {
                match chunk {
                    Ok(bytes) => {
                        if let Ok(text) = String::from_utf8(bytes.to_vec()) {
                            buffer.push_str(&text);

                            while let Some(pos) = buffer.find("\n\n") {
                                let event_str = buffer[..pos].to_string();
                                buffer = buffer[pos + 2..].to_string();

                                for line in event_str.lines() {
                                    if let Some(data) = line.strip_prefix("data: ") {
                                        match serde_json::from_str::<SwitchModelEvent>(data) {
                                            Ok(event) => {
                                                let is_terminal = matches!(
                                                    event,
                                                    SwitchModelEvent::Loaded { .. }
                                                        | SwitchModelEvent::Failed { .. }
                                                );
                                                if tx.send(Ok(event)).await.is_err() {
                                                    return;
                                                }
                                                if is_terminal {
                                                    return;
                                                }
                                            }
                                            Err(e) => {
                                                warn!("Failed to parse switch event: {}", e);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Err(e) => {
                        let _ = tx.send(Err(ClientError::Http(e))).await;
                        return;
                    }
                }
            }
        });

        Ok(rx)
    }
}
