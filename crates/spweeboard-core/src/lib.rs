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

#[cfg(feature = "uniffi")]
uniffi::setup_scaffolding!();

use crate::spw::Expression;
use crate::ground::Ground;
use crate::compiler::PromptCompiler;
use crate::inference::InferenceEngine;
use crate::buffer::ExpressionBuffer;

/// The main keyboard engine coordinating SPW parsing, ground management, and LLM inference.
pub struct SpweeboardEngine<I: InferenceEngine> {
    buffer: ExpressionBuffer,
    grounds: Vec<Ground>,
    compiler: PromptCompiler,
    inference: I,
}

impl<I: InferenceEngine> SpweeboardEngine<I> {
    /// Creates a new engine with the given inference backend.
    pub fn new(inference: I) -> Self {
        Self {
            buffer: ExpressionBuffer::new(),
            grounds: Vec::new(),
            compiler: PromptCompiler::default(),
            inference,
        }
    }

    /// Pushes a symbol or character to the current expression buffer.
    pub fn push(&mut self, input: char) {
        self.buffer.push(input);
    }

    /// Removes the last symbol or character from the buffer.
    pub fn pop(&mut self) {
        self.buffer.pop();
    }

    /// Clears the current expression buffer.
    pub fn clear(&mut self) {
        self.buffer.clear();
    }

    /// Returns the current expression being composed.
    pub fn current_expression(&self) -> Option<&Expression> {
        self.buffer.current()
    }

    /// Loads a ground context. Grounds compose according to SPW operator metaphysics.
    pub fn load_ground(&mut self, ground: Ground) {
        self.grounds.push(ground);
    }

    /// Clears all loaded grounds.
    pub fn clear_grounds(&mut self) {
        self.grounds.clear();
    }

    /// Returns the composed ground (all grounds merged by operator metaphysics).
    pub fn composed_ground(&self) -> Option<Ground> {
        Ground::compose(&self.grounds)
    }

    /// Compiles the current expression against loaded grounds into an LLM prompt.
    pub fn compile_prompt(&self) -> Option<String> {
        let expr = self.buffer.current()?;
        let ground = self.composed_ground();
        Some(self.compiler.compile(expr, ground.as_ref()))
    }

    /// Generates natural language from the current expression (blocking).
    pub async fn generate(&self) -> Result<String, I::Error> {
        let prompt = self.compile_prompt().unwrap_or_default();
        self.inference.generate(&prompt).await
    }

    /// Streams natural language tokens from the current expression.
    pub async fn stream(
        &self,
    ) -> Result<tokio::sync::mpsc::Receiver<String>, I::Error> {
        let prompt = self.compile_prompt().unwrap_or_default();
        self.inference.stream(&prompt).await
    }

    /// Commits the current expression to history and clears the buffer.
    pub fn commit(&mut self) {
        self.buffer.commit();
    }
}
