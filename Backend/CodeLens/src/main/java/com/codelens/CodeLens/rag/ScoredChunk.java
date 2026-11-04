package com.codelens.CodeLens.rag;

import com.codelens.CodeLens.model.CodeChunk;

/**
 * Pairs a CodeChunk with the cosine similarity score it received
 * against a particular query embedding.
 *
 * Used as the return type from SimilaritySearchService.topKSimilar().
 * After ranking, we hand the top-K of these to RagService, which
 * sends them to Gemini as context for answering the user's question.
 *
 * @param chunk      The CodeChunk entity from the database
 * @param score      Cosine similarity score — a double in the range [0.0, 1.0]
 *                   (1.0 = perfectly aligned meaning, 0.0 = completely unrelated)
 */
public record ScoredChunk(CodeChunk chunk, double score) {

    /**
     * Convenience accessor — how many lines this chunk covers.
     * Used when building source citations: "lines 45 – 82"
     */
    public int lineCount() {
        return chunk.getEndLine() - chunk.getStartLine() + 1;
    }
}
