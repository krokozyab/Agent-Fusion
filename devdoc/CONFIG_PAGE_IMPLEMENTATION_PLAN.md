# Config Page Implementation Plan

**Goal**: Display ALL active fusionagent.toml configuration parameters in a read-only web page with automatic support for new parameters.

**Key Requirements**:
- Display ALL properties from ContextConfig and nested data classes
- Automatically include new parameters when they appear in fusionagent.toml
- Use Kotlin reflection to avoid manual property listing
- Simple read-only display (no validation, no editing)
- Organized in collapsible sections by config category

---

## Task Breakdown

### Task 1: Create ConfigPage.kt Component
**File**: `/src/main/kotlin/com/orchestrator/web/pages/ConfigPage.kt`

**Purpose**: Render the Config page HTML with navigation and layout structure.

**Implementation**:
```kotlin
object ConfigPage {
    fun render(config: ContextConfig): String
    private fun HTML.pageLayout(config: ContextConfig)
    private fun FlowContent.pageHeader()
    private fun FlowContent.configSections(config: ContextConfig)
}
```

**Details**:
- Add "Config" link to navigation with ⚙️ icon
- Use same layout structure as ExplorerPage
- Include CSS for collapsible sections
- Pass ContextConfig instance to render method
- No JavaScript needed (pure HTML with `<details>` tags)

**Dependencies**: Navigation component, base CSS

---

### Task 2: Create ConfigRenderer Utility
**File**: `/src/main/kotlin/com/orchestrator/web/utils/ConfigRenderer.kt`

**Purpose**: Use Kotlin reflection to automatically extract and format ALL config properties.

**Implementation**:
```kotlin
object ConfigRenderer {
    fun renderConfigSection(
        sectionTitle: String,
        configObject: Any,
        expanded: Boolean = false
    ): String
    
    private fun extractProperties(obj: Any): List<ConfigProperty>
    private fun formatValue(value: Any?): String
    private fun shouldSkipProperty(name: String): Boolean
}

data class ConfigProperty(
    val name: String,
    val value: Any?,
    val type: String
)
```

**Reflection Strategy**:
- Use `KClass.memberProperties` to get all properties
- Handle nested data classes recursively
- Handle collections (List, Map) with proper formatting
- Handle enums by showing enum name
- Skip computed properties like `enabledProviders`
- Format values for readability (e.g., file paths, durations)

**Value Formatting Rules**:
- Strings: Display as-is in monospace
- Numbers: Display with units (ms, MB, K for thousands)
- Booleans: Display as ✓ (true) or ✗ (false)
- Lists: Display as bullet list
- Maps: Display as key-value table
- Nested objects: Render as sub-section
- Null: Display as "not set" in muted text

**Dependencies**: kotlin-reflect library

---

### Task 3: Add Config Route
**File**: `/src/main/kotlin/com/orchestrator/web/routes/ConfigRoutes.kt`

**Purpose**: Handle GET /config request and inject ContextConfig instance.

**Implementation**:
```kotlin
fun Route.configRoutes(contextConfig: ContextConfig) {
    get("/config") {
        val html = ConfigPage.render(contextConfig)
        call.response.headers.append("Cache-Control", "no-cache, no-store, must-revalidate")
        call.respondText(html, ContentType.Text.Html)
    }
}
```

**Details**:
- Accept ContextConfig as parameter (injected from Application setup)
- No caching (always show current config)
- Simple GET endpoint, no POST needed

**Dependencies**: ConfigPage, ContextConfig instance

---

### Task 4: Update Navigation Component
**File**: `/src/main/kotlin/com/orchestrator/web/components/Navigation.kt`

**Purpose**: Add Config link to all pages.

**Implementation**:
- Add new link: `Navigation.Link("Config", "/config", icon = "⚙️")`
- Insert after "Context Explorer" and before "Metrics"
- Ensure proper active state handling

