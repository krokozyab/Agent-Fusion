# Chunking Strategies

Agent Fusion uses language-aware chunkers to split files into semantically meaningful pieces. Each chunker is optimized for its file type, preserving structure and context.

## How Chunkers Work

When a file is indexed:
1. The **ChunkerRegistry** selects the appropriate chunker based on file extension
2. The chunker splits the content into **Chunks** (semantically meaningful pieces)
3. Each chunk gets metadata: line numbers, kind (class/function/section), summary
4. Chunks are embedded and stored in the database

When you search, results include the full chunk context, so you get complete code blocks or sections—not fragmented snippets.

---

## Language-Specific Chunkers

### MarkdownChunker (`.md` files)

**What it does:**
- Splits by heading sections – Each `# ## ### ####` heading gets its own section
- Preserves code fences – ` ```code blocks``` ` are extracted separately as `CODE_BLOCK` chunks
- Smart paragraph handling – Breaks large sections at blank lines
- Respects structure – Nested headings maintain hierarchy

**Chunk types:**
- `MARKDOWN_SECTION` – Content under a heading
- `CODE_BLOCK` – Fenced code blocks (```python, ```java, etc.)

**Example:**
```
# Installation
Install the package...

```bash
npm install agent-fusion
```

This becomes 2 chunks:
1. "Installation" section (MARKDOWN_SECTION)
2. Bash code block (CODE_BLOCK)
```

---

### PythonChunker (`.py` files)

**What it does:**
- Language-aware parsing – Uses regex to detect `def`, `async def`, `class`
- Extracts docstrings – Module docstrings and function docstrings get separate chunks
- Indentation-based block detection – Finds function/class bodies using indent levels
- Overlap support – Overlaps chunks by 15% to preserve context between splits
- Decorator awareness – Includes decorators with function definitions

**Chunk types:**
- `DOCSTRING` – Module or function docstring
- `CODE_CLASS` – Class definition with docstring
- `CODE_FUNCTION` – Function definition with docstring
- `CODE_BLOCK` – Initializers and static blocks

**Why this matters:**
- In Python, docstrings are semantic units separate from code
- Overlap (15%) ensures context isn't lost when a function spans multiple chunks
- Decorator detection keeps `@app.route` with the function it decorates

**Example:**
```python
def authenticate(token: str) -> bool:
    """Validate JWT token and return user."""
    import jwt
    try:
        jwt.decode(token, "secret")
        return True
    except jwt.InvalidTokenError:
        return False
```

Becomes 2 chunks:
1. Docstring: "Validate JWT token and user"
2. Function code with 15% overlap

---

### TypeScriptChunker (`.ts, .tsx, .js, .jsx` files)

**What it does:**
- Export-centric – Detects `export` statements and groups them with context
- Preserves JSDoc – Comments above exports stay attached
- Includes module imports – Each export chunk includes the file's import statements
- Heuristic-based – Regex patterns (not full AST parsing)

**Chunk types:**
- `CODE_CLASS` – Exported class declaration
- `CODE_FUNCTION` – Exported function
- `CODE_INTERFACE` – Exported interface
- `IMPORT` – Module imports (if no exports found)
- `CODE_BLOCK` – Fallback for non-exported code

**Why this matters:**
- TypeScript is modular – exports are the public API
- JSDoc is essential – includes type hints and usage examples
- Import context matters – helps understand dependencies

**Example:**
```typescript
/**
 * Authenticate user with token
 * @param token JWT token
 * @returns User object or null
 */
export function authenticate(token: string): User | null {
  // implementation
}
```

Becomes 1 chunk with:
- JSDoc comment attached
- Module imports included
- Full function signature

---

### JavaChunker (`.java` files)

**What it does:**
- True AST parsing – Uses `JavaParser` library (not regex) for 100% accuracy
- Package + imports as header – Boilerplate extracted as separate chunk
- Recurses nested types – Handles inner classes, static inner classes, enums
- Method-level chunks – Each method/constructor = separate chunk with full signature
- Javadoc preservation – Includes Javadoc comments in chunks
- Overlap support – Large methods split with 15% overlap

**Chunk types:**
- `CODE_HEADER` – Package declaration + imports
- `CODE_CLASS` – Class definition
- `CODE_INTERFACE` – Interface definition
- `CODE_ENUM` – Enum definition
- `CODE_METHOD` – Method with full signature and Javadoc
- `CODE_CONSTRUCTOR` – Constructor declaration
- `CODE_BLOCK` – Initializer blocks

