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
    /// Human-readable description of what this ground does.
    pub description: CompactString,
    /// Category for grouping (e.g., "Work", "Creative", "Philosophical").
    pub category: CompactString,
}

impl Ground {
    /// Creates a new ground with natural language content.
    #[must_use]
    pub fn natural(
        id: impl Into<SmolStr>,
        name: impl Into<CompactString>,
        text: impl Into<CompactString>,
        description: impl Into<CompactString>,
        category: impl Into<CompactString>,
    ) -> Self {
        Self {
            id: id.into(),
            name: name.into(),
            content: GroundContent::Natural(text.into()),
            description: description.into(),
            category: category.into(),
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
        description: impl Into<CompactString>,
        category: impl Into<CompactString>,
    ) -> Result<Self, ParseError> {
        let expr = parse(spw)?;
        Ok(Self {
            id: id.into(),
            name: name.into(),
            content: GroundContent::Spw(expr),
            description: description.into(),
            category: category.into(),
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
            description: CompactString::new("Composed ground"),
            category: CompactString::new("Composed"),
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

/// Returns the preset ground contexts available out of the box.
///
/// These provide common contextual foundations for SPW interpretation.
pub fn preset_grounds() -> Vec<Ground> {
    vec![
        Ground::spw(
            "software",
            "Software Development",
            ".{software}",
            "Grounded toward software creation",
            "Work",
        ).expect("valid spw"),
        Ground::spw(
            "craft",
            "Craftsperson",
            "@[craft].{utility}",
            "Perspective in craft mode, grounded in usefulness",
            "Work",
        ).expect("valid spw"),
        Ground::spw(
            "poetry",
            "Poetic Voice",
            "@[poetry]~",
            "Poetry's becoming perspective",
            "Creative",
        ).expect("valid spw"),
        Ground::spw(
            "inquiry",
            "Deep Inquiry",
            "?{&@.}",
            "Wondering about subject-perspective-ground flow",
            "Philosophical",
        ).expect("valid spw"),
        Ground::spw(
            "becoming",
            "Becoming",
            "&~*^",
            "Subject becoming through value integration",
            "Philosophical",
        ).expect("valid spw"),
        Ground::spw(
            "resonance",
            "Resonance",
            "#.&",
            "Vibration grounding the subject",
            "Creative",
        ).expect("valid spw"),
        Ground::spw(
            "action",
            "Action-Oriented",
            "!{*}",
            "Asserting toward value",
            "Work",
        ).expect("valid spw"),
        Ground::spw(
            "reflection",
            "Reflective",
            "@&.",
            "Perspective on subject's foundation",
            "Philosophical",
        ).expect("valid spw"),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_preset_grounds() {
        let grounds = preset_grounds();
        assert!(!grounds.is_empty());
        assert!(grounds.iter().any(|g| g.id == "software"));
        // Verify description and category are set
        let software = grounds.iter().find(|g| g.id == "software").unwrap();
        assert!(!software.description.is_empty());
        assert_eq!(software.category.as_str(), "Work");
    }

    #[test]
    fn natural_ground() {
        let ground = Ground::natural("g1", "Software", "software development", "Test desc", "Test");
        assert_eq!(ground.render(), "software development");
        assert_eq!(ground.description.as_str(), "Test desc");
        assert_eq!(ground.category.as_str(), "Test");
    }

    #[test]
    fn spw_ground() {
        let ground = Ground::spw("g2", "Work Mode", "@[work]", "Work mode desc", "Work").unwrap();
        assert_eq!(ground.render(), "@[work]");
        assert_eq!(ground.category.as_str(), "Work");
    }

    #[test]
    fn compose_grounds() {
        let g1 = Ground::spw("g1", "Craft", "@[craft]", "Craft desc", "Work").unwrap();
        let g2 = Ground::spw("g2", "Utility", ".{utility}", "Utility desc", "Work").unwrap();

        let composed = Ground::compose(&[g1, g2]).unwrap();
        assert_eq!(composed.render(), "@[craft].{utility}");
        assert_eq!(composed.name.as_str(), "Craft ^ Utility");
    }

    #[test]
    fn compose_mixed_grounds() {
        let g1 = Ground::natural("g1", "Context", "software", "Context desc", "Test");
        let g2 = Ground::spw("g2", "Mode", "@[work]", "Mode desc", "Work").unwrap();

        let composed = Ground::compose(&[g1, g2]).unwrap();
        // Natural text gets wrapped in scene brackets
        assert!(composed.render().contains("(software)"));
        assert!(composed.render().contains("@[work]"));
    }
}
