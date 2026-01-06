//! Expression history tracking.

use crate::spw::Expression;
use smallvec::SmallVec;

/// History of committed expressions.
#[derive(Debug, Clone)]
pub struct History {
    entries: SmallVec<[Expression; 32]>,
    max_size: usize,
}

impl Default for History {
    fn default() -> Self {
        Self::new()
    }
}

impl History {
    /// Creates a new history with default max size (100).
    #[must_use]
    pub fn new() -> Self {
        Self {
            entries: SmallVec::new(),
            max_size: 100,
        }
    }

    /// Creates a history with custom max size.
    #[must_use]
    pub const fn with_max_size(max_size: usize) -> Self {
        Self {
            entries: SmallVec::new_const(),
            max_size,
        }
    }

    /// Pushes an expression to history.
    pub fn push(&mut self, expr: Expression) {
        // Don't add duplicates consecutively
        if self.entries.last() == Some(&expr) {
            return;
        }

        self.entries.push(expr);

        // Trim to max size
        while self.entries.len() > self.max_size {
            self.entries.remove(0);
        }
    }

    /// Gets an expression by index (0 = oldest).
    #[must_use]
    pub fn get(&self, index: usize) -> Option<&Expression> {
        self.entries.get(index)
    }

    /// Gets the most recent expression.
    #[must_use]
    pub fn last(&self) -> Option<&Expression> {
        self.entries.last()
    }

    /// Returns the number of entries.
    #[must_use]
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    /// Returns whether history is empty.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Clears all history.
    pub fn clear(&mut self) {
        self.entries.clear();
    }

    /// Returns an iterator over history entries (oldest first).
    pub fn iter(&self) -> impl Iterator<Item = &Expression> {
        self.entries.iter()
    }

    /// Returns an iterator over history entries (newest first).
    pub fn iter_rev(&self) -> impl Iterator<Item = &Expression> {
        self.entries.iter().rev()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::spw::parse;

    #[test]
    fn push_and_retrieve() {
        let mut history = History::new();
        let expr = parse("&@").unwrap();

        history.push(expr.clone());

        assert_eq!(history.len(), 1);
        assert_eq!(history.get(0), Some(&expr));
        assert_eq!(history.last(), Some(&expr));
    }

    #[test]
    fn no_consecutive_duplicates() {
        let mut history = History::new();
        let expr = parse("&@").unwrap();

        history.push(expr.clone());
        history.push(expr.clone());
        history.push(expr);

        assert_eq!(history.len(), 1);
    }

    #[test]
    fn max_size_enforced() {
        let mut history = History::with_max_size(3);

        for i in 0..5 {
            let expr = parse(&format!("&{}", "~".repeat(i))).unwrap();
            history.push(expr);
        }

        assert_eq!(history.len(), 3);
        // Oldest entries should be removed
        assert!(history.get(0).unwrap().render().contains("~~"));
    }
}