**Why this matters:**
- Java is verbose – AST parsing extracts exact structure
- Nested classes are common – needs recursive processing
- Method signatures contain type info – preserved as chunk summary
- Javadoc is documentation – must stay attached

**Example:**
```java
/**
 * Manages user authentication.
 */
public class AuthService {
    /**
     * Validate JWT token.
     * @param token JWT token
     * @return true if valid
     */
    public boolean authenticate(String token) {
        // 500 lines of implementation
    }
}
```

Becomes 3 chunks:
1. Header (package + imports)
2. Class declaration with class-level Javadoc
3. Method with Javadoc (potentially split into parts if >600 tokens with 15% overlap)

---

### KotlinChunker (`.kt, .kts` files)

**What it does:**
- Similar to Java – Uses Kotlin parser or regex fallback
- Top-level functions – Handles Kotlin's file-level functions (not class-bound)
- Extension functions – Detects `fun String.method()`
- Data classes – Special handling for `data class` declarations
- Simpler nesting – Flatter structure than Java (inner classes less common)

**Chunk types:**
- `CODE_CLASS` – Class, data class, object
- `CODE_FUNCTION` – Top-level or member function
- `CODE_EXTENSION` – Extension function
- `CODE_INTERFACE` – Interface (trait-like in Kotlin)

**Why this matters:**
- Kotlin idioms differ from Java – top-level functions are common
- Data classes are structural – should include all fields
- Extension functions are powerful – need clear labeling

---

### CSharpChunker (`.cs` files)

**What it does:**
- C# AST awareness – Parses namespaces, classes, properties, methods
- Property handling – Auto-properties vs properties with logic
- Region detection – Respects `#region` markers if present
- Generic support – Handles generic types like `List<T>`

**Chunk types:**
- `CODE_CLASS` – Class definition
- `CODE_INTERFACE` – Interface definition
- `CODE_PROPERTY` – Property with getter/setter
- `CODE_METHOD` – Method
- `CODE_ENUM` – Enum definition

**Why this matters:**
- C# properties are first-class – need separate representation
- Regions organize code – chunk boundaries may align with regions
- Generics are everywhere – type parameters must be preserved

---

### YamlChunker (`.yaml, .yml` files)

**What it does:**
- Key-value hierarchy – Splits by top-level keys
- Indentation-aware – Respects YAML nesting structure
- Preserves structure – Nested objects stay together
- List handling – Arrays treated as atomic units

**Chunk types:**
- `CONFIG_SECTION` – Top-level key + nested values
- `CONFIG_LIST` – Array section

**Why this matters:**
- YAML is hierarchical – chunks follow hierarchy
- Indentation is semantic – must be preserved
- Common in configs – app.yaml, deployment.yaml, etc.

---

### JsonChunker (`.json` files)

**What it does:**
- Parse-aware splitting – Uses JSON parser (not regex)
- Object-level chunks – Top-level objects = separate chunks
- Maintains validity – Each chunk is valid JSON
- Array handling – Large arrays may be split

**Chunk types:**
- `CONFIG_OBJECT` – JSON object
- `CONFIG_ARRAY` – JSON array

**Why this matters:**
- JSON must be valid – regex splitting risks breaking structure
- Objects are semantic units – chunks follow object boundaries
- Common in APIs – API schemas, config objects

---

### SqlChunker (`.sql` files)

**What it does:**
- Statement splitting – Chunks by SQL statements (`;` delimited)
- Comment preservation – Keeps comments with relevant statements
- Migration support – Handles multiple statements in migration files
- Dialect awareness – Recognizes common SQL dialects

**Chunk types:**
- `SQL_STATEMENT` – Single SQL statement (SELECT, INSERT, CREATE, etc.)
- `SQL_PROCEDURE` – Stored procedure or function

**Why this matters:**
- SQL files often contain multiple statements – need clear boundaries
- Comments document intent – must stay attached
- Migrations are ordered – sequence matters

---

### PlainTextChunker (`.pdf, .doc, .docx, .txt` — Fallback)

**What it does:**
- Paragraph-based splitting – Chunks by blank lines
- Sentence-aware fallback – If paragraph exceeds token limit, splits by sentences
- Word-aware fallback – If sentences still too long, splits by words
- Progressive degradation – Tries to preserve semantic units first
- Graceful handling – Works for any unknown format

