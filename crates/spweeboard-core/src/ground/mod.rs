//! Ground context management.
//!
//! Grounds provide the contextual foundation against which SPW expressions are interpreted.
//! Grounds can themselves be SPW expressions, enabling recursive composition.
//! Multiple grounds compose according to operator metaphysics — the same rules that govern
//! symbol composition.

mod store;

pub use store::GroundStore;

use crate::spw::{parse, Expression, ParseError};
use compact_str::CompactString;
use serde::{Deserialize, Serialize};
use smol_str::SmolStr;

/// A ground context — the conceptual foundation for interpretation.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Ground {
    /// Unique identifier for this ground.
    pub id: SmolStr,
    /// Human-readable name.
    pub name: CompactString,
    /// The ground content — can be natural language or SPW.
    pub content: GroundContent,
}

impl Ground {
    /// Creates a new ground with natural language content.
    #[must_use]
    pub fn natural(id: impl Into<SmolStr>, name: impl Into<CompactString>, text: impl Into<CompactString>) -> Self {
        Self {
            id: id.into(),
            name: name.into(),
            content: GroundContent::Natural(text.into()),
        }
    }

    /// Creates a new ground with SPW expression content.
    ///
    /// # Errors
    /// Returns `ParseError` if the SPW string is malformed.
    pub fn spw(
        id: impl Into<SmolStr>,
        name: impl Into<CompactString>,
        spw: &str,
    ) -> Result<Self, ParseError> {
        let expr = parse(spw)?;
        Ok(Self {
            id: id.into(),
            name: name.into(),
            content: GroundContent::Spw(expr),
        })
    }

    /// Composes multiple grounds according to operator metaphysics.
    ///
    /// Grounds compose left-to-right, with each ground's expression appended.
    /// Natural language grounds are wrapped in scene brackets `()` to mark them as context.
    #[must_use]
    pub fn compose(grounds: &[Self]) -> Option<Self> {
        if grounds.is_empty() {
            return None;
        }

        if grounds.len() == 1 {
            return Some(grounds[0].clone());
        }

        // Compose all grounds into a single expression
        let mut composed = Expression::new();
        let mut names = Vec::with_capacity(grounds.len());

        for ground in grounds {
            names.push(ground.name.as_str());
            match &ground.content {
                GroundContent::Natural(text) => {
                    // Wrap natural language in scene brackets
                    use crate::spw::{Bracket, BracketKind, Node};
                    let inner = Expression::from_nodes(smallvec::smallvec![Node::Ident(
                        CompactString::new(text)
                    )]);
                    composed.push(Node::Bracket(Bracket::new(BracketKind::Scene, inner)));
                }
                GroundContent::Spw(expr) => {
                    for node in &expr.nodes {
                        composed.push(node.clone());
                    }
                }
            }
        }

        Some(Self {
            id: SmolStr::new("composed"),
            name: CompactString::new(names.join(" ^ ")),
            content: GroundContent::Spw(composed),
        })
    }

    /// Renders the ground to a string for prompt inclusion.
    #[must_use]
    pub fn render(&self) -> String {
        match &self.content {
            GroundContent::Natural(text) => text.to_string(),
            GroundContent::Spw(expr) => expr.render(),
        }
    }
}

/// The content of a ground — either natural language or SPW.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum GroundContent {
    /// Plain natural language description.
    Natural(CompactString),
    /// SPW expression as ground (recursive).
    Spw(Expression),
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn natural_ground() {
        let ground = Ground::natural("g1", "Software", "software development");
        assert_eq!(ground.render(), "software development");
    }

    #[test]
    fn spw_ground() {
        let ground = Ground::spw("g2", "Work Mode", "@[work]").unwrap();
        assert_eq!(ground.render(), "@[work]");
    }

    #[test]
    fn compose_grounds() {
        let g1 = Ground::spw("g1", "Craft", "@[craft]").unwrap();
        let g2 = Ground::spw("g2", "Utility", ".{utility}").unwrap();

        let composed = Ground::compose(&[g1, g2]).unwrap();
        assert_eq!(composed.render(), "@[craft].{utility}");
        assert_eq!(composed.name.as_str(), "Craft ^ Utility");
    }

    #[test]
    fn compose_mixed_grounds() {
        let g1 = Ground::natural("g1", "Context", "software");
        let g2 = Ground::spw("g2", "Mode", "@[work]").unwrap();

        let composed = Ground::compose(&[g1, g2]).unwrap();
        // Natural text gets wrapped in scene brackets
        assert!(composed.render().contains("(software)"));
        assert!(composed.render().contains("@[work]"));
    }
}
