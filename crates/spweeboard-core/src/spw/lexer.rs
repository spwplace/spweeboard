//! Lexer for SPW expressions.

use crate::spw::symbol::Symbol;
use crate::spw::token::{BracketType, Span, Token, TokenKind};
use compact_str::CompactString;
use std::iter::Peekable;
use std::str::CharIndices;
use tracing::debug;

/// Lexer that tokenizes SPW input strings.
pub struct Lexer<'src> {
    source: &'src str,
    chars: Peekable<CharIndices<'src>>,
    position: usize,
}

impl<'src> Lexer<'src> {
    /// Creates a new lexer for the given source string.
    #[must_use]
    pub fn new(source: &'src str) -> Self {
        debug!(len = source.len(), "Lexing SPW");
        Self {
            source,
            chars: source.char_indices().peekable(),
            position: 0,
        }
    }

    /// Peeks at the next character without consuming it.
    fn peek(&mut self) -> Option<char> {
        self.chars.peek().map(|(_, c)| *c)
    }

    /// Consumes and returns the next character.
    fn advance(&mut self) -> Option<(usize, char)> {
        let result = self.chars.next();
        if let Some((pos, c)) = result {
            self.position = pos + c.len_utf8();
        }
        result
    }

    /// Scans an identifier (alphanumeric sequence).
    fn scan_ident(&mut self, start: usize) -> Token {
        while let Some(c) = self.peek() {
            if c.is_alphanumeric() || c == '_' || c == '-' || c == '\'' {
                self.advance();
            } else {
                break;
            }
        }
        let text = &self.source[start..self.position];
        Token {
            kind: TokenKind::Ident(CompactString::new(text)),
            span: Span::new(start, self.position),
        }
    }

    /// Scans a quoted string literal.
    fn scan_string(&mut self, start: usize) -> Token {
        let mut content = CompactString::new("");
        while let Some((_, c)) = self.advance() {
            match c {
                '"' => {
                    return Token {
                        kind: TokenKind::String(content),
                        span: Span::new(start, self.position),
                    };
                }
                '\\' => {
                    // Handle escape sequences
                    if let Some((_, escaped)) = self.advance() {
                        match escaped {
                            'n' => content.push('\n'),
                            't' => content.push('\t'),
                            'r' => content.push('\r'),
                            '"' => content.push('"'),
                            '\\' => content.push('\\'),
                            other => {
                                content.push('\\');
                                content.push(other);
                            }
                        }
                    }
                }
                other => content.push(other),
            }
        }
        // Unterminated string — return what we have
        Token {
            kind: TokenKind::String(content),
            span: Span::new(start, self.position),
        }
    }

    /// Scans whitespace.
    fn scan_whitespace(&mut self, start: usize) -> Token {
        while let Some(c) = self.peek() {
            if c.is_whitespace() {
                self.advance();
            } else {
                break;
            }
        }
        Token {
            kind: TokenKind::Whitespace,
            span: Span::new(start, self.position),
        }
    }

    /// Produces the next token.
    fn next_token(&mut self) -> Token {
        let Some((start, c)) = self.advance() else {
            return Token {
                kind: TokenKind::Eof,
                span: Span::new(self.position, self.position),
            };
        };

        // SPW symbols
        if let Some(symbol) = Symbol::from_char(c) {
            return Token {
                kind: TokenKind::Symbol(symbol),
                span: Span::new(start, self.position),
            };
        }

        // Opening brackets
        if let Some(bracket) = BracketType::from_open(c) {
            return Token {
                kind: TokenKind::OpenBracket(bracket),
                span: Span::new(start, self.position),
            };
        }

        // Closing brackets
        if let Some(bracket) = BracketType::from_close(c) {
            return Token {
                kind: TokenKind::CloseBracket(bracket),
                span: Span::new(start, self.position),
            };
        }

        match c {
            '"' => self.scan_string(start),
            ',' => Token {
                kind: TokenKind::Comma,
                span: Span::new(start, self.position),
            },
            ';' => Token {
                kind: TokenKind::Semicolon,
                span: Span::new(start, self.position),
            },
            _ if c.is_whitespace() => self.scan_whitespace(start),
            _ if c.is_alphabetic() || c == '_' => self.scan_ident(start),
            _ => Token {
                kind: TokenKind::Unknown(c),
                span: Span::new(start, self.position),
            },
        }
    }
}

impl Iterator for Lexer<'_> {
    type Item = Token;

    fn next(&mut self) -> Option<Self::Item> {
        let token = self.next_token();
        if token.kind == TokenKind::Eof {
            None
        } else {
            Some(token)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lex_symbols() {
        let tokens: Vec<_> = Lexer::new("&@~").collect();
        assert_eq!(tokens.len(), 3);
        assert!(matches!(tokens[0].kind, TokenKind::Symbol(Symbol::Subject)));
        assert!(matches!(tokens[1].kind, TokenKind::Symbol(Symbol::Perspective)));
        assert!(matches!(tokens[2].kind, TokenKind::Symbol(Symbol::Potential)));
    }

    #[test]
    fn lex_bracketed() {
        let tokens: Vec<_> = Lexer::new("?{Worcester#}").collect();
        assert_eq!(tokens.len(), 5);
        assert!(matches!(tokens[0].kind, TokenKind::Symbol(Symbol::Wonder)));
        assert!(matches!(tokens[1].kind, TokenKind::OpenBracket(BracketType::Direction)));
        assert!(matches!(tokens[2].kind, TokenKind::Ident(_)));
        assert!(matches!(tokens[3].kind, TokenKind::Symbol(Symbol::Vibration)));
        assert!(matches!(tokens[4].kind, TokenKind::CloseBracket(BracketType::Direction)));
    }

    #[test]
    fn lex_string_literal() {
        let tokens: Vec<_> = Lexer::new(r#"@Henry ~ "of[Worcester]""#).collect();
        assert!(tokens.iter().any(|t| matches!(&t.kind, TokenKind::String(s) if s == "of[Worcester]")));
    }
}
