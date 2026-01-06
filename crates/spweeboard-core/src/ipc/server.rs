//! HTTP inference server for the main app.
//!
//! Runs on localhost to handle inference requests from the keyboard extension.

use crate::inference::{GenerationParams, InferenceConfig, InferenceEngine, LlamaEngine, LlamaError, ModelLoadProgress};
use crate::ipc::protocol::*;
use crate::ipc::DEFAULT_PORT;
use axum::{
    extract::State,
    http::StatusCode,
    response::{
        sse::{Event, KeepAlive, Sse},
        IntoResponse, Response,
    },
    routing::{get, post},
    Json, Router,
};
use std::convert::Infallible;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Instant;
use tokio::sync::{mpsc, Mutex, RwLock};
use tokio_stream::wrappers::ReceiverStream;
use tower_http::cors::{Any, CorsLayer};
use tracing::{debug, info, warn};

/// Shared server state.
struct ServerState {
    /// The LLM engine (None if not yet loaded).
    engine: RwLock<Option<LlamaEngine>>,
    /// Number of pending requests.
    pending: Mutex<u32>,
    /// Server configuration.
    config: ServerConfig,
}

/// Server configuration.
#[derive(Debug, Clone)]
pub struct ServerConfig {
    /// Port to listen on.
    pub port: u16,
    /// Default generation parameters.
    pub default_params: GenerationParams,
    /// Inference configuration.
    pub inference_config: InferenceConfig,
}

impl Default for ServerConfig {
    fn default() -> Self {
        Self {
            port: DEFAULT_PORT,
            default_params: GenerationParams::default(),
            inference_config: InferenceConfig::default(),
        }
    }
}

/// The inference server that runs in the main app.
pub struct InferenceServer {
    state: Arc<ServerState>,
}

impl InferenceServer {
    /// Creates a new inference server with the given configuration.
    #[must_use]
    pub fn new(config: ServerConfig) -> Self {
        Self {
            state: Arc::new(ServerState {
                engine: RwLock::new(None),
                pending: Mutex::new(0),
                config,
            }),
        }
    }

    /// Loads a model into the server.
    ///
    /// # Errors
    /// Returns error if model loading fails.
    pub async fn load_model(
        &self,
        model_id: &str,
        quantization: &str,
    ) -> Result<(), LlamaError> {
        info!(model_id, quantization, "Loading model");

        let engine = LlamaEngine::new(
            model_id,
            quantization,
            self.state.config.inference_config.clone(),
            self.state.config.default_params.clone(),
        )
        .await?;

        let mut guard = self.state.engine.write().await;
        *guard = Some(engine);

        info!("Model loaded successfully");
        Ok(())
    }

    /// Switches to a new model with progress reporting.
    ///
    /// # Errors
    /// Returns error if model switching fails.
    pub async fn switch_model(
        &self,
        model_id: &str,
        quantization: &str,
        progress_tx: mpsc::Sender<ModelLoadProgress>,
    ) -> Result<(), LlamaError> {
        let guard = self.state.engine.read().await;

        if let Some(engine) = guard.as_ref() {
            // Hot-swap on existing engine
            engine.switch_model(model_id, quantization, progress_tx).await
        } else {
            drop(guard);
            // No engine yet, do initial load
            // Send progress manually
            let _ = progress_tx
                .send(ModelLoadProgress::Resolving {
                    model_id: model_id.to_string(),
                })
                .await;

            self.load_model(model_id, quantization).await?;

            let guard = self.state.engine.read().await;
            if let Some(engine) = guard.as_ref() {
                let _ = progress_tx
                    .send(ModelLoadProgress::Loaded {
                        vocab: 0, // We don't have these details in this path
                        params: 0,
                    })
                    .await;
            }
            Ok(())
        }
    }

    /// Returns whether a model is loaded and ready.
    pub async fn is_ready(&self) -> bool {
        self.state.engine.read().await.is_some()
    }

