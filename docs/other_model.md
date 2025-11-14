# Using a Different Embedding Model

If `[context.embedding].model_path` is not set in `fusionagent.toml`, the orchestrator falls back to the bundled `all-MiniLM-L6-v2.onnx` model (384-dimensional) for embeddings.

When you want higher-quality search at the cost of slower indexing, you can point the agent at a larger model such as `baai-bge-base-en-v1.5-onnx` (768-dimensional). Follow the steps below to switch models safely.

## Example: Switch to `baai-bge-base-en-v1.5-onnx`

1. Download the ONNX file from Hugging Face: https://huggingface.co/LightEmbed/baai-bge-base-en-v1.5-onnx/blob/main/model.onnx.
2. Place the downloaded file in a directory that the orchestrator can read (for example, `data/models/baai-bge-base-en-v1.5-onnx/model.onnx`).
3. Open `fusionagent.toml` and update the `[context.embedding]` section:
   - Set `model_path` to the absolute path of the downloaded model.
   - Set `model = "baai-bge-base-en-v1.5-onnx"`.
   - Set `embedding_dim = 768` to match the selected model.
4. Delete the existing `context.duckdb` (and `context.duckdb.wal` if present) so the index rebuilds with the new embedding size.
5. Restart the orchestrator (e.g., `./start.sh`) so it loads the new configuration and reindexes your files.

Once the restart completes, the context indexer will ingest documents with the new embedding model. Expect improved semantic recall but longer ingestion times relative to the default `all-MiniLM-L6-v2.onnx`.
