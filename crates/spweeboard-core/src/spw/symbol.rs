//! Core SPW symbols — the cognitive primitives.

use derive_more::Display;
use serde::{Deserialize, Serialize};

/// The 13 SPW symbols, each representing a cognitive primitive.
///
/// Order in expressions matters: `&@` (subject→perspective) differs from `@&` (perspective→subject).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, Display)]
#[repr(u8)]
pub enum Symbol {
    /// `~` — Potential: latent possibility, becoming
    #[display("~")]
    Potential,

    /// `#` — Vibration: resonance, frequency, rhythm
    #[display("#")]
    Vibration,

    /// `.` — Ground: foundation, context, base truth
    #[display(".")]
    Ground,

    /// `?` — Wonder: inquiry, exploration, openness
    #[display("?")]
    Wonder,

    /// `!` — Action: execution, assertion, force
    #[display("!")]
    Action,

    /// `*` — Value: worth, significance, weight
    #[display("*")]
    Value,

    /// `&` — Subject: agent, entity, the who
    #[display("&")]
    Subject,

    /// `@` — Perspective: viewpoint, lens, frame
    #[display("@")]
    Perspective,

    /// `^` — Integration: synthesis, unification, elevation
    #[display("^")]
    Integration,
}

impl Symbol {
    /// All SPW symbols in canonical order.
    pub const ALL: [Self; 9] = [
        Self::Potential,
        Self::Vibration,
        Self::Ground,
        Self::Wonder,
        Self::Action,
        Self::Value,
        Self::Subject,
        Self::Perspective,
        Self::Integration,
    ];

    /// Attempts to parse a character as a SPW symbol.
    #[must_use]
    pub const fn from_char(c: char) -> Option<Self> {
        match c {
            '~' => Some(Self::Potential),
            '#' => Some(Self::Vibration),
            '.' => Some(Self::Ground),
            '?' => Some(Self::Wonder),
            '!' => Some(Self::Action),
            '*' => Some(Self::Value),
            '&' => Some(Self::Subject),
            '@' => Some(Self::Perspective),
            '^' => Some(Self::Integration),
            _ => None,
        }
    }

    /// Returns the character representation of this symbol.
    #[must_use]
    pub const fn as_char(self) -> char {
        match self {
            Self::Potential => '~',
            Self::Vibration => '#',
            Self::Ground => '.',
            Self::Wonder => '?',
            Self::Action => '!',
            Self::Value => '*',
            Self::Subject => '&',
            Self::Perspective => '@',
            Self::Integration => '^',
        }
    }

    /// Returns the semantic name of this symbol.
    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            Self::Potential => "potential",
            Self::Vibration => "vibration",
            Self::Ground => "ground",
            Self::Wonder => "wonder",
            Self::Action => "action",
            Self::Value => "value",
            Self::Subject => "subject",
            Self::Perspective => "perspective",
            Self::Integration => "integration",
        }
    }

    /// Returns the semantic lore of this symbol — its deeper meaning.
    #[must_use]
    pub const fn lore(self) -> &'static str {
        match self {
            Self::Potential => "latent possibility, becoming",
            Self::Vibration => "resonance, frequency, rhythm",
            Self::Ground => "foundation, context, base truth",
            Self::Wonder => "inquiry, exploration, openness",
            Self::Action => "execution, assertion, force",
            Self::Value => "worth, significance, weight",
            Self::Subject => "agent, entity, the who",
            Self::Perspective => "viewpoint, lens, frame",
            Self::Integration => "synthesis, unification, elevation",
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_char() {
        for symbol in Symbol::ALL {
            let c = symbol.as_char();
            let parsed = Symbol::from_char(c);
            assert_eq!(parsed, Some(symbol));
        }
    }

    #[test]
    fn non_symbol_chars() {
        assert_eq!(Symbol::from_char('a'), None);
        assert_eq!(Symbol::from_char(' '), None);
        assert_eq!(Symbol::from_char('1'), None);
    }
}
