package com.orchestrator.web.pages

import com.orchestrator.domain.Task
import com.orchestrator.domain.TaskStatus
import com.orchestrator.domain.TaskType
import com.orchestrator.web.components.Breadcrumbs
import com.orchestrator.web.components.Navigation
import kotlinx.html.*
import kotlinx.html.stream.createHTML
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Home page for the Orchestrator dashboard.
 *
 * Displays system overview with:
 * - Quick stats (tasks, index, metrics)
 * - Recent activity feed
 * - System status
 * - HTMX live updates
 */
object HomePage {

    /**
     * System statistics for the dashboard
     */
    data class SystemStats(
        val totalTasks: Int = 0,
        val activeTasks: Int = 0,
        val completedTasks: Int = 0,
        val failedTasks: Int = 0,
        val indexedFiles: Int = 0,
        val indexedChunks: Int = 0,
        val totalEmbeddings: Int = 0,
        val recentMetrics: Int = 0
    )

    /**
     * Activity item for the recent activity feed
     */
    data class ActivityItem(
        val id: String,
        val title: String,
        val description: String,
        val timestamp: Instant,
        val type: ActivityType,
        val href: String? = null
    )

    enum class ActivityType {
        TASK_CREATED,
        TASK_COMPLETED,
        TASK_FAILED,
        INDEX_UPDATE,
        METRIC_RECORDED
    }

    /**
     * Configuration for the home page
     */
    data class Config(
        val stats: SystemStats = SystemStats(),
        val recentActivity: List<ActivityItem> = emptyList(),
        val recentTasks: List<Task> = emptyList(),
        val refreshInterval: Int = 30 // seconds
    )

    /**
     * Render complete home page
     */
    fun render(config: Config): String {
        val htmlContent = createHTML().html {
            pageLayout(config)
        }
        return "<!DOCTYPE html>\n$htmlContent"
    }

    /**
     * Main page layout
     */
    private fun HTML.pageLayout(config: Config) {
        val navConfig = Navigation.Config(
            title = "Orchestrator",
            titleHref = "/",
            enableHtmxBoost = true,
            links = listOf(
                Navigation.Link(
                    label = "Home",
                    href = "/",
                    active = true,
                    ariaLabel = "Go to home page",
                    icon = "🏠"
                ),
                Navigation.Link(
                    label = "Tasks",
                    href = "/tasks",
                    ariaLabel = "View and manage tasks",
                    icon = "📋"
                ),
                Navigation.Link(
                    label = "Index Status",
                    href = "/index",
                    ariaLabel = "View index status and file browser",
                    icon = "📁"
                ),
                Navigation.Link(
                    label = "Metrics",
                    href = "/metrics",
                    ariaLabel = "View metrics and analytics",
                    icon = "📊"
                )
            )
        )

        head {
                meta(charset = "utf-8")
                meta(name = "viewport", content = "width=device-width, initial-scale=1")
                title("Orchestrator Dashboard")

                // CSS
                link(rel = "stylesheet", href = "/static/css/base.css")
                link(rel = "stylesheet", href = "/static/css/orchestrator.css")
                link(rel = "stylesheet", href = "/static/css/dark-mode.css")

                // HTMX
                script(src = "/static/js/htmx.min.js") {}
            }

            body(classes = "dashboard-layout") {
                // Navigation
                with(Navigation) {
                    navigationBar(navConfig)
                }

                // Main content
                main(classes = "main-content") {
                    attributes["id"] = "main-content"
                    attributes["role"] = "main"

                    // Page header
                    pageHeader()

                    // Quick stats grid
                    quickStatsGrid(config.stats)

                    // Two-column layout for activity and tasks
                    div(classes = "grid grid-cols-2 gap-lg mt-4") {
                        // Recent activity feed
                        activityFeed(config.recentActivity)

                        // Recent tasks
                        recentTasksList(config.recentTasks)
                    }
                }

                // Footer
                footer(classes = "main-footer") {
                    small {
                        +"Orchestrator Dashboard © 2025"
                    }
                }

                // JavaScript
                script(src = "/static/js/theme-toggle.js") {}
                script(src = "/static/js/navigation.js") {}

                // Auto-refresh script (HTMX)
                if (config.refreshInterval > 0) {
                    script {
                        unsafe {
                            +"""
                            // Auto-refresh stats every ${config.refreshInterval} seconds
                            setInterval(function() {
                                htmx.trigger('#quick-stats', 'refresh');
                            }, ${config.refreshInterval * 1000});
                            """.trimIndent()
                        }
                    }
                }
        }
    }

