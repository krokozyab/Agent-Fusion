package com.orchestrator.web.routes

import com.orchestrator.context.ContextRepository
import com.orchestrator.domain.TaskStatus
import com.orchestrator.storage.repositories.MetricsRepository
import com.orchestrator.storage.repositories.TaskRepository
import com.orchestrator.web.pages.HomePage
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Home page routes for the Orchestrator dashboard
 */
fun Route.homeRoutes(clock: Clock = Clock.systemUTC()) {

    /**
     * GET / - Main dashboard home page
     *
     * Displays:
     * - System overview with quick stats (tasks, index, metrics)
     * - Recent activity feed
     * - Recent tasks list
     * - Live HTMX updates
     */
    get("/") {
        val config = buildHomePageConfig(clock)
        val html = HomePage.render(config)

        call.response.headers.append("Cache-Control", "no-cache, no-store, must-revalidate")
        call.respondText(html, ContentType.Text.Html)
    }

    /**
     * GET /api/stats - Quick stats fragment for HTMX updates
     *
     * Returns only the stats grid portion for live updates
     */
    get("/api/stats") {
        val stats = fetchSystemStats()
        val html = renderStatsFragment(stats)

        call.response.headers.append("Cache-Control", "no-cache, no-store, must-revalidate")
        call.respondText(html, ContentType.Text.Html)
    }

    /**
     * GET /api/activity - Recent activity fragment for HTMX updates
     *
     * Returns only the activity feed portion for live updates
     */
    get("/api/activity") {
        val activities = fetchRecentActivities(clock, limit = 10)
        val html = renderActivityFragment(activities)

        call.response.headers.append("Cache-Control", "no-cache, no-store, must-revalidate")
        call.respondText(html, ContentType.Text.Html)
    }
}

/**
 * Build complete home page configuration with all data
 */
private fun buildHomePageConfig(clock: Clock): HomePage.Config {
    val stats = fetchSystemStats()
    val recentActivity = fetchRecentActivities(clock, limit = 10)
    val recentTasks = fetchRecentTasks(limit = 8)

    return HomePage.Config(
        stats = stats,
        recentActivity = recentActivity,
        recentTasks = recentTasks,
        refreshInterval = 30 // 30 seconds
    )
}

/**
 * Fetch system statistics from repositories
 */
private fun fetchSystemStats(): HomePage.SystemStats {
    // Query task statistics
    val (allTasks, _) = TaskRepository.queryFiltered(
        status = null,
        agentId = null,
        from = null,
        to = null,
        limit = 10000,
        offset = 0
    )

    val totalTasks = allTasks.size
    val activeTasks = allTasks.count { it.status in setOf(TaskStatus.PENDING, TaskStatus.IN_PROGRESS, TaskStatus.WAITING_INPUT) }
    val completedTasks = allTasks.count { it.status == TaskStatus.COMPLETED }
    val failedTasks = allTasks.count { it.status == TaskStatus.FAILED }

    // Query index statistics
    // TODO: Add methods to ContextRepository for file/chunk/embedding counts
    // For now, use placeholder values
    val indexedFiles = 0
    val indexedChunks = 0
    val totalEmbeddings = 0

    // Query metrics count (last 24 hours)
    // TODO: Add method to MetricsRepository for counting metrics by time range
    val recentMetrics = 0

    return HomePage.SystemStats(
        totalTasks = totalTasks,
        activeTasks = activeTasks,
        completedTasks = completedTasks,
        failedTasks = failedTasks,
        indexedFiles = indexedFiles,
        indexedChunks = indexedChunks,
        totalEmbeddings = totalEmbeddings,
        recentMetrics = recentMetrics
    )
}

/**
 * Fetch recent activity items from task history
 */
private fun fetchRecentActivities(clock: Clock, limit: Int): List<HomePage.ActivityItem> {
    // Query recent tasks for activity feed
    val (recentTasks, _) = TaskRepository.queryFiltered(
        status = null,
        agentId = null,
        from = null,
        to = null,
        limit = limit * 2, // Fetch more to filter
        offset = 0
    )

    // Convert tasks to activity items
    val activities = mutableListOf<HomePage.ActivityItem>()

    recentTasks.sortedByDescending { it.createdAt }.forEach { task ->
        // Task created activity
        if (activities.size < limit) {
            activities.add(
                HomePage.ActivityItem(
                    id = "task-created-${task.id.value}",
                    title = task.title,
                    description = "Task created: ${task.type.name.lowercase().replace('_', ' ')}",
                    timestamp = task.createdAt,
                    type = HomePage.ActivityType.TASK_CREATED,
                    href = "/tasks/${task.id.value}"
                )
            )
        }

        // Task completed/failed activity (if applicable)
        if (activities.size < limit && task.status == TaskStatus.COMPLETED) {
            // Use updatedAt as completion timestamp
            task.updatedAt?.let { completedAt ->
                activities.add(
                    HomePage.ActivityItem(
                        id = "task-completed-${task.id.value}",
                        title = task.title,
                        description = "Task completed successfully",
                        timestamp = completedAt,
                        type = HomePage.ActivityType.TASK_COMPLETED,
                        href = "/tasks/${task.id.value}"
                    )
                )
            }
        }

        if (activities.size < limit && task.status == TaskStatus.FAILED) {
            // Use updatedAt as failure timestamp
            task.updatedAt?.let { failedAt ->
                activities.add(
                    HomePage.ActivityItem(
                        id = "task-failed-${task.id.value}",
                        title = task.title,
                        description = "Task failed",
                        timestamp = failedAt,
                        type = HomePage.ActivityType.TASK_FAILED,
                        href = "/tasks/${task.id.value}"
                    )
                )
            }
        }
    }

    // Sort by timestamp and limit
    return activities.sortedByDescending { it.timestamp }.take(limit)
}

