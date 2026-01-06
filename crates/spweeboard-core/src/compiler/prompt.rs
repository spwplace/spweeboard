//! Prompt template and compilation.

use crate::ground::Ground;
use crate::spw::Expression;

/// Compiles SPW expressions into LLM prompts with in-context learning.
#[derive(Debug, Clone)]
pub struct PromptCompiler {
    /// Whether to include the full example bank (for debugging/testing).
    include_examples: bool,
}

impl Default for PromptCompiler {
    fn default() -> Self {
        Self {
            include_examples: true,
        }
    }
}

impl PromptCompiler {
    /// Creates a compiler with examples disabled (shorter prompts).
    #[must_use]
    pub const fn minimal() -> Self {
        Self {
            include_examples: false,
        }
    }

    /// Compiles an expression with optional ground into a prompt.
    #[must_use]
    pub fn compile(&self, expression: &Expression, ground: Option<&Ground>) -> String {
        let mut prompt = String::with_capacity(2048);

        // System instruction
        prompt.push_str(SYSTEM_INSTRUCTION);

        // In-context learning examples
        if self.include_examples {
            prompt.push_str("\n\n");
            prompt.push_str(EXAMPLES);
        }

        // Ground context
        prompt.push_str("\n\n<ground>\n");
        if let Some(g) = ground {
            prompt.push_str(&g.render());
        } else {
            prompt.push_str("(none)");
        }
        prompt.push_str("\n</ground>");

        // Expression to interpret
        prompt.push_str("\n\n<expression>\n");
        prompt.push_str(&expression.render());
        prompt.push_str("\n</expression>");

        // Task instruction
        prompt.push_str("\n\n");
        prompt.push_str(TASK_INSTRUCTION);

        prompt
    }
}

/// System instruction defining the interpreter role.
const SYSTEM_INSTRUCTION: &str = r#"<system>
You interpret SPW symbolic expressions as natural language. SPW encodes cognitive operations — treat symbols as creative constraints that shape meaning, not words to substitute.

Symbol lore (order matters, left-to-right flow):
~ potential (becoming)    # vibration (resonance)   . ground (foundation)
? wonder (inquiry)        ! action (assertion)      * value (significance)
& subject (agent)         @ perspective (viewpoint) <> concept (abstraction)
() scene (situation)      [] mode (manner)          {} direction (intent)
^ integration (synthesis)

Nesting: brackets contain and scope. Order transforms: &@ = "from subject's view", @& = "the subject being viewed".
</system>"#;

/// In-context learning examples (~800 tokens budget).
const EXAMPLES: &str = r#"<examples>
## Order Semantics
&@ → "from my perspective as the subject"
@& → "the subject I'm viewing"
?! → "wondering, then acting"
!? → "acting, then questioning what happened"

## Bracket Nesting
&[work] → "the subject in work mode"
[work]& → "work mode gives rise to the subject"
?{&.} → "wondering about who is foundational, directed inquiry"
{?}.& → "directed wondering grounds the subject"

## Ground Composition
ground_a: @[craft]    ground_b: .{utility}
composed: @[craft].{utility} → "craftsperson's view grounded in usefulness"

ground_a: &~    ground_b: *^
composed: &~*^ → "subject becoming through value integration"

## Expression Against Ground
ground: .{software}
expr: ?&*
output: "In software: wondering who finds this valuable"

ground: @[poetry]~
expr: !<rhythm>#
output: "From poetry's becoming perspective: asserting the concept of rhythm's resonance"

## Recursive Grounds
ground: ?{&@.}  (wondering about subject-perspective-ground flow)
expr: !*
output: "Within that wondering: asserting value"
</examples>"#;

/// Task instruction for generation.
const TASK_INSTRUCTION: &str = r#"<task>
Produce natural language that embodies this expression against the ground. Be creative and concise. Match the cognitive tone implied by the symbols (? = wondering, ! = assertive, ~ = emergent, etc.). Do not explain the symbols — just interpret.
</task>"#;

#[cfg(test)]
mod tests {
    use super::*;
    use crate::spw::parse;

    #[test]
    fn compile_simple_expression() {
        let expr = parse("&@").unwrap();
        let prompt = PromptCompiler::default().compile(&expr, None);

        assert!(prompt.contains("<system>"));
        assert!(prompt.contains("<expression>"));
        assert!(prompt.contains("&@"));
        assert!(prompt.contains("<task>"));
    }

    #[test]
    fn compile_with_ground() {
        let expr = parse("?*").unwrap();
        let ground = Ground::spw("g", "Work", "@[work]").unwrap();
        let prompt = PromptCompiler::default().compile(&expr, Some(&ground));

        assert!(prompt.contains("@[work]"));
        assert!(prompt.contains("?*"));
    }

    #[test]
    fn minimal_compiler_omits_examples() {
        let expr = parse("&@").unwrap();
        let prompt = PromptCompiler::minimal().compile(&expr, None);

        assert!(!prompt.contains("<examples>"));
        assert!(prompt.contains("<system>"));
    }
}
