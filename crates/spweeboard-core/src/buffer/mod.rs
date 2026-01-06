//! Expression buffer for incremental composition.
//!
//! Manages the current expression being typed, with support for
//! character-by-character input, deletion, and history.

mod history;

pub use history::History;

use crate::spw::{parse, Expression, ParseError};
use compact_str::CompactString;
use tracing::trace;

/// Buffer for composing SPW expressions incrementally.
#[derive(Debug, Clone, Default)]
pub struct ExpressionBuffer {
    /// Raw input string.
    raw: CompactString,
    /// Parsed expression (updated on each change).
    parsed: Option<Expression>,
    /// Parse error if current input is invalid.
    error: Option<ParseError>,
    /// History of committed expressions.
    history: History,
}

impl ExpressionBuffer {
    /// Creates a new empty buffer.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Pushes a character to the buffer.
    pub fn push(&mut self, c: char) {
        self.raw.push(c);
        self.reparse();
    }

    /// Pushes a string to the buffer.
    pub fn push_str(&mut self, s: &str) {
        self.raw.push_str(s);
        self.reparse();
    }

    /// Removes the last character from the buffer.
    pub fn pop(&mut self) {
        self.raw.pop();
        self.reparse();
    }

    /// Clears the buffer.
    pub fn clear(&mut self) {
        self.raw.clear();
        self.parsed = None;
        self.error = None;
    }

    /// Returns the raw input string.
    #[must_use]
    pub fn raw(&self) -> &str {
        &self.raw
    }

    /// Returns the current parsed expression, if valid.
    #[must_use]
    pub fn current(&self) -> Option<&Expression> {
        self.parsed.as_ref()
    }

    /// Returns the current parse error, if any.
    #[must_use]
    pub fn error(&self) -> Option<&ParseError> {
        self.error.as_ref()
    }

    /// Returns whether the buffer is empty.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.raw.is_empty()
    }

    /// Returns the length of the raw input.
    #[must_use]
    pub fn len(&self) -> usize {
        self.raw.len()
    }

    /// Commits the current expression to history and clears the buffer.
    pub fn commit(&mut self) {
        if let Some(expr) = self.parsed.take() {
            self.history.push(expr);
        }
        self.clear();
    }

    /// Returns the expression history.
    #[must_use]
    pub const fn history(&self) -> &History {
        &self.history
    }

    /// Replaces the buffer content with a previous expression from history.
    pub fn recall(&mut self, index: usize) {
        if let Some(expr) = self.history.get(index) {
            self.raw = CompactString::new(expr.render());
            self.reparse();
        }
    }

    /// Re-parses the raw input.
    fn reparse(&mut self) {
        if self.raw.is_empty() {
            self.parsed = Some(Expression::new());
            self.error = None;
            return;
        }

        match parse(&self.raw) {
            Ok(expr) => {
                trace!(expression = ?expr, "Parse successful");
                self.parsed = Some(expr);
                self.error = None;
            }
            Err(e) => {
                trace!(error = ?e, "Parse failed");
                // Keep partial parse if possible
                self.parsed = None;
                self.error = Some(e);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn incremental_input() {
        let mut buf = ExpressionBuffer::new();

        buf.push('&');
        assert_eq!(buf.raw(), "&");
        assert!(buf.current().is_some());

        buf.push('@');
        assert_eq!(buf.raw(), "&@");
        assert_eq!(buf.current().unwrap().render(), "&@");
    }

    #[test]
    fn pop_character() {
        let mut buf = ExpressionBuffer::new();
        buf.push_str("&@~");
        buf.pop();

        assert_eq!(buf.raw(), "&@");
    }

    #[test]
    fn commit_to_history() {
        let mut buf = ExpressionBuffer::new();
        buf.push_str("&@");
        buf.commit();

        assert!(buf.is_empty());
        assert_eq!(buf.history().len(), 1);
    }

    #[test]
    fn recall_from_history() {
        let mut buf = ExpressionBuffer::new();
        buf.push_str("&@");
        buf.commit();

        buf.push_str("?!");
        buf.commit();

        buf.recall(0);
        assert_eq!(buf.raw(), "&@");
    }
}
