# File Browser Component - Wiring Complete

## Navigation Integration

The File Browser is now fully integrated into the Orchestrator dashboard navigation.

### Changes Made

#### 1. **PageLayout.kt** - Added Navigation Link
```kotlin
Navigation.Link(
    label = "Files",
    href = "/files",
    active = currentPath.startsWith("/files"),
    ariaLabel = "Browse indexed files",
    icon = "📂"
)
```

**Location:** `src/main/kotlin/com/orchestrator/web/rendering/PageLayout.kt:86-92`

The Files link appears in the main navigation between Tasks and Index Status:
- **Home** 🏠
- **Tasks** 📋
- **Files** 📂 ← **NEW**
- **Index Status** 📊
- **Metrics** 📈

#### 2. **Routing.kt** - Registered Routes
```kotlin
// Imports
import com.orchestrator.web.routes.fileRoutes

// In routing block
fileRoutes()
```

**Location:** `src/main/kotlin/com/orchestrator/web/plugins/Routing.kt:6, 45`

This registers all File Browser routes:
- `GET /files` - Main file browser page
- `GET /files/table` - Dynamic table data
- `GET /files/{filePath}/detail` - File detail modal

## How It Works

### User Navigation Flow

1. **User clicks "Files" in navigation**
   - Browser navigates to `/files`
   - HTMX boost enabled (smooth page transition)

2. **FilesPage renders**
   - Search/filter controls displayed
   - File table loads via HTMX `hx-trigger="revealed"`
   - Table is populated from `/files/table`

3. **User searches or filters**
   - Form triggers HTMX GET to `/files/table`
   - Results update dynamically without page reload

4. **User clicks "View" button**
   - HTMX GET to `/files/{filePath}/detail`
   - Modal appears with file details and chunks

5. **User clicks modal close**
   - Modal content cleared
   - Back to file browser

## Route Details

### GET /files
- **Purpose:** Main file browser page
- **Response:** Full HTML page with shell and controls
- **Template:** FilesPage.kt
- **Features:**
  - Search input
  - Status filter
  - File type filter
  - Sort dropdown
  - Dynamic table container

### GET /files/table
- **Purpose:** Return filtered/paginated file list
- **Query Parameters:**
  - `search`: File name/path search (100 char max)
  - `status`: Filter by status
  - `extension`: Filter by file extension
  - `sortBy`: Column to sort by
  - `sortOrder`: asc, desc, or none
  - `page`: Page number
  - `pageSize`: Items per page (1-200)
- **Response:** HTML table fragment for HTMX swap
- **Data Source:** ContextRepository.listAllFiles()

### GET /files/{filePath}/detail
- **Purpose:** Show file details and chunks in modal
- **Path Parameter:** filePath
- **Response:** HTML modal fragment
- **Data Source:** ContextRepository.fetchFileArtifactsByPath()
- **Content:**
  - File metadata (status, size, language, hash)
  - All chunks with code previews
  - Token counts and line numbers

## Navigation States

The Files link shows as active when:
- Current path is `/files`
- Current path starts with `/files`

This applies to:
- `/files` - Main page
- `/files/table` - HTMX requests (no page change)
- `/files/{id}/detail` - Detail modal (no page change)

## Active State Styling

The navigation link uses CSS classes:
- **Active:** `.main-nav__link--active` (desktop) / `.mobile-nav__link--active` (mobile)
- **Icon:** 📂 (file folder emoji)
- **Label:** "Files"

## Testing the Integration

1. **Start the application:**
   ```bash
   ./gradlew run
   ```

2. **Open browser:**
   ```
   http://localhost:8080
   ```

3. **Click "Files" in navigation**
   - Should navigate to `/files`
   - Link becomes highlighted/active
   - File browser page loads

4. **Search for files:**
   - Type in search box
   - Table updates automatically

5. **Filter by status:**
   - Select status dropdown
   - Table updates with filtered results

6. **View file details:**
   - Click "View" button on any file
   - Modal appears with chunks

7. **Close modal:**
   - Click close button or X
   - Modal disappears

## Responsive Behavior

- **Desktop:** Full navigation with icons and labels
- **Tablet (≤768px):** Compressed navigation
- **Mobile (≤576px):** Hamburger menu with Files option

The mobile hamburger menu automatically includes the Files link and closes when clicked.

## Build Status

✅ **BUILD SUCCESSFUL** - All routes compiled and wired correctly

## Next Steps

The File Browser is now ready to use:
1. Commit the navigation and routing changes
2. Start the application
3. Test the file browser functionality
4. Deploy to your environment

---

**Integration Date:** 2025-10-27
**Status:** Complete and Ready to Use
