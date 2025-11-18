# Query Context Providers

The `query_context` tool uses a sophisticated multi-provider architecture to retrieve the most relevant context from the codebase. This document explains the different search providers and how they collaborate to deliver accurate and diverse results.

## Provider-Based Architecture

The system is designed around a set of independent "providers," each specializing in a different type of search. This allows the system to leverage the strengths of various search strategies, from semantic understanding to precise symbol matching.

## Core Search Providers

The following providers are available:

-   **Semantic Provider**: Performs a search based on the meaning and context of your query. It uses vector embeddings to find code snippets that are semantically similar to the query, even if they don't share the same keywords.
-   **Symbol Provider**: Uses Abstract Syntax Trees (ASTs) to analyze the code's structure and find specific code symbols like function names, classes, or variables. This is ideal for precise code navigation.
-   **Full-Text Provider**: A traditional keyword-based search that uses a BM25 algorithm to find exact matches for the terms in your query.
-   **Git History Provider**: Searches through the project's Git history, including commit messages and blame information, to find context related to changes in the codebase.

## Collaboration and Re-ranking

The power of the `query_context` tool lies in how these providers collaborate. The process is managed by a `Hybrid Provider` and a `Query Optimizer`.

```mermaid
graph TD
    A[query_context] --> B{Hybrid Provider};
    B --> C[Semantic Provider];
    B --> D[Symbol Provider];
    B --> E[Full-Text Provider];
    C --> F{Reciprocal Rank Fusion (RRF)};
    D --> F;
    E --> F;
    F --> G{Query Optimizer (MMR)};
    G --> H[Final Results];
```

### 1. Hybrid Provider and Parallel Queries

When a query is executed, the **Hybrid Provider** sends the request to multiple underlying providers (e.g., Semantic, Symbol, Full-Text) in parallel. This allows the system to gather a wide range of candidate results simultaneously.

### 2. Reciprocal Rank Fusion (RRF)

The results from each provider are then fused into a single, unified list using **Reciprocal Rank Fusion (RRF)**. RRF is an algorithm that combines ranked lists from different systems. It gives higher scores to items that are consistently ranked high across multiple providers, effectively leveraging the consensus of the different search strategies.

### 3. Query Optimizer and Maximal Marginal Relevance (MMR)

The final step is to re-rank the fused list for relevance and diversity. The **Query Optimizer** uses the **Maximal Marginal Relevance (MMR)** algorithm for this purpose. MMR selects results that are both relevant to the query and dissimilar from each other, ensuring a broad and informative set of results.

This multi-stage process of parallel querying, fusion, and re-ranking allows `query_context` to provide highly relevant and diverse results that draw from the strengths of multiple search paradigms.