    private fun FlowContent.pageHeader() {
        div(classes = "flex justify-between items-center mb-lg") {
            div {
                h1(classes = "mt-0 mb-2") {
                    +"Dashboard"
                }
                p(classes = "text-muted mb-0") {
                    +"System overview and recent activity"
                }
            }

            div {
                span(classes = "badge badge-status-in-progress") {
                    +"System Active"
                }
            }
        }
    }

    private fun FlowContent.quickStatsGrid(stats: SystemStats) {
        div(classes = "grid grid-cols-4 gap-md mb-lg") {
            attributes["id"] = "quick-stats"
            attributes["hx-get"] = "/api/stats"
            attributes["hx-trigger"] = "refresh"
            attributes["hx-swap"] = "outerHTML"

            // Tasks stat
            statCard(
                label = "Total Tasks",
                value = stats.totalTasks.toString(),
                subtitle = "${stats.activeTasks} active",
                color = "primary"
            )

            // Completed tasks stat
            statCard(
                label = "Completed",
                value = stats.completedTasks.toString(),
                subtitle = if (stats.failedTasks > 0) "${stats.failedTasks} failed" else "All successful",
                color = "success"
            )

            // Index stat
            statCard(
                label = "Indexed Files",
                value = stats.indexedFiles.toString(),
                subtitle = "${stats.indexedChunks} chunks",
                color = "info"
            )

            // Embeddings stat
            statCard(
                label = "Embeddings",
                value = formatNumber(stats.totalEmbeddings),
                subtitle = "Vector database",
                color = "secondary"
            )
        }
    }

    private fun FlowContent.statCard(
        label: String,
        value: String,
        subtitle: String,
        color: String
    ) {
        div(classes = "card stat-card") {
            div(classes = "stat-card__value") {
                +value
            }
            div(classes = "stat-card__label") {
                +label
            }
            small(classes = "text-muted") {
                +subtitle
            }
        }
    }

    private fun FlowContent.activityFeed(activities: List<ActivityItem>) {
        div(classes = "card") {
            div(classes = "card-header") {
                h3(classes = "card-title") {
                    +"Recent Activity"
                }
            }
            div(classes = "card-body") {
                attributes["id"] = "activity-feed"
                attributes["hx-get"] = "/api/activity"
                attributes["hx-trigger"] = "every 60s"
                attributes["hx-swap"] = "innerHTML"

                if (activities.isEmpty()) {
                    div(classes = "text-center text-muted") {
                        +"No recent activity"
                    }
                } else {
                    ul(classes = "activity-list") {
                        attributes["role"] = "list"
                        activities.take(10).forEach { activity ->
                            activityItem(activity)
                        }
                    }
                }
            }
        }
    }

    private fun UL.activityItem(activity: ActivityItem) {
        li(classes = "activity-item") {
            attributes["role"] = "listitem"

            div(classes = "activity-item__icon") {
                span(classes = "badge badge-${activity.type.badgeClass}") {
                    +activity.type.icon
                }
            }

            div(classes = "activity-item__content") {
                if (activity.href != null) {
                    a(href = activity.href, classes = "activity-item__title") {
                        attributes["hx-boost"] = "true"
                        +activity.title
                    }
                } else {
                    div(classes = "activity-item__title") {
                        +activity.title
                    }
                }
                div(classes = "activity-item__description text-muted") {
                    +activity.description
                }
            }

            div(classes = "activity-item__time text-muted text-sm") {
                +formatRelativeTime(activity.timestamp)
            }
        }
    }

