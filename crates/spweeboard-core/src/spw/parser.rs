//! Parser for SPW expressions.

use crate::spw::ast::{ArrowDir, Bracket, BracketKind, Expression, Node};
use crate::spw::token::{BracketType, Span, Token, TokenKind};
use smallvec::SmallVec;
use thiserror::Error;

/// Errors that can occur during parsing.
#[derive(Debug, Error, Clone, PartialEq, Eq)]
pub enum ParseError {
    /// Unexpected token encountered.
    #[error("unexpected token {found:?} at position {span:?}, expected {expected}")]
    Unexpected {
        found: TokenKind,
        span: Span,
        expected: &'static str,
    },

    /// Unmatched opening bracket.
    #[error("unmatched opening bracket {kind:?} at position {span:?}")]
    UnmatchedOpen { kind: BracketType, span: Span },

    /// Unmatched closing bracket.
    #[error("unmatched closing bracket {kind:?} at position {span:?}")]
    UnmatchedClose { kind: BracketType, span: Span },

    /// Mismatched bracket types.
    #[error("mismatched brackets: opened with {open:?} but closed with {close:?}")]
    MismatchedBrackets {
        open: BracketType,
        close: BracketType,
    },
}

/// Parser for SPW token streams.
pub struct Parser<'tok> {
    tokens: &'tok [Token],
    position: usize,
}

impl<'tok> Parser<'tok> {
    /// Creates a new parser for the given token slice.
    #[must_use]
    pub const fn new(tokens: &'tok [Token]) -> Self {
        Self {
            tokens,
            position: 0,
        }
    }

    /// Returns the current token without consuming it.
    fn peek(&self) -> Option<&Token> {
        self.tokens.get(self.position)
    }

    /// Consumes and returns the current token.
    fn advance(&mut self) -> Option<&Token> {
        let token = self.tokens.get(self.position);
        if token.is_some() {
            self.position += 1;
        }
        token
    }

    /// Checks if we're at the end of input.
    fn is_at_end(&self) -> bool {
        self.position >= self.tokens.len()
    }

    /// Parses a complete expression.
    ///
    /// # Errors
    /// Returns `ParseError` if the token stream is malformed.
    pub fn parse(&mut self) -> Result<Expression, ParseError> {
        self.parse_expression(&[])
    }

    /// Parses an expression until we hit a closing bracket or end of input.
    fn parse_expression(&mut self, stop_at: &[BracketType]) -> Result<Expression, ParseError> {
        let mut nodes = SmallVec::new();

        while let Some(token) = self.peek() {
            // Skip whitespace
            if token.kind == TokenKind::Whitespace {
                self.advance();
                continue;
            }

            // Check for stopping conditions
            if let TokenKind::CloseBracket(bt) = &token.kind {
                if stop_at.contains(bt) {
                    break;
                }
            }

            // Parse the next node
            match &token.kind {
                TokenKind::Symbol(s) => {
                    let symbol = *s;
                    self.advance();
                    nodes.push(Node::Symbol(symbol));
                }

                TokenKind::OpenBracket(bt) => {
                    let bracket_type = *bt;
                    let span = token.span;
                    self.advance();

                    // Parse contents until matching close
                    let contents = self.parse_expression(&[bracket_type])?;

                    // Expect closing bracket
                    match self.peek() {
                        Some(Token {
                            kind: TokenKind::CloseBracket(close_bt),
                            ..
                        }) if *close_bt == bracket_type => {
                            self.advance();
                        }
                        Some(Token {
                            kind: TokenKind::CloseBracket(close_bt),
                            ..
                        }) => {
                            return Err(ParseError::MismatchedBrackets {
                                open: bracket_type,
                                close: *close_bt,
                            });
                        }
                        _ => {
                            return Err(ParseError::UnmatchedOpen {
                                kind: bracket_type,
                                span,
                            });
                        }
                    }

                    nodes.push(Node::Bracket(Bracket::new(
                        BracketKind::from(bracket_type),
                        contents,
                    )));
                }

                TokenKind::CloseBracket(bt) => {
                    let bracket_type = *bt;
                    let span = token.span;
                    return Err(ParseError::UnmatchedClose {
                        kind: bracket_type,
                        span,
                    });
                }

                TokenKind::Ident(id) => {
                    let ident = id.clone();
                    self.advance();
                    nodes.push(Node::Ident(ident));
                }

                TokenKind::String(s) => {
                    let string = s.clone();
                    self.advance();
                    nodes.push(Node::String(string));
                }

                TokenKind::ArrowRight => {
                    self.advance();
                    nodes.push(Node::Arrow(ArrowDir::Right));
                }

                TokenKind::ArrowLeft => {
                    self.advance();
                    nodes.push(Node::Arrow(ArrowDir::Left));
                }

                TokenKind::Comma => {
                    // Start collecting a sequence
                    let first = Expression::from_nodes(nodes);
                    nodes = SmallVec::new();
                    self.advance();

                    let mut sequence: Vec<Expression> = Vec::new();
                    sequence.push(first);

                    loop {
                        let rest = self.parse_sequence_element(stop_at)?;
                        sequence.push(rest);

                        // Check for more commas
                        if let Some(Token {
                            kind: TokenKind::Comma,
                            ..
                        }) = self.peek()
                        {
                            self.advance();
                        } else {
                            break;
                        }
                    }

                    nodes.push(Node::Sequence(sequence));
                }

                TokenKind::Semicolon => {
                    // Semicolons act as statement separators within brackets
                    self.advance();
                }

                TokenKind::Whitespace | TokenKind::Eof => {
                    self.advance();
                }

                TokenKind::Unknown(_) => {
                    // Skip unknown characters
                    self.advance();
                }
            }
        }

        Ok(Expression::from_nodes(nodes))
    }

