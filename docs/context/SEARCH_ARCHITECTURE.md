# Search Architecture

This document provides an overview of the search architecture within the MCP server, detailing how the `query_context` tool and its underlying provider system work to deliver relevant and diverse search results.

## Search Process Overview

The search functionality is designed as a multi-stage pipeline that leverages several specialized search "providers." This collaborative approach ensures that search results are not only accurate, based on a variety of criteria (semantic, symbolic, keyword-based), but also diverse and free of redundancy.

The process can be broken down into four main stages:
1.  **Query Initiation**: An agent initiates a search through the `query_context` tool.
2.  **Parallel Fan-out**: The query is distributed to multiple specialized providers.
3.  **Result Fusion**: The results from all providers are aggregated and ranked.
4.  **Re-ranking for Diversity**: The unified list of results is re-ranked to optimize for diversity.

## Search Lifecycle

```mermaid
graph TD
    A[Agent calls query_context] --> B{Hybrid Provider};
    B -- Query --> C[Semantic Provider];
    B -- Query --> D[Symbol Provider];
    B -- Query --> E[Full-Text Provider];
    C -- Results --> F{Reciprocal Rank Fusion (RRF)};
    D -- Results --> F;
    E -- Results --> F;
    F -- Unified & Ranked List --> G{Query Optimizer (MMR)};
    G -- Diverse & Ranked List --> H[Final Results];
    H --> A;
```

### 1. The Entry Point: `query_context`

All searches are initiated through the `query_context` tool. This tool serves as the primary interface for agents to access the search and retrieval system, providing a consistent API for all search operations.

### 2. The Hybrid Provider and Parallel Queries

When a query is received, it is first handled by the **Hybrid Provider**. This provider acts as a conductor, fanning out the query to multiple specialized providers simultaneously. This parallel execution ensures that different search strategies are applied to the query at the same time, maximizing the chances of finding relevant information.

The core providers include:
-   **Semantic Provider**: For conceptual search.
-   **Symbol Provider**: For precise code symbol search.
-   **Full-Text Provider**: For keyword-based search.
-   **Git History Provider**: For searching through version control history.

### 3. Reciprocal Rank Fusion (RRF)

As each provider returns its own ranked list of results, these lists are aggregated into a single, unified list using **Reciprocal Rank Fusion (RRF)**. RRF is a data fusion method that gives higher scores to results that are consistently ranked high across multiple providers. This leverages the "wisdom of the crowd" by assuming that if multiple, diverse search algorithms all agree that a result is important, it is more likely to be relevant.

### 4. The Query Optimizer and Maximal Marginal Relevance (MMR)

The final stage of the process is to re-rank the unified list to ensure diversity. The **Query Optimizer** uses the **Maximal Marginal Relevance (MMR)** algorithm for this purpose. MMR iteratively selects results that are a good balance between being relevant to the original query and being dissimilar to the results that have already been selected. This is crucial for avoiding a list of redundant or nearly identical results, instead providing the user with a broad yet relevant set of information.

After this final re-ranking, the optimized list of results is returned to the agent that initiated the query.
