# Using a Different Embedding Model

If `[context.embedding].model_path` is not set in `fusionagent.toml`, the orchestrator falls back to the bundled `all-MiniLM-L6-v2.onnx` model (384-dimensional) for embeddings.

When you want higher-quality search at the cost of slower indexing, you can point the agent at a larger model such as `baai-bge-base-en-v1.5-onnx` (768-dimensional). Follow the steps below to switch models safely.

## Example: Switch to `baai-bge-base-en-v1.5-onnx`

1. Download the ONNX file from Hugging Face: https://huggingface.co/LightEmbed/baai-bge-base-en-v1.5-onnx/blob/main/model.onnx.
2. Place the downloaded file in a directory that the orchestrator can read (for example, `data/models/baai-bge-base-en-v1.5-onnx/model.onnx`).

   > **macOS:** do **not** keep the model in `~/Downloads`, `~/Desktop`, or `~/Documents`. These are privacy-protected (TCC), and the JVM can see the file's size but is denied reading its contents, so model load fails with `ai.onnxruntime.OrtException: ORT_FAIL ... Load model ... failed: system error number 1`. Move it to a non-protected folder (e.g. `~/fusion-models/` or a path inside the project), and clear the download quarantine flag if needed: `xattr -d com.apple.quarantine <model.onnx>`.
3. Open `fusionagent.toml` and update the `[context.embedding]` section:
   - Set `model_path` to the absolute path of the downloaded model.
   - Set `model = "baai-bge-base-en-v1.5-onnx"`.
   - Set `dimension = 768` to match the selected model. (The config key is `dimension`; it defaults to 384. A wrong or missing value causes an "Embedding dimension mismatch" error at index time.)
4. Delete the existing `context.duckdb` (and `context.duckdb.wal` if present) so the index rebuilds with the new embedding size.
5. Restart the orchestrator (e.g., `./start.sh`) so it loads the new configuration and reindexes your files.

The bundled WordPiece vocabulary (`vocab.txt`, bert-base-uncased) is shared by both the default `all-MiniLM-L6-v2` and `baai-bge-base-en-v1.5`, so switching between them needs no vocabulary change.

Once the restart completes, the context indexer will ingest documents with the new embedding model. Expect improved semantic recall but longer ingestion times relative to the default `all-MiniLM-L6-v2.onnx` (the larger model is several times slower per embedding on CPU).