**Details**:
- Update all page navigation configs (HomePage, TasksPage, ExplorerPage, etc.)
- Use ⚙️ emoji icon for consistency
- No boost needed (regular page load)

**Dependencies**: None (existing component)

---

### Task 5: Create config.css Stylesheet
**File**: `/src/main/resources/static/css/config.css`

**Purpose**: Style config sections, properties, and values.

**Styles Needed**:
```css
.config-section { /* Collapsible section container */ }
.config-section__header { /* Section title with icon */ }
.config-section__body { /* Section content */ }
.config-property { /* Individual property row */ }
.config-property__name { /* Property name label */ }
.config-property__value { /* Property value (monospace) */ }
.config-property__type { /* Type annotation (muted) */ }
.config-nested { /* Nested object indentation */ }
.config-list { /* List formatting */ }
.config-map { /* Map/table formatting */ }
```

**Design**:
- Use `<details>` and `<summary>` for collapsible sections
- Monospace font for all values
- Subtle borders and spacing
- Indentation for nested structures
- Responsive layout (mobile-friendly)

**Dependencies**: base.css, bootstrap

---

### Task 6: Register Config Route in Application
**File**: `/src/main/kotlin/com/orchestrator/Application.kt`

**Purpose**: Wire up config route with ContextConfig instance.

**Implementation**:
```kotlin
// In routing block
configRoutes(contextConfig)
```

**Details**:
- Pass existing ContextConfig instance from application context
- Ensure ContextConfig is loaded before routing setup
- No new dependencies needed

**Dependencies**: ConfigRoutes, ContextConfig instance

---

### Task 7: Create Config Section Renderers
**File**: `/src/main/kotlin/com/orchestrator/web/components/ConfigSection.kt`

**Purpose**: Render individual config sections with proper HTML structure.

**Implementation**:
```kotlin
object ConfigSection {
    fun render(
        title: String,
        icon: String,
        properties: List<ConfigProperty>,
        expanded: Boolean = false
    ): String
    
    private fun renderProperty(prop: ConfigProperty): String
    private fun renderNestedObject(name: String, obj: Any): String
    private fun renderList(name: String, list: List<*>): String
    private fun renderMap(name: String, map: Map<*, *>): String
}
```

**Section Organization**:
1. **General** (enabled, mode, fallbackEnabled)
2. **Engine** (host, port, timeoutMs, retryAttempts)
3. **Storage** (dbPath)
4. **Watcher** (enabled, debounceMs, watchPaths, includePaths, ignorePatterns, useGitignore, useContextignore, deletionSweepIntervalMs)
5. **Indexing** (allowedExtensions, blockedExtensions, skipPatterns, maxFileSizeMb, warnFileSizeMb, sizeExceptions, followSymlinks, maxSymlinkDepth, binaryDetection, binaryThreshold)
6. **Embedding** (model, modelPath, dimension, batchSize, normalize, cacheEnabled)
7. **Chunking** (markdown, python, kotlin, typescript - each with sub-properties)
8. **Query** (defaultK, mmrLambda, minScoreThreshold, rerankEnabled, useOptimizerInTool, neighborWindow, embeddingCacheSize, boosts, idfEnabled)
9. **Budget** (defaultMaxTokens, reserveForPrompt, warnThresholdPercent)
10. **Providers** (semantic, symbol, full_text, git_history, hybrid - each with enabled, weight, etc.)
11. **Metrics** (enabled, trackLatency, trackTokenUsage, trackCacheHits, exportIntervalMinutes)
12. **Bootstrap** (enabled, parallelWorkers, batchSize, priorityExtensions, maxInitialFiles, failFast, showProgress, progressIntervalSeconds)
13. **Security** (scrubSecrets, secretPatterns, encryptDb)
14. **Ignore** (patterns)

**Dependencies**: ConfigRenderer

---

## Implementation Order

