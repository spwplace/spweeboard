//! # spweeboard-core
//!
//! Core library for the spweebo'ard keyboard application.
//!
//! This crate provides:
//! - SPW expression parsing and representation
//! - Ground context management
//! - Prompt compilation for LLM inference
//! - Platform-agnostic inference engine abstraction

#![forbid(unsafe_code)]

pub mod spw;
pub mod ground;
pub mod compiler;
pub mod inference;
pub mod buffer;

pub use compiler::PromptCompiler;

#[cfg(feature = "uniffi")]
uniffi::setup_scaffolding!();

use crate::spw::Expression;
use crate::ground::Ground;
use crate::inference::InferenceEngine;
use crate::buffer::ExpressionBuffer;
use tracing::{trace, debug, info, instrument};

/// The main keyboard engine coordinating SPW parsing, ground management, and LLM inference.
pub struct SpweeboardEngine<I: InferenceEngine> {
    buffer: ExpressionBuffer,
    grounds: Vec<Ground>,
    compiler: PromptCompiler,
    inference: I,
}

impl<I: InferenceEngine> SpweeboardEngine<I> {
    /// Creates a new engine with the given inference backend.
    #[instrument(skip(inference), level = "debug")]
    pub fn new(inference: I) -> Self {
        info!("Creating new SpweeboardEngine");
        Self {
            buffer: ExpressionBuffer::new(),
            grounds: Vec::new(),
            compiler: PromptCompiler::default(),
            inference,
        }
    }

    /// Creates a new engine with a custom prompt compiler.
    #[instrument(skip(inference, compiler), level = "debug")]
    pub fn with_compiler(inference: I, compiler: PromptCompiler) -> Self {
        info!("Creating new SpweeboardEngine with custom compiler");
        Self {
            buffer: ExpressionBuffer::new(),
            grounds: Vec::new(),
            compiler,
            inference,
        }
    }

    /// Updates the prompt template at runtime.
    pub fn set_prompt_template(&mut self, template: String) {
        self.compiler.set_template(template);
    }

    /// Returns the current prompt template.
    #[must_use]
    pub fn prompt_template(&self) -> &str {
        self.compiler.template()
    }

    /// Pushes a symbol or character to the current expression buffer.
    #[instrument(skip(self), level = "trace")]
    pub fn push(&mut self, input: char) {
        trace!(input = %input, "Pushing character to buffer");
        self.buffer.push(input);
        trace!(buffer = ?self.buffer.raw(), "Buffer state after push");
    }

    /// Removes the last symbol or character from the buffer.
    #[instrument(skip(self), level = "trace")]
    pub fn pop(&mut self) {
        trace!("Popping from buffer");
        self.buffer.pop();
    }

    /// Clears the current expression buffer.
    #[instrument(skip(self), level = "debug")]
    pub fn clear(&mut self) {
        debug!("Clearing buffer");
        self.buffer.clear();
    }

    /// Returns the current expression being composed.
    pub fn current_expression(&self) -> Option<&Expression> {
        self.buffer.current()
    }

    /// Loads a ground context. Grounds compose according to SPW operator metaphysics.
    #[instrument(skip(self), level = "debug")]
    pub fn load_ground(&mut self, ground: Ground) {
        debug!(ground = ?ground, "Loading ground");
        self.grounds.push(ground);
    }

    /// Clears all loaded grounds.
    #[instrument(skip(self), level = "debug")]
    pub fn clear_grounds(&mut self) {
        debug!("Clearing all grounds");
        self.grounds.clear();
    }

    /// Returns the composed ground (all grounds merged by operator metaphysics).
    pub fn composed_ground(&self) -> Option<Ground> {
        let composed = Ground::compose(&self.grounds);
        trace!(composed = ?composed, "Composed ground");
        composed
    }

    /// Compiles the current expression against loaded grounds into an LLM prompt.
    #[instrument(skip(self), level = "debug")]
    pub fn compile_prompt(&self) -> Option<String> {
        let expr = self.buffer.current()?;
        debug!(expression = ?expr, "Compiling prompt for expression");
        let ground = self.composed_ground();
        let prompt = self.compiler.compile(expr, ground.as_ref());
        trace!(prompt_len = prompt.len(), "Compiled prompt");
        debug!(prompt = %prompt, "Full compiled prompt");
        Some(prompt)
    }

    /// Generates natural language from the current expression (blocking).
    #[instrument(skip(self), level = "info")]
    pub async fn generate(&self) -> Result<String, I::Error> {
        info!("Starting generation");
        let prompt = self.compile_prompt().unwrap_or_default();
        trace!(prompt = %prompt, "Sending prompt to inference engine");
        let result = self.inference.generate(&prompt).await;
        match &result {
            Ok(output) => info!(output_len = output.len(), "Generation complete"),
            Err(_) => info!("Generation failed"),
        }
        result
    }

    /// Streams natural language tokens from the current expression.
    #[instrument(skip(self), level = "info")]
    pub async fn stream(
        &self,
    ) -> Result<tokio::sync::mpsc::Receiver<String>, I::Error> {
        info!("Starting streaming generation");
        let prompt = self.compile_prompt().unwrap_or_default();
        self.inference.stream(&prompt).await
    }

    /// Commits the current expression to history and clears the buffer.
    #[instrument(skip(self), level = "debug")]
    pub fn commit(&mut self) {
        debug!("Committing expression to history");
        self.buffer.commit();
    }
}
