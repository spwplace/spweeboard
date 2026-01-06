//! Abstract Syntax Tree for SPW expressions.

use crate::spw::symbol::Symbol;
use crate::spw::token::BracketType;
use compact_str::CompactString;
use derive_more::Display;
use serde::{Deserialize, Serialize};
use smallvec::SmallVec;

/// A complete SPW expression — a sequence of nodes that compose left-to-right.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct Expression {
    /// The nodes comprising this expression, in order.
    pub nodes: SmallVec<[Node; 8]>,
}

impl Expression {
    /// Creates an empty expression.
    #[must_use]
    pub const fn new() -> Self {
        Self {
            nodes: SmallVec::new_const(),
        }
    }

    /// Creates an expression from a vector of nodes.
    #[must_use]
    pub fn from_nodes(nodes: impl Into<SmallVec<[Node; 8]>>) -> Self {
        Self {
            nodes: nodes.into(),
        }
    }

    /// Returns true if the expression has no nodes.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.nodes.is_empty()
    }

    /// Returns the number of nodes in the expression.
    #[must_use]
    pub fn len(&self) -> usize {
        self.nodes.len()
    }

    /// Pushes a node to the expression.
    pub fn push(&mut self, node: Node) {
        self.nodes.push(node);
    }

    /// Renders the expression back to SPW string form.
    #[must_use]
    pub fn render(&self) -> String {
        let mut out = String::new();
        for node in &self.nodes {
            node.render_into(&mut out);
        }
        out
    }
}

impl std::fmt::Display for Expression {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.render())
    }
}

/// Arrow direction in SPW.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, Display)]
pub enum ArrowDir {
    /// `->` — Right arrow (flow, causation, transformation)
    #[display("->")]
    Right,

    /// `<-` — Left arrow (origin, source, derivation)
    #[display("<-")]
    Left,
}

/// A node in the SPW expression tree.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Display)]
pub enum Node {
    /// A bare symbol (e.g., `&`, `@`, `~`).
    #[display("{_0}")]
    Symbol(Symbol),

    /// A bracketed group with optional contents.
    #[display("{_0}")]
    Bracket(Bracket),

    /// A literal identifier (e.g., `Worcester`, `Henry`).
    #[display("{_0}")]
    Ident(CompactString),

    /// A string literal (e.g., `"of[Worcester]"`).
    #[display("\"{_0}\"")]
    String(CompactString),

    /// A sequence separated by commas (e.g., `[Henry, Worcester]`).
    #[display("seq")]
    Sequence(Vec<Expression>),

    /// An arrow (`->` or `<-`).
    #[display("{_0}")]
    Arrow(ArrowDir),
}

impl Node {
    /// Renders this node into a string buffer.
    pub fn render_into(&self, out: &mut String) {
        match self {
            Self::Symbol(s) => out.push(s.as_char()),
            Self::Bracket(b) => b.render_into(out),
            Self::Ident(id) => out.push_str(id),
            Self::String(s) => {
                out.push('"');
                out.push_str(s);
                out.push('"');
            }
            Self::Sequence(exprs) => {
                for (i, expr) in exprs.iter().enumerate() {
                    if i > 0 {
                        out.push_str(", ");
                    }
                    out.push_str(&expr.render());
                }
            }
            Self::Arrow(dir) => match dir {
                ArrowDir::Right => out.push_str("->"),
                ArrowDir::Left => out.push_str("<-"),
            },
        }
    }
}

/// A bracketed group in SPW — brackets carry semantic meaning.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Bracket {
    /// The type of bracket (concept, scene, mode, direction).
    pub kind: BracketKind,
    /// The contents of the bracket.
    pub contents: Box<Expression>,
}

impl Bracket {
    /// Creates a new bracket with the given kind and contents.
    #[must_use]
    pub fn new(kind: BracketKind, contents: Expression) -> Self {
        Self {
            kind,
            contents: Box::new(contents),
        }
    }

    /// Renders this bracket into a string buffer.
    pub fn render_into(&self, out: &mut String) {
        let (open, close) = self.kind.chars();
        out.push(open);
        out.push_str(&self.contents.render());
        out.push(close);
    }
}

impl std::fmt::Display for Bracket {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let (open, close) = self.kind.chars();
        write!(f, "{}{}{}", open, self.contents, close)
    }
}

/// The semantic kind of bracket.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, Display)]
pub enum BracketKind {
    /// `<>` — Concept: abstraction, idea, category
    #[display("<>")]
    Concept,

    /// `()` — Scene: situation, context, narrative
    #[display("()")]
    Scene,

    /// `[]` — Mode: state, manner, operational frame
    #[display("[]")]
    Mode,

    /// `{}` — Direction: vector, intent, trajectory
    #[display("{{}}")]
    Direction,
}

impl BracketKind {
    /// Returns the opening and closing characters for this bracket kind.
    #[must_use]
    pub const fn chars(self) -> (char, char) {
        match self {
            Self::Concept => ('<', '>'),
            Self::Scene => ('(', ')'),
            Self::Mode => ('[', ']'),
            Self::Direction => ('{', '}'),
        }
    }
}

impl From<BracketType> for BracketKind {
    fn from(bt: BracketType) -> Self {
        match bt {
            BracketType::Concept => Self::Concept,
            BracketType::Scene => Self::Scene,
            BracketType::Mode => Self::Mode,
            BracketType::Direction => Self::Direction,
        }
    }
}