1. **Task 2** (ConfigRenderer) - Core reflection logic first
2. **Task 7** (ConfigSection) - HTML rendering components
3. **Task 1** (ConfigPage) - Page layout and structure
4. **Task 5** (config.css) - Styling
5. **Task 3** (ConfigRoutes) - Route handler
6. **Task 4** (Navigation) - Add link to all pages
7. **Task 6** (Application) - Wire everything together

---

## Automatic New Parameter Support

**How it works**:
1. Developer adds new property to ContextConfig or nested class
2. Kotlin reflection automatically discovers it via `memberProperties`
3. ConfigRenderer extracts property name, value, and type
4. ConfigSection renders it with appropriate formatting
5. No code changes needed in ConfigPage or ConfigRoutes

**Example**:
```kotlin
// Developer adds new property to ContextConfig
data class ContextConfig(
    // ... existing properties ...
    val newFeature: NewFeatureConfig = NewFeatureConfig()  // NEW
)

data class NewFeatureConfig(  // NEW
    val enabled: Boolean = true,
    val threshold: Int = 100
)
```

**Result**: Config page automatically shows new section "New Feature" with properties "enabled" and "threshold" - zero code changes needed.

---

## Edge Cases to Handle

1. **Computed Properties**: Skip properties like `enabledProviders` (derived from `providers`)
2. **Large Collections**: Limit display to first 50 items with "... and N more" message
3. **Sensitive Data**: Mask values matching security patterns (passwords, tokens, keys)
4. **Null Values**: Display as "not set" in muted text
5. **Empty Collections**: Display as "empty" instead of blank
6. **Circular References**: Detect and break cycles (unlikely in data classes)
7. **Complex Objects**: Recursively render nested data classes up to 5 levels deep

---

## Testing Strategy

1. **Unit Tests**: Test ConfigRenderer with sample config objects
2. **Integration Tests**: Test full page rendering with real ContextConfig
3. **Manual Tests**: 
   - Verify all sections expand/collapse
   - Check all current properties are displayed
   - Add new property to ContextConfig and verify auto-display
   - Test with different config values (nulls, empty lists, large maps)
   - Verify mobile responsiveness

---

## Success Criteria

✅ All 14 config sections render correctly  
✅ All current ContextConfig properties displayed  
✅ New properties automatically appear without code changes  
✅ Values formatted correctly (booleans, lists, maps, nested objects)  
✅ Sections collapsible with `<details>` tags  
✅ Mobile-friendly responsive layout  
✅ No JavaScript required (pure HTML/CSS)  
✅ Config link in navigation on all pages  
✅ Page loads in < 100ms (reflection is fast)  

---

## Files to Create

1. `/src/main/kotlin/com/orchestrator/web/pages/ConfigPage.kt`
2. `/src/main/kotlin/com/orchestrator/web/utils/ConfigRenderer.kt`
3. `/src/main/kotlin/com/orchestrator/web/components/ConfigSection.kt`
4. `/src/main/kotlin/com/orchestrator/web/routes/ConfigRoutes.kt`
5. `/src/main/resources/static/css/config.css`

## Files to Modify

1. `/src/main/kotlin/com/orchestrator/Application.kt` - Register config route
2. `/src/main/kotlin/com/orchestrator/web/components/Navigation.kt` - Add Config link
3. All page files (HomePage, TasksPage, ExplorerPage, etc.) - Update navigation config

---

## Estimated Effort

- Task 1 (ConfigPage): 30 min
- Task 2 (ConfigRenderer): 60 min (reflection logic)
- Task 3 (ConfigRoutes): 10 min
- Task 4 (Navigation): 15 min
- Task 5 (config.css): 30 min
- Task 6 (Application): 5 min
- Task 7 (ConfigSection): 45 min

**Total**: ~3 hours

---

## Future Enhancements (Out of Scope)

- Export config as JSON/YAML
- Compare config with default values
- Highlight non-default values
- Search/filter config properties
- Edit config values (requires validation, restart logic)
- Config history/versioning
- Performance metrics per config section
