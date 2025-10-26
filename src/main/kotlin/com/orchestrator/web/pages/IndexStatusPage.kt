package com.orchestrator.web.pages

import com.orchestrator.web.components.StatusBadge
import com.orchestrator.web.dto.FileStateDTO
import com.orchestrator.web.dto.IndexStatusDTO
import com.orchestrator.web.rendering.PageLayout
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.TBODY
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.li
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.thead
import kotlinx.html.th
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.ul
import kotlinx.html.unsafe
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

object IndexStatusPage {

    enum class ProviderHealth { HEALTHY, UNAVAILABLE, DISABLED }

    data class ProviderStatus(
        val id: String,
        val displayName: String,
        val type: String?,
        val weight: Double,
        val health: ProviderHealth
    )

    data class AdminAction(
        val id: String,
        val label: String,
        val description: String,
        val hxPost: String,
        val icon: String,
        val confirm: String? = null
    )

    data class Config(
        val status: IndexStatusDTO,
        val providers: List<ProviderStatus>,
        val actions: List<AdminAction>,
        val generatedAt: Instant
    )

    private val isoFormatter: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)
    private val displayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
        .withZone(ZoneOffset.UTC)

    fun render(config: Config): String {
        val htmlContent = createHTML().html {
            head {
                meta(charset = "utf-8")
                meta(name = "viewport", content = "width=device-width, initial-scale=1")
                title("Index Status - Orchestrator")

                link(rel = "stylesheet", href = "/static/css/base.css")
                link(rel = "stylesheet", href = "/static/css/orchestrator.css")
                link(rel = "stylesheet", href = "/static/css/dark-mode.css")

                script(src = "/static/js/htmx.min.js") {}
                // Load our custom SSE extension IMMEDIATELY after HTMX
                // Must be before body hx-ext="sse" is processed
                script(src = "/static/js/htmx-sse.min.js") {}
                // sse-handler.js is redundant now - our htmx-sse.min.js handles everything
                script(src = "/static/js/app.js") {}
            }

            body(classes = "dashboard-layout") {
                // Don't use hx-ext="sse" here - we'll register and connect manually in our extension
                // This prevents HTMX's built-in SSE from conflicting with our custom implementation
                attributes["data-sse-url"] = "/sse/index"

                with(PageLayout) {
                    dashboardShell(
                        pageTitle = "Index Status",
                        currentPath = "/index"
                    ) {
                        div { populateContainer(config) }
                    }
                }

                script {
                    unsafe {
                        +"""
                            (function() {
                                var INDEX_ACTION_SELECTOR = '[data-index-action]';

                                function ensureSSE(force) {
                                    try {
                                        document.body.setAttribute('data-sse-url', '/sse/index');
                                        if (typeof window.__initIndexSSE === 'function') {
                                            window.__initIndexSSE(!!force);
                                        }
                                    } catch (err) {
                                        console.warn('Failed to initialize index SSE connection:', err);
                                    }
                                }

                                function showPendingState(label) {
                                    var region = document.getElementById('index-progress-region');
                                    if (!region) {
                                        return;
                                    }

                                    region.classList.remove('index-progress--idle');
                                    region.classList.add('index-progress', 'index-progress--pending');

                                    var safeLabel = label || 'Index Operation';
                                    region.innerHTML = ''
                                        + '<div class="index-progress__header">'
                                        +   '<span class="index-progress__title">' + safeLabel + '</span>'
                                        +   '<span class="index-progress__value">Preparing…</span>'
                                        + '</div>'
                                        + '<progress class="index-progress__bar" max="100" value="0"></progress>'
                                        + '<div class="index-progress__meta">Waiting for server updates…</div>';
                                }

                                function bindIndexActionButtons(root) {
                                    var scope = root || document;
                                    var buttons = scope.querySelectorAll(INDEX_ACTION_SELECTOR);
                                    buttons.forEach(function(btn) {
                                        if (btn.__indexActionBound) {
                                            return;
                                        }
                                        btn.__indexActionBound = true;

                                        btn.addEventListener('click', function(event) {
                                            var endpoint = btn.getAttribute('data-action-endpoint');
                                            if (!endpoint) {
                                                return;
                                            }

                                            var confirmMessage = btn.getAttribute('data-action-confirm');
                                            if (confirmMessage && !window.confirm(confirmMessage)) {
                                                return;
                                            }

                                            event.preventDefault();
                                            var label = btn.getAttribute('data-action-label') || btn.textContent || '';

                                            ensureSSE(true);
                                            showPendingState(label.trim());

                                            fetch(endpoint, {
                                                method: 'POST',
                                                credentials: 'same-origin',
                                                headers: {
                                                    'X-Requested-With': 'fetch'
                                                }
                                            }).catch(function(error) {
                                                console.error('Index action request failed:', error);
                                            });
                                        });
                                    });
                                }

                                function initializeIndexPage(root) {
                                    ensureSSE(true);
                                    bindIndexActionButtons(root);
                                }

                                if (document.readyState === 'loading') {
                                    document.addEventListener('DOMContentLoaded', function() {
                                        initializeIndexPage();
                                    }, { once: true });
                                } else {
                                    initializeIndexPage();
                                }

                                document.addEventListener('htmx:afterSwap', function(evt) {
                                    initializeIndexPage(evt.target);
                                });

                                document.addEventListener('htmx:afterSettle', function() {
                                    ensureSSE();
                                });
                            })();
                        """.trimIndent()
                    }
                }
            }
        }
        return "<!DOCTYPE html>\n$htmlContent"
    }

    fun renderContainer(config: Config): String = createHTML().div {
        populateContainer(config)
    }

    internal fun renderSummaryFragment(status: IndexStatusDTO): String = createHTML().div {
        attributes["id"] = "index-summary"
        attributes["class"] = "grid grid-cols-1 grid-cols-md-2 grid-cols-lg-4 gap-md mt-lg"
        attributes["sse-swap"] = "indexSummary"
        attributes["hx-swap"] = "outerHTML"

        summaryCard(
            testId = "stat-total-files",
            label = "Total Files",
            value = formatNumber(status.totalFiles)
        )
        summaryCard(
            testId = "stat-indexed-files",
            label = "Indexed",
            value = formatNumber(status.indexedFiles)
        )
        summaryCard(
            testId = "stat-pending-files",
            label = "Pending",
            value = formatNumber(status.pendingFiles)
        )
        summaryCard(
            testId = "stat-failed-files",
            label = "Failed",
            value = formatNumber(status.failedFiles)
        )
    }

    private fun FlowContent.pageHeader(config: Config) {
        val healthLabel = config.status.health.toLabel()
        val healthTone = config.status.health.toTone()

        div(classes = "flex items-center justify-between mb-lg page-header") {
            div {
                h2(classes = "mt-0 mb-1") {
                    +"Index Status"
                }
                p(classes = "text-muted") {
                    attributes["data-testid"] = "index-generated-at"
                    +"Generated ${displayFormatter.format(config.generatedAt)}"
                }
            }

            div(classes = "flex items-center gap-sm") {
                span(classes = "text-muted") {
                    +"Overall Health"
                }
                with(StatusBadge) {
                    badge(
                        StatusBadge.Config(
                            label = healthLabel,
                            tone = healthTone,
                            ariaLabel = "Index health $healthLabel"
                        )
                    )
                }
            }
        }

        div(classes = "card mt-narrow") {
            div(classes = "flex flex-wrap items-center gap-md") {
                div {
                    span(classes = "text-muted block") { +"Last Refresh" }
                    p(classes = "font-semibold mb-0") {
                        attributes["data-testid"] = "index-last-refresh"
                        +formatTimestamp(config.status.lastRefresh)
                    }
                }

                if (config.providers.isNotEmpty()) {
                    div {
                        span(classes = "text-muted block") { +"Providers" }
                        p(classes = "font-semibold mb-0") {
                            val healthyProviders = config.providers.count { it.health == ProviderHealth.HEALTHY }
                            +("$healthyProviders/${config.providers.size} Healthy")
                        }
                    }
                }

                div {
                    span(classes = "text-muted block") { +"Pending Files" }
                    p(classes = "font-semibold mb-0") {
                        +config.status.pendingFiles.toString()
                    }
                }
            }
        }
    }

    private fun FlowContent.summarySection(config: Config) {
        div(classes = "grid grid-cols-1 grid-cols-md-2 grid-cols-lg-4 gap-md mt-lg") {
            attributes["id"] = "index-summary"
            attributes["sse-swap"] = "indexSummary"
            attributes["hx-swap"] = "outerHTML"

            summaryCard(
                testId = "stat-total-files",
                label = "Total Files",
                value = formatNumber(config.status.totalFiles)
            )
            summaryCard(
                testId = "stat-indexed-files",
                label = "Indexed",
                value = formatNumber(config.status.indexedFiles)
            )
            summaryCard(
                testId = "stat-pending-files",
                label = "Pending",
                value = formatNumber(config.status.pendingFiles)
            )
            summaryCard(
                testId = "stat-failed-files",
                label = "Failed",
                value = formatNumber(config.status.failedFiles)
            )
        }
    }

    private fun FlowContent.summaryCard(testId: String, label: String, value: String) {
        div(classes = "card stat-card") {
            attributes["data-testid"] = testId
            div(classes = "stat-card__value") { +value }
            div(classes = "stat-card__label") { +label }
        }
    }

    private fun FlowContent.progressSection() {
        div(classes = "card mt-xl") {
            h3(classes = "mt-0") { +"Index Operations" }
            p(classes = "text-muted mb-md") {
                +"Live progress for refresh and rebuild jobs."
            }

            div {
                attributes["id"] = "index-progress-region"
                attributes["class"] = "index-progress index-progress--idle"
                attributes["sse-swap"] = "indexProgress"
                attributes["hx-swap"] = "outerHTML"

                span(classes = "text-muted") {
                    +"No active operations."
                }
            }
        }
    }

    private fun FlowContent.adminActions(actions: List<AdminAction>) {
        div(classes = "card mt-xl") {
            h3(classes = "mt-0") { +"Admin Actions" }
            p(classes = "text-muted mb-md") {
                +"Run maintenance tasks against the context index."
            }

            div(classes = "flex flex-wrap gap-sm") {
                actions.forEach { action ->
                    button(classes = "button button--primary") {
                        attributes["type"] = "button"
                        attributes["data-testid"] = "action-${action.id}"
                        attributes["data-index-action"] = action.id
                        attributes["data-action-endpoint"] = action.hxPost
                        attributes["data-action-label"] = action.label
                        action.confirm?.let { confirm ->
                            attributes["data-action-confirm"] = confirm
                        }
                        span(classes = "mr-xs") { +action.icon }
                        +action.label
                    }
                }
            }

            if (actions.isNotEmpty()) {
                ul(classes = "text-muted small mt-md") {
                    actions.forEach { action ->
                        li {
                            +action.description
                        }
                    }
                }
            }
        }
    }

    private fun FlowContent.providerSection(providers: List<ProviderStatus>) {
        div(classes = "card mt-xl") {
            h3(classes = "mt-0") { +"Provider Health" }

            if (providers.isEmpty()) {
                p(classes = "text-muted") {
                    +"No providers configured. Update context configuration to enable providers."
                }
                return@div
            }

            table(classes = "data-table mt-md") {
                thead {
                    tr {
                        th { +"Provider" }
                        th { +"Type" }
                        th { +"Weight" }
                        th { +"Status" }
                    }
                }
                tbody {
                    providers.forEach { provider ->
                        tr {
                            attributes["data-testid"] = "provider-${provider.id}"
                            td {
                                span(classes = "font-semibold") { +provider.displayName }
                            }
                            td {
                                +(provider.type?.uppercase() ?: "—")
                            }
                            td {
                                +"${"%.2f".format(Locale.US, provider.weight)}"
                            }
                            td {
                                attributes["data-testid"] = "provider-${provider.id}-status"
                                val (label, tone) = provider.health.toLabelAndTone()
                                with(StatusBadge) {
                                    badge(StatusBadge.Config(label = label, tone = tone))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun FlowContent.filesSection(status: IndexStatusDTO) {
        val files = status.files
        val visibleFiles = files.take(50)

        div(classes = "card mt-xl") {
            h3(classes = "mt-0") { +"Indexed Files" }

            if (files.isEmpty()) {
                p(classes = "text-muted") {
                    +"No files indexed yet. Trigger a refresh to populate the index."
                }
                return@div
            }

            p(classes = "text-muted mb-md") {
                attributes["data-testid"] = "file-count-summary"
                +"Showing ${visibleFiles.size} of ${files.size} files"
                if (files.size > visibleFiles.size) {
                    +" (displaying first 50)"
                }
            }

            table(classes = "data-table") {
                thead {
                    tr {
                        th { +"File" }
                        th { +"Status" }
                        th { +"Size" }
                        th { +"Chunks" }
                        th { +"Last Modified" }
                    }
                }
                tbody {
                    for (file in visibleFiles) {
                        fileRow(file)
                    }
                }
            }
        }
    }

    private fun TBODY.fileRow(file: FileStateDTO) {
        tr {
            attributes["data-testid"] = "file-row-${file.path}"
            td {
                span(classes = "font-mono") { +file.path }
            }
            td {
                val tone = file.status.toFileTone()
                val label = file.status.toStatusLabel()
                with(StatusBadge) {
                    badge(StatusBadge.Config(label = label, tone = tone))
                }
            }
            td {
                +formatSize(file.sizeBytes)
            }
            td {
                +file.chunkCount.toString()
            }
            td {
                +formatTimestamp(file.lastModified)
            }
        }
    }

    private fun String?.toLabel(): String =
        this?.takeIf { it.isNotBlank() }?.let { value ->
            value.lowercase(Locale.US).replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase(Locale.US) else ch.toString()
            }
        } ?: "Unknown"

    private fun String?.toTone(): StatusBadge.Tone = when (this?.lowercase(Locale.US)) {
        "healthy" -> StatusBadge.Tone.SUCCESS
        "degraded" -> StatusBadge.Tone.WARNING
        "critical" -> StatusBadge.Tone.DANGER
        else -> StatusBadge.Tone.INFO
    }

    private fun ProviderHealth.toLabelAndTone(): Pair<String, StatusBadge.Tone> = when (this) {
        ProviderHealth.HEALTHY -> "Healthy" to StatusBadge.Tone.SUCCESS
        ProviderHealth.UNAVAILABLE -> "Unavailable" to StatusBadge.Tone.WARNING
        ProviderHealth.DISABLED -> "Disabled" to StatusBadge.Tone.DEFAULT
    }

    private fun String.toStatusLabel(): String =
        lowercase(Locale.US).replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase(Locale.US) else ch.toString()
        }

    private fun String.toFileTone(): StatusBadge.Tone = when (lowercase(Locale.US)) {
        "indexed" -> StatusBadge.Tone.SUCCESS
        "pending" -> StatusBadge.Tone.INFO
        "outdated" -> StatusBadge.Tone.WARNING
        "error" -> StatusBadge.Tone.DANGER
        else -> StatusBadge.Tone.DEFAULT
    }

    private fun formatTimestamp(value: String?): String {
        if (value.isNullOrBlank()) return "Never"
        return runCatching { displayFormatter.format(Instant.parse(value)) }.getOrElse { value }
    }

    private fun formatNumber(number: Int): String = "%,d".format(Locale.US, number)

    private fun formatSize(sizeBytes: Long): String {
        if (sizeBytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (ln(sizeBytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.lastIndex)
        val value = sizeBytes / 1024.0.pow(digitGroups.toDouble())
        return "%.1f %s".format(Locale.US, value, units[digitGroups])
    }

    private fun DIV.populateContainer(config: Config) {
        attributes["id"] = "index-status-container"
        pageHeader(config)
        summarySection(config)
        progressSection()
        adminActions(config.actions)
        providerSection(config.providers)
        filesSection(config.status)
    }
}
