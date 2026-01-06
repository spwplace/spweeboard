//! Inter-process communication for keyboard extension ↔ main app.
//!
//! iOS keyboard extensions have a 50MB memory limit, making it impossible to load
//! LLM models directly. This module provides:
//!
//! - **Server** (main app): Hosts the LLM, listens for inference requests
//! - **Client** (keyboard): Lightweight HTTP client, streams responses
//!
//! Communication uses HTTP over localhost with Server-Sent Events (SSE) for streaming.
//!
//! # Architecture
//!
//! ```text
//! ┌─────────────────────────┐         ┌─────────────────────────┐
//! │      Main App           │         │   Keyboard Extension    │
//! │  ┌─────────────────┐   │  HTTP   │  ┌─────────────────┐   │
//! │  │ InferenceServer │◄──┼─────────┼──│ InferenceClient │   │
//! │  │  (LlamaEngine)  │   │ :19284  │  │   (<50MB total) │   │
//! │  └─────────────────┘   │         │  └─────────────────┘   │
//! └─────────────────────────┘         └─────────────────────────┘
//! ```

mod protocol;

#[cfg(feature = "ipc-server")]
mod server;

#[cfg(feature = "ipc-client")]
mod client;

pub use protocol::*;

#[cfg(feature = "ipc-server")]
pub use server::InferenceServer;

#[cfg(feature = "ipc-client")]
pub use client::InferenceClient;

/// Default port for the inference server.
pub const DEFAULT_PORT: u16 = 19284;

/// Localhost address for IPC (keyboard extensions can only reach localhost).
pub const LOCALHOST: &str = "127.0.0.1";