/**
 * Fetch recent tasks
 */
private fun fetchRecentTasks(limit: Int) = TaskRepository.queryFiltered(
    status = null,
    agentId = null,
    from = null,
    to = null,
    limit = limit,
    offset = 0
).first.sortedByDescending { it.createdAt }

/**
 * Render stats grid fragment for HTMX update
 */
private fun renderStatsFragment(stats: HomePage.SystemStats): String {
    return """
        <div class="grid grid-cols-4 gap-md mb-lg" id="quick-stats" hx-get="/api/stats" hx-trigger="refresh" hx-swap="outerHTML">
            <div class="card stat-card">
                <div class="stat-card__value">${stats.totalTasks}</div>
                <div class="stat-card__label">Total Tasks</div>
                <small class="text-muted">${stats.activeTasks} active</small>
            </div>
            <div class="card stat-card">
                <div class="stat-card__value">${stats.completedTasks}</div>
                <div class="stat-card__label">Completed</div>
                <small class="text-muted">${if (stats.failedTasks > 0) "${stats.failedTasks} failed" else "All successful"}</small>
            </div>
            <div class="card stat-card">
                <div class="stat-card__value">${stats.indexedFiles}</div>
                <div class="stat-card__label">Indexed Files</div>
                <small class="text-muted">${stats.indexedChunks} chunks</small>
            </div>
            <div class="card stat-card">
                <div class="stat-card__value">${formatNumber(stats.totalEmbeddings)}</div>
                <div class="stat-card__label">Embeddings</div>
                <small class="text-muted">Vector database</small>
            </div>
        </div>
    """.trimIndent()
}

/**
 * Render activity feed fragment for HTMX update
 */
private fun renderActivityFragment(activities: List<HomePage.ActivityItem>): String {
    if (activities.isEmpty()) {
        return """<div class="text-center text-muted">No recent activity</div>"""
    }

    val items = activities.joinToString("\n") { activity ->
        val badgeClass = when (activity.type) {
            HomePage.ActivityType.TASK_CREATED -> "status-pending"
            HomePage.ActivityType.TASK_COMPLETED -> "status-completed"
            HomePage.ActivityType.TASK_FAILED -> "status-failed"
            HomePage.ActivityType.INDEX_UPDATE -> "type-research"
            HomePage.ActivityType.METRIC_RECORDED -> "type-testing"
        }

        val icon = when (activity.type) {
            HomePage.ActivityType.TASK_CREATED -> "+"
            HomePage.ActivityType.TASK_COMPLETED -> "✓"
            HomePage.ActivityType.TASK_FAILED -> "✗"
            HomePage.ActivityType.INDEX_UPDATE -> "⟳"
            HomePage.ActivityType.METRIC_RECORDED -> "📊"
        }

        val titleHtml = if (activity.href != null) {
            """<a href="${activity.href}" class="activity-item__title" hx-boost="true">${activity.title}</a>"""
        } else {
            """<div class="activity-item__title">${activity.title}</div>"""
        }

        """
        <li class="activity-item" role="listitem">
            <div class="activity-item__icon">
                <span class="badge badge-$badgeClass">$icon</span>
            </div>
            <div class="activity-item__content">
                $titleHtml
                <div class="activity-item__description text-muted">${activity.description}</div>
            </div>
            <div class="activity-item__time text-muted text-sm">${formatRelativeTime(activity.timestamp)}</div>
        </li>
        """.trimIndent()
    }

    return """<ul class="activity-list" role="list">$items</ul>"""
}

/**
 * Format large numbers with K/M suffix
 */
private fun formatNumber(num: Int): String {
    return when {
        num >= 1_000_000 -> String.format("%.1fM", num / 1_000_000.0)
        num >= 1_000 -> String.format("%.1fK", num / 1_000.0)
        else -> num.toString()
    }
}

/**
 * Format timestamp as relative time (e.g., "2h ago")
 */
private fun formatRelativeTime(instant: Instant): String {
    val now = Instant.now()
    val seconds = ChronoUnit.SECONDS.between(instant, now)

    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60}m ago"
        seconds < 86400 -> "${seconds / 3600}h ago"
        seconds < 604800 -> "${seconds / 86400}d ago"
        seconds < 2592000 -> "${seconds / 604800}w ago"
        else -> {
            val formatter = java.time.format.DateTimeFormatter.ofPattern("MMM d")
                .withZone(java.time.ZoneId.systemDefault())
            formatter.format(instant)
        }
    }
}
