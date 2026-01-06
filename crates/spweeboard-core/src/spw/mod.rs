//! SPW symbolic language parsing and representation.
//!
//! SPW uses 13 symbols as cognitive primitives:
//! - `~` potential (becoming)
//! - `#` vibration (resonance)
//! - `.` ground (foundation)
//! - `?` wonder (inquiry)
//! - `!` action (assertion)
//! - `*` value (significance)
//! - `&` subject (agent)
//! - `@` perspective (viewpoint)
//! - `<>` concept (abstraction)
//! - `()` scene (situation)
//! - `[]` mode (manner)
//! - `{}` direction (intent)
//! - `^` integration (synthesis)

mod symbol;
mod token;
mod lexer;
mod ast;
mod parser;

pub use symbol::Symbol;
pub use token::{Token, TokenKind};
pub use lexer::Lexer;
pub use ast::{Expression, Node, Bracket, BracketKind};
pub use parser::{Parser, ParseError};

/// Parses a SPW string into an expression tree.
///
/// # Errors
/// Returns `ParseError` if the input is malformed.
pub fn parse(input: &str) -> Result<Expression, ParseError> {
    let tokens = Lexer::new(input).collect::<Vec<_>>();
    Parser::new(&tokens).parse()
}