    private fun FlowContent.recentTasksList(tasks: List<Task>) {
        div(classes = "card") {
            div(classes = "card-header flex justify-between items-center") {
                h3(classes = "card-title mb-0") {
                    +"Recent Tasks"
                }
                a(href = "/tasks", classes = "text-sm") {
                    attributes["hx-boost"] = "true"
                    +"View all →"
                }
            }
            div(classes = "card-body") {
                if (tasks.isEmpty()) {
                    div(classes = "text-center text-muted") {
                        +"No tasks yet"
                    }
                } else {
                    ul(classes = "task-list") {
                        attributes["role"] = "list"
                        tasks.take(8).forEach { task ->
                            taskItem(task)
                        }
                    }
                }
            }
        }
    }

    private fun UL.taskItem(task: Task) {
        li(classes = "task-item") {
            attributes["role"] = "listitem"

            div(classes = "task-item__header") {
                span(classes = "badge badge-type-${task.type.name.lowercase()}") {
                    +task.type.displayName
                }
                span(classes = "badge badge-status-${task.status.name.lowercase()}") {
                    +task.status.displayName
                }
            }

            a(href = "/tasks/${task.id.value}", classes = "task-item__title") {
                attributes["hx-boost"] = "true"
                +task.title
            }

            task.description?.let { desc ->
                if (desc.isNotBlank()) {
                    p(classes = "task-item__description text-muted text-sm") {
                        val truncated = if (desc.length > 100) desc.take(100) + "..." else desc
                        +truncated
                    }
                }
            }

            div(classes = "task-item__meta text-muted text-sm") {
                +"Created ${formatRelativeTime(task.createdAt)}"
            }
        }
    }

    // Helper extensions for enum display names
    private val TaskStatus.displayName: String
        get() = when (this) {
            TaskStatus.PENDING -> "Pending"
            TaskStatus.IN_PROGRESS -> "In Progress"
            TaskStatus.WAITING_INPUT -> "Waiting Input"
            TaskStatus.COMPLETED -> "Completed"
            TaskStatus.FAILED -> "Failed"
        }

    private val TaskType.displayName: String
        get() = when (this) {
            TaskType.IMPLEMENTATION -> "Implementation"
            TaskType.ARCHITECTURE -> "Architecture"
            TaskType.REVIEW -> "Review"
            TaskType.RESEARCH -> "Research"
            TaskType.TESTING -> "Testing"
            TaskType.DOCUMENTATION -> "Documentation"
            TaskType.PLANNING -> "Planning"
            TaskType.BUGFIX -> "Bug Fix"
        }

    private val ActivityType.badgeClass: String
        get() = when (this) {
            ActivityType.TASK_CREATED -> "status-pending"
            ActivityType.TASK_COMPLETED -> "status-completed"
            ActivityType.TASK_FAILED -> "status-failed"
            ActivityType.INDEX_UPDATE -> "type-research"
            ActivityType.METRIC_RECORDED -> "type-testing"
        }

    private val ActivityType.icon: String
        get() = when (this) {
            ActivityType.TASK_CREATED -> "+"
            ActivityType.TASK_COMPLETED -> "✓"
            ActivityType.TASK_FAILED -> "✗"
            ActivityType.INDEX_UPDATE -> "⟳"
            ActivityType.METRIC_RECORDED -> "📊"
        }

    private fun formatNumber(num: Int): String {
        return when {
            num >= 1_000_000 -> String.format("%.1fM", num / 1_000_000.0)
            num >= 1_000 -> String.format("%.1fK", num / 1_000.0)
            else -> num.toString()
        }
    }

    private fun formatRelativeTime(instant: Instant): String {
        val now = Instant.now()
        val duration = Duration.between(instant, now)

        return when {
            duration.seconds < 60 -> "just now"
            duration.toMinutes() < 60 -> "${duration.toMinutes()}m ago"
            duration.toHours() < 24 -> "${duration.toHours()}h ago"
            duration.toDays() < 7 -> "${duration.toDays()}d ago"
            duration.toDays() < 30 -> "${duration.toDays() / 7}w ago"
            else -> {
                val formatter = DateTimeFormatter.ofPattern("MMM d")
                    .withZone(ZoneId.systemDefault())
                formatter.format(instant)
            }
        }
    }
}