    /// Starts the HTTP server.
    ///
    /// This function runs until the server is shut down.
    ///
    /// # Errors
    /// Returns error if the server fails to start.
    pub async fn run(&self) -> Result<(), std::io::Error> {
        let app = Router::new()
            .route("/health", get(health_handler))
            .route("/infer", post(infer_handler))
            .route("/switch", post(switch_model_handler))
            .layer(CorsLayer::new().allow_origin(Any))
            .with_state(self.state.clone());

        let addr = SocketAddr::from(([127, 0, 0, 1], self.state.config.port));
        info!("Starting inference server on {}", addr);

        let listener = tokio::net::TcpListener::bind(addr).await?;
        axum::serve(listener, app).await?;

        Ok(())
    }

    /// Starts the server in the background and returns a handle.
    pub fn spawn(self: Arc<Self>) -> tokio::task::JoinHandle<Result<(), std::io::Error>> {
        tokio::spawn(async move { self.run().await })
    }
}

/// Health check endpoint.
async fn health_handler(State(state): State<Arc<ServerState>>) -> Json<HealthResponse> {
    let engine = state.engine.read().await;
    let pending = *state.pending.lock().await;

    let response = match engine.as_ref() {
        Some(e) => HealthResponse::ready(e.current_model_id(), e.model_info()).with_pending(pending),
        None => HealthResponse::not_ready().with_pending(pending),
    };

    Json(response)
}

/// Inference endpoint with SSE streaming.
async fn infer_handler(
    State(state): State<Arc<ServerState>>,
    Json(request): Json<InferRequest>,
) -> Response {
    debug!(
        prompt_len = request.prompt.len(),
        stream = request.stream,
        "Inference request received"
    );

    // Increment pending count
    {
        let mut pending = state.pending.lock().await;
        *pending += 1;
    }

    let engine = state.engine.read().await;
    let Some(engine) = engine.as_ref() else {
        decrement_pending(&state).await;
        return error_response(ErrorCode::ModelNotLoaded, "No model loaded");
    };

    if request.stream {
        // Streaming response via SSE
        let (tx, rx) = mpsc::channel::<Result<Event, Infallible>>(32);
        let prompt = request.prompt.clone();
        let state_clone = state.clone();

        // We need to clone the engine reference for the spawned task
        // Since we can't move the guard, we'll do generation inline and stream results
        let start = Instant::now();

        // For streaming, we use the stream method
        match engine.stream(&prompt).await {
            Ok(mut token_rx) => {
                let _ = engine; // Release reference (read lock dropped when we leave scope)

                tokio::spawn(async move {
                    let mut index = 0u32;
                    while let Some(text) = token_rx.recv().await {
                        let event = InferEvent::Token { text, index };
                        if let Ok(json) = serde_json::to_string(&event) {
                            let sse_event = Event::default().data(json);
                            if tx.send(Ok(sse_event)).await.is_err() {
                                break;
                            }
                        }
                        index += 1;
                    }

                    // Send done event
                    let done = InferEvent::Done {
                        total_tokens: index,
                        elapsed_ms: start.elapsed().as_millis() as u64,
                    };
                    if let Ok(json) = serde_json::to_string(&done) {
                        let _ = tx.send(Ok(Event::default().data(json))).await;
                    }

                    decrement_pending(&state_clone).await;
                });

                let stream = ReceiverStream::new(rx);
                Sse::new(stream)
                    .keep_alive(KeepAlive::default())
                    .into_response()
            }
            Err(e) => {
                let _ = engine;
                decrement_pending(&state).await;
                error_response(ErrorCode::InferenceFailed, &e.to_string())
            }
        }
    } else {
        // Non-streaming response
        let start = Instant::now();
        match engine.generate(&request.prompt).await {
            Ok(text) => {
                let _ = engine;
                decrement_pending(&state).await;

                let response = InferResponse {
                    text,
                    total_tokens: 0, // We don't track this in non-streaming mode currently
                    elapsed_ms: start.elapsed().as_millis() as u64,
                    request_id: request.request_id,
                };
                Json(response).into_response()
            }
            Err(e) => {
                let _ = engine;
                decrement_pending(&state).await;
                error_response(ErrorCode::InferenceFailed, &e.to_string())
            }
        }
    }
}