    /// Parses a single element of a comma-separated sequence.
    fn parse_sequence_element(&mut self, stop_at: &[BracketType]) -> Result<Expression, ParseError> {
        let mut nodes = SmallVec::new();

        while let Some(token) = self.peek() {
            // Skip whitespace
            if token.kind == TokenKind::Whitespace {
                self.advance();
                continue;
            }

            // Stop at comma, closing bracket, or semicolon
            match &token.kind {
                TokenKind::Comma | TokenKind::Semicolon => break,
                TokenKind::CloseBracket(bt) if stop_at.contains(bt) => break,
                _ => {}
            }

            // Parse node (simplified for sequence elements)
            match &token.kind {
                TokenKind::Symbol(s) => {
                    let symbol = *s;
                    self.advance();
                    nodes.push(Node::Symbol(symbol));
                }
                TokenKind::Ident(id) => {
                    let ident = id.clone();
                    self.advance();
                    nodes.push(Node::Ident(ident));
                }
                TokenKind::String(s) => {
                    let string = s.clone();
                    self.advance();
                    nodes.push(Node::String(string));
                }
                TokenKind::ArrowRight => {
                    self.advance();
                    nodes.push(Node::Arrow(ArrowDir::Right));
                }
                TokenKind::ArrowLeft => {
                    self.advance();
                    nodes.push(Node::Arrow(ArrowDir::Left));
                }
                TokenKind::OpenBracket(bt) => {
                    let bracket_type = *bt;
                    let span = token.span;
                    self.advance();

                    let contents = self.parse_expression(&[bracket_type])?;

                    match self.peek() {
                        Some(Token {
                            kind: TokenKind::CloseBracket(close_bt),
                            ..
                        }) if *close_bt == bracket_type => {
                            self.advance();
                        }
                        _ => {
                            return Err(ParseError::UnmatchedOpen {
                                kind: bracket_type,
                                span,
                            });
                        }
                    }

                    nodes.push(Node::Bracket(Bracket::new(
                        BracketKind::from(bracket_type),
                        contents,
                    )));
                }
                _ => {
                    self.advance();
                }
            }
        }

        Ok(Expression::from_nodes(nodes))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::spw::Lexer;

    fn parse_str(input: &str) -> Result<Expression, ParseError> {
        let tokens: Vec<_> = Lexer::new(input).collect();
        Parser::new(&tokens).parse()
    }

    #[test]
    fn parse_simple_symbols() {
        let expr = parse_str("&@").unwrap();
        assert_eq!(expr.nodes.len(), 2);
        assert_eq!(expr.render(), "&@");
    }

    #[test]
    fn parse_bracketed() {
        let expr = parse_str("?{Worcester#}").unwrap();
        assert_eq!(expr.render(), "?{Worcester#}");
    }

    #[test]
    fn parse_nested_brackets() {
        let expr = parse_str("?{Henry@.(Worcester#)}").unwrap();
        assert_eq!(expr.render(), "?{Henry@.(Worcester#)}");
    }

    #[test]
    fn parse_with_string() {
        let expr = parse_str(r#"@Henry ~ "of[Worcester]""#).unwrap();
        assert!(expr.render().contains("of[Worcester]"));
    }

    #[test]
    fn parse_sequence() {
        let expr = parse_str("[Henry, Worcester]").unwrap();
        assert!(!expr.is_empty());
    }

    #[test]
    fn unmatched_open() {
        let result = parse_str("{foo");
        assert!(matches!(result, Err(ParseError::UnmatchedOpen { .. })));
    }

    #[test]
    fn unmatched_close() {
        let result = parse_str("foo}");
        assert!(matches!(result, Err(ParseError::UnmatchedClose { .. })));
    }
}
