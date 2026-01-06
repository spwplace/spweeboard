//! Token representation for SPW lexing.

use crate::spw::Symbol;
use compact_str::CompactString;
use derive_more::Display;
use serde::{Deserialize, Serialize};

/// A token in the SPW language.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Token {
    /// The kind of token.
    pub kind: TokenKind,
    /// Byte offset in the source string.
    pub span: Span,
}

/// Byte span in source.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct Span {
    /// Start byte offset (inclusive).
    pub start: usize,
    /// End byte offset (exclusive).
    pub end: usize,
}

impl Span {
    /// Creates a new span.
    #[must_use]
    pub const fn new(start: usize, end: usize) -> Self {
        Self { start, end }
    }

    /// Length of the span in bytes.
    #[must_use]
    pub const fn len(&self) -> usize {
        self.end - self.start
    }

    /// Whether the span is empty.
    #[must_use]
    pub const fn is_empty(&self) -> bool {
        self.start == self.end
    }
}

/// The kind of token produced by the lexer.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Display)]
pub enum TokenKind {
    /// A SPW symbol (one of the 13 cognitive primitives).
    #[display("{_0}")]
    Symbol(Symbol),

    /// Opening bracket: `<`, `(`, `[`, or `{`.
    #[display("{_0}")]
    OpenBracket(BracketType),

    /// Closing bracket: `>`, `)`, `]`, or `}`.
    #[display("{_0}")]
    CloseBracket(BracketType),

    /// A literal identifier (alphanumeric word).
    #[display("{_0}")]
    Ident(CompactString),

    /// A quoted string literal.
    #[display("\"{_0}\"")]
    String(CompactString),

    /// Whitespace (preserved for reconstruction).
    #[display(" ")]
    Whitespace,

    /// A comma separator.
    #[display(",")]
    Comma,

    /// A semicolon separator.
    #[display(";")]
    Semicolon,

    /// Right arrow `->` (flow, causation, transformation).
    #[display("->")]
    ArrowRight,

    /// Left arrow `<-` (origin, source, derivation).
    #[display("<-")]
    ArrowLeft,

    /// End of input.
    #[display("EOF")]
    Eof,

    /// Unrecognized character.
    #[display("?{_0}")]
    Unknown(char),
}

/// Types of brackets in SPW.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, Display)]
pub enum BracketType {
    /// `<>` — Concept brackets (abstraction, idea, category)
    #[display("<>")]
    Concept,

    /// `()` — Scene brackets (situation, context, narrative)
    #[display("()")]
    Scene,

    /// `[]` — Mode brackets (state, manner, operational frame)
    #[display("[]")]
    Mode,

    /// `{}` — Direction brackets (vector, intent, trajectory)
    #[display("{{}}")]
    Direction,
}

impl BracketType {
    /// Returns the opening character for this bracket type.
    #[must_use]
    pub const fn open_char(self) -> char {
        match self {
            Self::Concept => '<',
            Self::Scene => '(',
            Self::Mode => '[',
            Self::Direction => '{',
        }
    }

    /// Returns the closing character for this bracket type.
    #[must_use]
    pub const fn close_char(self) -> char {
        match self {
            Self::Concept => '>',
            Self::Scene => ')',
            Self::Mode => ']',
            Self::Direction => '}',
        }
    }

    /// Attempts to parse an opening bracket character.
    #[must_use]
    pub const fn from_open(c: char) -> Option<Self> {
        match c {
            '<' => Some(Self::Concept),
            '(' => Some(Self::Scene),
            '[' => Some(Self::Mode),
            '{' => Some(Self::Direction),
            _ => None,
        }
    }

    /// Attempts to parse a closing bracket character.
    #[must_use]
    pub const fn from_close(c: char) -> Option<Self> {
        match c {
            '>' => Some(Self::Concept),
            ')' => Some(Self::Scene),
            ']' => Some(Self::Mode),
            '}' => Some(Self::Direction),
            _ => None,
        }
    }

    /// Returns the semantic name of this bracket type.
    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            Self::Concept => "concept",
            Self::Scene => "scene",
            Self::Mode => "mode",
            Self::Direction => "direction",
        }
    }

    /// Returns the semantic lore of this bracket type.
    #[must_use]
    pub const fn lore(self) -> &'static str {
        match self {
            Self::Concept => "abstraction, idea, category",
            Self::Scene => "situation, context, narrative",
            Self::Mode => "state, manner, operational frame",
            Self::Direction => "vector, intent, trajectory",
        }
    }
}