/// Model switch endpoint with SSE progress.
async fn switch_model_handler(
    State(state): State<Arc<ServerState>>,
    Json(request): Json<SwitchModelRequest>,
) -> impl IntoResponse {
    info!(
        model_id = request.model_id,
        quantization = request.quantization,
        "Model switch requested"
    );

    let (progress_tx, mut progress_rx) = mpsc::channel::<ModelLoadProgress>(16);
    let (sse_tx, sse_rx) = mpsc::channel::<Result<Event, Infallible>>(16);

    let model_id = request.model_id.clone();
    let quantization = request.quantization.clone();
    let state_clone = state.clone();

    // Spawn the model loading task
    tokio::spawn(async move {
        let engine_guard = state_clone.engine.read().await;

        let result = if let Some(engine) = engine_guard.as_ref() {
            drop(engine_guard);
            // Get write lock for switching
            let engine_guard = state_clone.engine.read().await;
            if let Some(engine) = engine_guard.as_ref() {
                engine
                    .switch_model(&model_id, &quantization, progress_tx)
                    .await
            } else {
                Err(LlamaError::ModelLoad("Engine disappeared".to_string()))
            }
        } else {
            drop(engine_guard);
            // Initial load
            let _ = progress_tx
                .send(ModelLoadProgress::Resolving {
                    model_id: model_id.clone(),
                })
                .await;

            let engine = LlamaEngine::new(
                &model_id,
                &quantization,
                state_clone.config.inference_config.clone(),
                state_clone.config.default_params.clone(),
            )
            .await;

            match engine {
                Ok(e) => {
                    let mut guard = state_clone.engine.write().await;
                    let _ = progress_tx
                        .send(ModelLoadProgress::Loaded {
                            vocab: 0,
                            params: 0,
                        })
                        .await;
                    *guard = Some(e);
                    Ok(())
                }
                Err(e) => {
                    let _ = progress_tx
                        .send(ModelLoadProgress::Failed {
                            error: e.to_string(),
                        })
                        .await;
                    Err(e)
                }
            }
        };

        if let Err(e) = result {
            warn!("Model switch failed: {}", e);
        }
    });

    // Spawn task to forward progress to SSE
    tokio::spawn(async move {
        while let Some(progress) = progress_rx.recv().await {
            let event = match progress {
                ModelLoadProgress::Resolving { model_id } => {
                    SwitchModelEvent::Resolving { model_id }
                }
                ModelLoadProgress::Downloading {
                    model_id,
                    filename,
                } => SwitchModelEvent::Downloading {
                    model_id,
                    filename,
                    progress: None,
                },
                ModelLoadProgress::Loading { path } => SwitchModelEvent::Loading { path },
                ModelLoadProgress::Loaded { vocab, params } => {
                    SwitchModelEvent::Loaded { vocab, params }
                }
                ModelLoadProgress::Failed { error } => SwitchModelEvent::Failed { error },
            };

            if let Ok(json) = serde_json::to_string(&event) {
                if sse_tx.send(Ok(Event::default().data(json))).await.is_err() {
                    break;
                }
            }
        }
    });

    let stream = ReceiverStream::new(sse_rx);
    Sse::new(stream).keep_alive(KeepAlive::default())
}

async fn decrement_pending(state: &ServerState) {
    let mut pending = state.pending.lock().await;
    *pending = pending.saturating_sub(1);
}

fn error_response(code: ErrorCode, message: &str) -> Response {
    let event = InferEvent::Error {
        message: message.to_string(),
        code,
    };
    (StatusCode::BAD_REQUEST, Json(event)).into_response()
}
