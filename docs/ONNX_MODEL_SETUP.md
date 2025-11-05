# ONNX Model Setup for LocalEmbedder

## Quick Start

### 1. Install Required Tools

```bash
# For zsh (macOS default), escape brackets
pip install 'optimum[onnxruntime]' sentence-transformers

# Or install separately
pip install optimum onnxruntime sentence-transformers
```

**SSL Error Fix (Python missing SSL module):**

If you get SSL errors, your Python installation is missing SSL support. Fix:

```bash
# Option 1: Use Homebrew Python (recommended for macOS)
brew install python@3.11
/opt/homebrew/bin/python3.11 -m pip install 'optimum[onnxruntime]' sentence-transformers

# Option 2: Use conda/miniconda
conda create -n onnx python=3.11
conda activate onnx
pip install 'optimum[onnxruntime]' sentence-transformers

# Option 3: Reinstall Python with SSL support
brew reinstall python@3.11
```

### 2. Convert Model to ONNX

```bash
# Create models directory
mkdir -p ~/.orchestrator/models

# Convert all-MiniLM-L6-v2 (recommended, 384 dimensions, 80MB)
optimum-cli export onnx \
  --model sentence-transformers/all-MiniLM-L6-v2 \
  --task feature-extraction \
  ~/.orchestrator/models/all-MiniLM-L6-v2/

# The model file will be at:
# ~/.orchestrator/models/all-MiniLM-L6-v2/model.onnx
```

### 3. Test Integration

```bash
# Set environment variable for tests
export ONNX_MODEL_PATH=~/.orchestrator/models/all-MiniLM-L6-v2/model.onnx

# Run integration tests
./gradlew test --tests "LocalEmbedderTest"
```

## Recommended Models

### all-MiniLM-L6-v2 (Default)
- **Dimensions**: 384
- **Size**: ~80MB
- **Speed**: Fast
- **Quality**: Good for most use cases
- **Best for**: Code search, general text similarity

```bash
optimum-cli export onnx \
  --model sentence-transformers/all-MiniLM-L6-v2 \
  --task feature-extraction \
  ~/.orchestrator/models/all-MiniLM-L6-v2/
```

### all-mpnet-base-v2 (Higher Quality)
- **Dimensions**: 768
- **Size**: ~420MB
- **Speed**: Slower
- **Quality**: Better accuracy
- **Best for**: Production systems requiring high accuracy

```bash
optimum-cli export onnx \
  --model sentence-transformers/all-mpnet-base-v2 \
  --task feature-extraction \
  ~/.orchestrator/models/all-mpnet-base-v2/
```

### paraphrase-MiniLM-L3-v2 (Fastest)
- **Dimensions**: 384
- **Size**: ~60MB
- **Speed**: Very fast
- **Quality**: Decent
- **Best for**: Development, testing, resource-constrained environments

```bash
optimum-cli export onnx \
  --model sentence-transformers/paraphrase-MiniLM-L3-v2 \
  --task feature-extraction \
  ~/.orchestrator/models/paraphrase-MiniLM-L3-v2/
```

## Usage in Code

```kotlin
import com.orchestrator.context.embedding.LocalEmbedder
import kotlin.io.path.Path

// Create embedder
val embedder = LocalEmbedder(
    modelPath = Path(System.getProperty("user.home"))
        .resolve(".orchestrator/models/all-MiniLM-L6-v2/model.onnx"),
    modelName = "sentence-transformers/all-MiniLM-L6-v2",
    dimension = 384,
    normalize = true,
    maxBatchSize = 32
)

// Single embedding
val embedding = embedder.embed("Hello world")

// Batch embeddings
val embeddings = embedder.embedBatch(listOf(
    "First text",
    "Second text",
    "Third text"
))

// Clean up
embedder.close()
```

## Troubleshooting

### Model not found error
```
IllegalStateException: Model not found at ~/.orchestrator/models/...
```
**Solution**: Run the conversion command above to download and convert the model.

### ONNX Runtime errors
```
OrtException: Error loading model
```
**Solution**: Ensure ONNX Runtime dependency is in build.gradle.kts:
```kotlin
implementation("com.microsoft.onnxruntime:onnxruntime:1.16.3")
```

### Wrong dimensions
If embeddings have wrong dimensions, verify:
1. Model was converted correctly
2. Dimension parameter matches model (384 for MiniLM, 768 for MPNet)

## Pre-converted Models (No Python Required)

If you can't install Python tools, download pre-converted ONNX models directly:

### Option 1: Direct Download from Hugging Face

```bash
# Download all-MiniLM-L6-v2 ONNX model (no Python needed)
mkdir -p ~/.orchestrator/models
cd ~/.orchestrator/models

# Download model.onnx directly
curl -L -o all-MiniLM-L6-v2.onnx \
  "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx"
```

### Option 2: Using git-lfs

```bash
git lfs install
git clone https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2
cp all-MiniLM-L6-v2/onnx/model.onnx ~/.orchestrator/models/all-MiniLM-L6-v2.onnx
```

### Option 3: Manual Download

1. Visit: https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/tree/main/onnx
2. Download `model.onnx` file
3. Save as `~/.orchestrator/models/all-MiniLM-L6-v2.onnx`

## Model Storage Location

### Priority Order:

1. **Bundled Resource** (highest priority, default)
   - Model is bundled inside JAR at `/models/all-MiniLM-L6-v2.onnx`
   - Extracted to temp file on first use
   - **No setup required** - works out of the box!

2. **Environment Variable** (override bundled model)
   ```bash
   export ONNX_MODEL_PATH=/path/to/custom-model.onnx
   ```

3. **JAR Directory** (fallback if resource missing)
   - Place `all-MiniLM-L6-v2.onnx` in same folder as orchestrator JAR
   - Example: `/opt/orchestrator/all-MiniLM-L6-v2.onnx`

4. **Explicit Path** (in code)
   ```kotlin
   LocalEmbedder(modelPath = Path("/custom/path/model.onnx"))
   ```

### Production Deployment:

```bash
# Option 1: Use bundled model (recommended - zero setup)
java -jar orchestrator-all.jar

# Option 2: Override with custom model
export ONNX_MODEL_PATH=/opt/models/custom-model.onnx
java -jar orchestrator-all.jar

# Option 3: Place model next to JAR
cp custom-model.onnx /opt/orchestrator/all-MiniLM-L6-v2.onnx
java -jar orchestrator-all.jar
```

### Building JAR with Model:

```bash
# Model is automatically included from src/main/resources/models/
./gradlew shadowJar

# Result: build/libs/orchestrator-all.jar (includes 86MB model)
```
