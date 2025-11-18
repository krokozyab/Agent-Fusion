# Embedded Neo4j Implementation

## Overview

Implemented **embedded Neo4j** that runs in-process inside the JAR with **zero setup** required. No separate server, no Docker, no configuration - just enable and go.

## User Experience

### Before (Separate Server - Overkill)
```bash
# Install Neo4j
brew install neo4j

# Start Neo4j server
neo4j start

# Configure connection
# Edit fusionagent.toml...

# Run application
java -jar fusionagent.jar
```

### After (Embedded - Zero Setup)
```toml
[context.neo4j]
enabled = true  # That's it!
```

```bash
java -jar fusionagent.jar  # Neo4j starts automatically
```

## Implementation

### Files Created/Modified

1. **build.gradle.kts** - Added embedded Neo4j dependencies (~50MB)
   ```kotlin
   implementation("org.neo4j:neo4j:5.15.0")
   implementation("org.neo4j:neo4j-kernel:5.15.0")
   implementation("org.neo4j:neo4j-bolt:5.15.0")
   ```

2. **EmbeddedNeo4jDriver.kt** - In-process Neo4j driver
   - Starts Neo4j in same JVM
   - Automatic lifecycle management
   - Transaction support

3. **ContextConfig.kt** - Added mode configuration
   ```kotlin
   data class Neo4jConfig(
       val enabled: Boolean = false,
       val mode: String = "embedded",  // or "server"
       val dataDir: String = "./data/neo4j",
       // ... server mode settings
   )
   ```

4. **fusionagent.toml** - Updated config
   ```toml
   [context.neo4j]
   enabled = false  # Default: disabled
   mode = "embedded"  # Default: embedded (zero setup)
   data_dir = "./data/neo4j"  # Auto-created
   ```

## Modes

### Embedded Mode (Default)
- **Setup**: None - just enable
- **Performance**: Fast (no network overhead)
- **Size**: +50MB to JAR
- **Use case**: End users, simple deployments
- **Limitations**: No Neo4j Browser UI

### Server Mode (Optional)
- **Setup**: Requires external Neo4j server
- **Performance**: Network overhead
- **Size**: No JAR bloat
- **Use case**: Production, multi-instance deployments
- **Benefits**: Neo4j Browser, clustering, monitoring

## Configuration

### Embedded Mode (Recommended for End Users)
```toml
[context.neo4j]
enabled = true
mode = "embedded"
data_dir = "./data/neo4j"  # Created automatically
database = "neo4j"
```

### Server Mode (For Production)
```toml
[context.neo4j]
enabled = true
mode = "server"
uri = "bolt://localhost:7687"
username = "neo4j"
password = "your-password"
database = "neo4j"
```

## Benefits

✅ **Zero Setup** - No installation, no configuration
✅ **Automatic** - Starts/stops with application
✅ **Portable** - Single JAR deployment
✅ **Fast** - No network overhead
✅ **Simple** - Perfect for end users

## Trade-offs

❌ **No Browser UI** - Can't inspect graph visually
❌ **Single Process** - Can't share across instances
❌ **JAR Size** - +50MB (manageable)

## Next Steps

1. **Integrate into bootstrap** - Use embedded Neo4j during indexing
2. **Integrate into queries** - Use for structural search
3. **Add fallback** - Gracefully handle if Neo4j fails
4. **Documentation** - User guide for enabling Neo4j

## Status

✅ **Embedded driver implemented**
✅ **Configuration updated**
✅ **Compiles successfully**
⏳ **Not integrated into pipeline yet** (Phase 8)

## Recommendation

**For end users**: Use embedded mode (default)
**For production**: Consider server mode with Docker Compose
**For development**: Server mode with Neo4j Browser for debugging