**Chunk types:**
- `PARAGRAPH` – Text paragraph
- `SENTENCE` – Fallback if paragraph too large
- `WORD_CHUNK` – Fallback if sentence too large

**Splitting strategy (in order):**
1. Try splitting by paragraphs (blank line boundaries)
2. If paragraph > max tokens, split by sentences (`.!?` boundaries)
3. If sentence > max tokens, split by words (whitespace boundaries)
4. If word > max tokens, split by characters (last resort)

**Why this matters:**
- PDFs and Word docs are unstructured – need conservative approach
- Preserves readability – starts with semantic units (paragraphs)
- Handles any format – fallback for unknown file types

**Example:**
```
This is a long paragraph that talks about
something important spanning multiple lines.
It exceeds the token limit so gets split.

This is another paragraph.
```

Becomes 2 chunks (by blank lines):
1. First paragraph (and continuation)
2. Second paragraph

If first paragraph was too long, it would split further by sentences.

---

## Chunking Strategy Comparison

### Advanced AST-Based Chunkers
- **Java, Kotlin, C#** – True parsing with abstract syntax tree
- **Benefit**: 100% accurate structure extraction
- **Cost**: Parsing errors silently degrade to PlainText

### Heuristic Chunkers
- **Python, TypeScript, YAML, JSON, SQL** – Regex + pattern matching
- **Benefit**: Fast, no parsing errors, language-specific patterns
- **Cost**: Edge cases might be missed

### Document Chunkers
- **Markdown, PlainText** – Line/section based
- **Benefit**: Simple, predictable, handles any content
- **Cost**: Less structural awareness

### Overlap Support
Some chunkers use **15% overlap** for large chunks:
- Used by: **Python, Java, Kotlin**
- Why: Preserves context when a function/method is split across chunks
- Trade-off: Slightly larger index, better semantic coverage

---

## Token Budgets

Each chunker has a **max tokens** setting (default per language):

| Language | Default Max Tokens | Reason |
|----------|------------------|--------|
| Markdown | 400 | Docs are prose – larger chunks work |
| Python | 600 | Functions are usually self-contained |
| Java | 600 | Methods need context (signature + Javadoc) |
| TypeScript | 400 | Modules are smaller, export-heavy |
| PlainText | 600 | Paragraphs vary – safe default |

When a chunk exceeds the limit:
- **Advanced chunkers** → split with overlap
- **Heuristic chunkers** → split at natural boundaries
- **PlainText** → split progressively (paragraph → sentence → word)

---

## ChunkKind Enum

Each chunk is tagged with a kind:

```kotlin
enum class ChunkKind {
    // Code structure
    CODE_CLASS, CODE_INTERFACE, CODE_ENUM,
    CODE_FUNCTION, CODE_METHOD, CODE_CONSTRUCTOR,
    CODE_PROPERTY, CODE_EXTENSION,

    // Documentation
    CODE_HEADER, CODE_BLOCK, DOCSTRING,
    MARKDOWN_SECTION, PARAGRAPH,

    // Configuration
    CONFIG_SECTION, CONFIG_OBJECT, CONFIG_ARRAY,

    // Database
    SQL_STATEMENT, SQL_PROCEDURE
}
```

**Use in search**: You can filter by kind when querying:
```json
{
  "query": "authentication validator",
  "kinds": ["CODE_METHOD", "CODE_FUNCTION"]
}
```

---

## Adding a Custom Chunker

To add a new language chunker:

1. **Create a class** implementing `SimpleChunker` or `Chunker`:
   ```kotlin
   class RustChunker : SimpleChunker {
       override fun chunk(content: String, filePath: String): List<Chunk> {
           // Your chunking logic
       }
   }
   ```

2. **Register in ChunkerRegistry**:
   ```kotlin
   "rs" to SimpleChunkerAdapter(RustChunker())
   ```

3. **Test** with `RustChunkerTest.kt` in test suite

4. **Update documentation** with your chunker's strategy

---

## Troubleshooting Chunking Issues

### "Chunks too small / fragments broken across chunks"
→ Increase `maxTokens` in the chunker or configuration

### "Code examples incomplete in search results"
→ Check if chunker is preserving context (docstrings, imports, comments)

### "Unknown file type not being indexed"
→ Check if extension is in `allowed_extensions` config
→ PlainTextChunker will be used as fallback

### "Parsing error for language X"
→ Check logs for `ChunkerRegistry` fallback to PlainText
→ Consider using heuristic chunker instead of AST parser
