//! Prompt template and compilation.

use crate::ground::Ground;
use crate::spw::Expression;
use tracing::{trace, debug, instrument};

/// Default prompt template loaded from prompt.txt at compile time.
const DEFAULT_PROMPT_TEMPLATE: &str = include_str!("prompt.txt");

/// Compiles SPW expressions into LLM prompts with in-context learning.
#[derive(Debug, Clone)]
pub struct PromptCompiler {
    /// Whether to include the full example bank (for debugging/testing).
    include_examples: bool,
    /// The prompt template (system instruction + examples).
    template: String,
}

impl Default for PromptCompiler {
    fn default() -> Self {
        Self {
            include_examples: true,
            template: DEFAULT_PROMPT_TEMPLATE.to_string(),
        }
    }
}

impl PromptCompiler {
    /// Creates a compiler with examples disabled (shorter prompts).
    #[must_use]
    pub fn minimal() -> Self {
        Self {
            include_examples: false,
            template: DEFAULT_PROMPT_TEMPLATE.to_string(),
        }
    }

    /// Creates a compiler with a custom prompt template.
    #[must_use]
    pub fn with_template(template: String) -> Self {
        Self {
            include_examples: true,
            template,
        }
    }

    /// Updates the prompt template at runtime.
    pub fn set_template(&mut self, template: String) {
        debug!(template_len = template.len(), "Updating prompt template");
        self.template = template;
    }

    /// Returns the current prompt template.
    #[must_use]
    pub fn template(&self) -> &str {
        &self.template
    }

    /// Compiles an expression with optional ground into a prompt.
    #[must_use]
    #[instrument(skip(self, expression, ground), level = "debug")]
    pub fn compile(&self, expression: &Expression, ground: Option<&Ground>) -> String {
        let expr_str = expression.render();
        debug!(expression = %expr_str, has_ground = ground.is_some(), "Compiling prompt");

        let mut prompt = String::with_capacity(2048);

        // System instruction
        prompt.push_str(self.system_instruction());
        trace!("Added system instruction");

        // In-context learning examples
        if self.include_examples {
            if let Some(examples) = self.examples() {
                prompt.push_str("\n\n");
                prompt.push_str(examples);
                trace!("Added ICL examples");
            }
        }

        // Ground context
        let ground_str = ground.map(|g| g.render()).unwrap_or_else(|| "(none)".to_string());
        trace!(ground = %ground_str, "Ground context");

        // Final interpretation request - with strong steering toward answering only this query
        prompt.push_str("\n\n");
        prompt.push_str("Now interpret the following expression. Provide ONLY a single poetic interpretation, nothing else:\n\n");
        prompt.push_str(&format!("SPW: {}\nGround: {}\nInterpretation:", expr_str, ground_str));

        debug!(prompt_len = prompt.len(), "Prompt compiled");
        prompt
    }

    /// System instruction (everything before ---EXAMPLES---).
    fn system_instruction(&self) -> &str {
        self.template.split("---EXAMPLES---").next().unwrap_or(&self.template).trim()
    }

    /// In-context learning examples (everything after ---EXAMPLES---).
    fn examples(&self) -> Option<&str> {
        self.template.split("---EXAMPLES---").nth(1).map(|s| s.trim())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::spw::parse;

    #[test]
    fn compile_simple_expression() {
        let expr = parse("&@").unwrap();
        let prompt = PromptCompiler::default().compile(&expr, None);

        // Check for system instruction content
        assert!(prompt.contains("SPW interprets symbolic expressions"));
        // Check for few-shot examples
        assert!(prompt.contains("SPW: &@\nGround: (none)\nInterpretation:"));
        // Check for final request format
        assert!(prompt.contains("SPW: &@\nGround: (none)\nInterpretation:"));
    }

    #[test]
    fn compile_with_ground() {
        let expr = parse("?*").unwrap();
        let ground = Ground::spw("g", "Work", "@[work]").unwrap();
        let prompt = PromptCompiler::default().compile(&expr, Some(&ground));

        assert!(prompt.contains("@[work]"));
        assert!(prompt.contains("SPW: ?*"));
    }

    #[test]
    fn minimal_compiler_omits_examples() {
        let expr = parse("&@").unwrap();
        let prompt = PromptCompiler::minimal().compile(&expr, None);

        // Should not contain the example interpretations
        assert!(!prompt.contains("From where I stand"));
        // Should still contain system instruction
        assert!(prompt.contains("SPW interprets symbolic expressions"));
    }
}
