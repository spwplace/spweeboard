//! Prompt compilation from SPW expressions.
//!
//! Compiles SPW expressions and grounds into structured prompts for LLM inference.
//! The compiler handles in-context learning examples and prompt budgeting.

mod prompt;

pub use prompt::PromptCompiler;

use crate::ground::Ground;
use crate::spw::Expression;

/// Compiles an expression and optional ground into an LLM prompt.
#[must_use]
pub fn compile(expression: &Expression, ground: Option<&Ground>) -> String {
    PromptCompiler::default().compile(expression, ground)
}
