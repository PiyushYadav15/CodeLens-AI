package com.codelens.CodeLens.rag;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds grounded, hallucination-resistant prompts for the LLM.
 *
 * ════════════════════════════════════════════════════════════════════════════
 *  WHY ARE THESE SYSTEM RULES NECESSARY? (Combatting Hallucinations)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Large Language Models (LLMs) are trained to predict plausible-sounding text.
 * When asked about code, an unconstrained LLM will often invent ("hallucinate"):
 *   • Controller routes that don't exist (e.g. POST /api/login)
 *   • Methods that sound standard in Spring Boot but aren't in this project
 *   • Dependencies or database columns that don't match the actual schema
 *
 * Each instruction in this prompt serves a specific defense:
 *
 * 1. "Answer ONLY using the context below."
 *    → Blocks the model from falling back on generic training data about
 *      unrelated open-source projects.
 *
 * 2. "Never invent files, methods, or endpoints not shown in the context."
 *    → Explicit negative constraint. LLMs respond strongly to direct negative
 *      guardrails when synthesizing technical architecture.
 *
 * 3. "Cite the specific file and line range for every claim."
 *    → Forcing citations acts as a chain-of-verification: the model must
 *      locate evidence before stating a fact, which dramatically lowers errors
 *      and lets developers independently audit the answer.
 *
 * 4. "If the context doesn't contain enough info, say so explicitly."
 *    → Gives the model permission to say "I don't know" or "The provided code
 *      does not show how payment processing is configured" instead of guessing.
 * ════════════════════════════════════════════════════════════════════════════
 */
@Component
public class PromptBuilder {

    /**
     * Data carrier for an individual retrieved code snippet and its file metadata.
     *
     * @param filePath  Relative path of the source file
     * @param startLine Line number where snippet begins
     * @param endLine   Line number where snippet ends
     * @param content   The actual code snippet content
     */
    public record SnippetContext(String filePath, int startLine, int endLine, String content) {
    }

    /**
     * Assembles the prompt string combining system instructions, the retrieved
     * context chunks, and the user's natural language question.
     *
     * @param question The user's query
     * @param snippets The top retrieved snippets from similarity search
     * @return Formatted prompt ready to send to Groq
     */
    public String buildPrompt(String question, List<SnippetContext> snippets) {
        StringBuilder sb = new StringBuilder();

        // ── 1. Guardrail System Instructions ─────────────────────────────────
        sb.append("You are a codebase assistant. Answer ONLY using the context below.\n")
          .append("- Never invent files, methods, or endpoints not shown in the context.\n")
          .append("- Cite the specific file and line range for every claim.\n")
          .append("- If the context doesn't contain enough info, say so explicitly.\n\n");

        // ── 2. Retrieved Code Context ─────────────────────────────────────────
        sb.append("CONTEXT:\n");

        if (snippets == null || snippets.isEmpty()) {
            sb.append("(No relevant code snippets were found in the indexed repository)\n\n");
        } else {
            for (SnippetContext snippet : snippets) {
                sb.append(String.format("[%s, lines %d-%d]\n",
                        snippet.filePath(),
                        snippet.startLine(),
                        snippet.endLine()));
                sb.append(snippet.content().trim()).append("\n\n");
            }
        }

        // ── 3. The User Question ──────────────────────────────────────────────
        sb.append("QUESTION: ").append(question.trim());

        return sb.toString();
    }
}
