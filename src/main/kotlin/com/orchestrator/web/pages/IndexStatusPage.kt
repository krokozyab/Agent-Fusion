package com.orchestrator.web.pages

import com.orchestrator.web.components.StatusBadge
import com.orchestrator.web.dto.FileStateDTO
import com.orchestrator.web.dto.FilesystemStatusDTO
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
import kotlinx.html.style
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
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

object IndexStatusPage {

    enum class ProviderHealth { HEALTHY, UNAVAILABLE, DISABLED }
    enum class StatTone { NEUTRAL, WARNING, DANGER }

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
                link(rel = "stylesheet", href = "/static/css/bootstrap-litera.min.css")
                link(rel = "stylesheet", href = "/static/css/orchestrator.css")

                style {
                    unsafe {
                        +"""
                            #index-status-container { padding: 0 !important; margin: 0 !important; }
                            #index-status-container .page-header { margin-bottom: 0.5rem !important; }
                            #index-status-container .card { padding: 0.5rem 0.75rem !important; margin: 0 !important; }
                            #index-status-container .stat-card { padding: 0.25rem 0.5rem !important; }
                            #index-status-container .stat-card__value { font-size: 1.25rem !important; margin-bottom: 0.125rem !important; }
                            #index-status-container .stat-card__label { font-size: 0.65rem !important; margin: 0 !important; }
                            #index-status-container .stat-card__subtext { font-size: 0.65rem !important; display: block !important; opacity: 0.8; margin-top: 0.125rem !important; }
                            #index-status-container .stat-card__value--warning { color: #b45309 !important; }
                            #index-status-container .stat-card__value--danger { color: #b91c1c !important; }
                            #index-status-container .grid { gap: 0.5rem !important; }
                            #index-status-container h2 { margin: 0 0 0.25rem 0 !important; font-size: 1.25rem !important; }
                            #index-status-container h3 { margin: 0.25rem 0 !important; font-size: 1rem !important; }
                            #index-status-container h4 { margin: 0.25rem 0 !important; font-size: 0.95rem !important; }
                            #index-status-container p { margin: 0.125rem 0 !important; font-size: 0.875rem !important; }
                            #index-status-container ul { margin: 0.25rem 0 !important; padding-left: 1rem !important; }
                            #index-status-container li { margin: 0.125rem 0 !important; }
                            #index-status-container .mt-lg { margin-top: 0.5rem !important; }
                            #index-status-container .mt-xl { margin-top: 0.5rem !important; }
                            #index-status-container .mb-lg { margin-bottom: 0.5rem !important; }
                            #index-status-container .gap-lg { gap: 0.5rem !important; }
                            #index-status-container .gap-md { gap: 0.375rem !important; }
                        """.trimIndent()
                    }
                }

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
                                var sseConnection = null;

                                function ensureSSE(force) {
                                    console.log('[ensureSSE] Called with force=' + force);

                                    // If we already have an active connection, reuse it unless force=true and it's closed
                                    if (sseConnection) {
                                        var state = sseConnection.readyState;
                                        console.log('[ensureSSE] Existing connection found, readyState:', state);
                                        if (state === EventSource.OPEN || state === EventSource.CONNECTING) {
                                            console.log('[ensureSSE] Reusing active connection');
                                            return;
                                        }
                                    }

                                    try {
                                        var sseUrl = '/sse/index';
                                        console.log('[ensureSSE] Creating EventSource for:', sseUrl);

                                        sseConnection = new EventSource(sseUrl, { withCredentials: true });

                                        sseConnection.addEventListener('open', function() {
                                            console.log('[SSE] Connection opened');
                                        });

                                        function registerHandlers(source) {
                                            source.addEventListener('indexProgress', function(event) {
                                                console.log('[SSE] indexProgress event received');
                                                handleIndexProgressEvent(event.data);
                                            });

                                            source.addEventListener('indexSummary', function(event) {
                                                console.log('[SSE] indexSummary event received');
                                                handleIndexSummaryEvent(event.data);
                                            });

                                            source.addEventListener('error', function(err) {
                                                if (!source || source.readyState === EventSource.CLOSED) {
                                                    console.warn('[SSE] EventSource closed, will attempt reconnect');
                                                } else {
                                                    console.error('[SSE] SSE error', err);
                                                }
                                                if (sseConnection) {
                                                    sseConnection.close();
                                                }
                                                sseConnection = null;
                                                setTimeout(function() { ensureSSE(true); }, 1000);
                                            });
                                        }

                                        sseConnection.addEventListener('open', function() {
                                            console.log('[SSE] Connection opened (index stream)');
                                        });

                                        registerHandlers(sseConnection);

                                        console.log('[ensureSSE] EventSource created successfully');
                                    } catch (err) {
                                        console.error('[ensureSSE] Failed to create SSE connection:', err);
                                    }
                                }

                                function handleIndexProgressEvent(data) {
                                    try {
                                        // Data is already HTML, not JSON
                                        console.log('[SSE] Updating progress region with HTML fragment');
                                        var container = document.getElementById('index-progress-region');
                                        if (!container) {
                                            console.warn('[SSE] index-progress-region not found');
                                            return;
                                        }

                                        container.outerHTML = data;
                                        console.log('[SSE] Progress region updated successfully');
                                    } catch (err) {
                                        console.error('[SSE] Failed to update indexProgress:', err);
                                    }
                                }

                                function handleIndexSummaryEvent(data) {
                                    try {
                                        // Data is already HTML, not JSON
                                        console.log('[SSE] Received indexSummary, swapping summary container');
                                        var container = document.getElementById('index-summary');
                                        if (!container) {
                                            console.warn('[SSE] index-status-summary-container not found');
                                            return;
                                        }

                                        container.outerHTML = data;
                                        console.log('[SSE] Summary container updated successfully');

                                        // Summary update indicates operation completion - enable buttons as backup trigger
                                        console.log('[Button Control] Summary updated, enabling buttons');
                                        enableAllIndexButtons();
                                    } catch (err) {
                                        console.error('[SSE] Failed to update indexSummary:', err);
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

                                function disableAllIndexButtons() {
                                    console.log('[Button Control] Disabling all index operation buttons');
                                    var buttons = document.querySelectorAll(INDEX_ACTION_SELECTOR);
                                    buttons.forEach(function(btn) {
                                        btn.disabled = true;
                                        btn.classList.add('button--disabled');
                                    });
                                }

                                function enableAllIndexButtons() {
                                    console.log('[Button Control] Enabling all index operation buttons');
                                    var buttons = document.querySelectorAll(INDEX_ACTION_SELECTOR);
                                    buttons.forEach(function(btn) {
                                        btn.disabled = false;
                                        btn.classList.remove('button--disabled');
                                    });
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
                                            disableAllIndexButtons();

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

                                // Immediately initialize SSE connection when this script executes
                                // This ensures SSE is ready even when navigating to /index via HTMX
                                console.log('IndexStatusPage inline script executing, scheduling SSE init...');
                                setTimeout(function() {
                                    console.log('Calling ensureSSE(true) from setTimeout...');
                                    ensureSSE(true);
                                }, 100);
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
            label = "Catalog (Indexed)",
            value = formatNumber(status.totalFiles),
            subtitle = buildCatalogSubtitle(status),
            tone = computeCatalogTone(status)
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

        status.filesystem?.let { filesystem ->
            filesystemDetailCard(filesystem)
        }
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
                label = "Catalog (Indexed)",
                value = formatNumber(config.status.totalFiles),
                subtitle = buildCatalogSubtitle(config.status),
                tone = computeCatalogTone(config.status)
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

        config.status.filesystem?.let { filesystem ->
            filesystemDetailCard(filesystem)
        }
    }
    }

    private fun FlowContent.summaryCard(
        testId: String,
        label: String,
        value: String,
        subtitle: String? = null,
        tone: StatTone = StatTone.NEUTRAL
    ) {
        div(classes = "card stat-card") {
            attributes["data-testid"] = testId
            val valueClasses = buildString {
                append("stat-card__value")
                when (tone) {
                    StatTone.WARNING -> append(" stat-card__value--warning")
                    StatTone.DANGER -> append(" stat-card__value--danger")
                    else -> {}
                }
            }
            div(classes = valueClasses) { +value }
            div(classes = "stat-card__label") { +label }
            subtitle?.let {
                span(classes = "stat-card__subtext text-muted") { +it }
            }
        }
    }

    private fun FlowContent.progressSection() {
        div(classes = "card") {
            h3(classes = "mt-0") { +"Index Operations" }
            p(classes = "text-muted mb-md") {
                +"Live progress for rebuild jobs."
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
        div(classes = "card") {
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


    private fun FlowContent.filesSection(files: List<FileStateDTO>) {
        div(classes = "card mt-xl") {
            h3(classes = "mt-0") { +"Indexed Files" }

            if (files.isEmpty()) {
                p(classes = "text-muted") {
                    +"No files indexed yet."
                }
                return@div
            }

            table(classes = "data-table mt-md") {
                thead {
                    tr {
                        th { +"File Path" }
                        th { +"Status" }
                        th { +"Size" }
                        th { +"Chunks" }
                    }
                }
                tbody {
                    files.forEach { file ->
                        tr {
                            attributes["data-testid"] = "file-row-${file.path}"
                            td {
                                +file.path
                            }
                            td {
                                +(file.status?.toStatusLabel() ?: "—")
                            }
                            td {
                                +formatSize(file.sizeBytes)
                            }
                            td {
                                +file.chunkCount.toString()
                            }
                        }
                    }
                }
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

    private fun computeCatalogTone(status: IndexStatusDTO): StatTone {
        val filesystem = status.filesystem ?: return StatTone.NEUTRAL
        val stableCatalog = status.indexedFiles
        val delta = filesystem.totalFiles - stableCatalog
        return when {
            delta == 0 -> StatTone.NEUTRAL
            delta > 0 -> StatTone.WARNING
            else -> StatTone.DANGER
        }
    }

    private fun buildCatalogSubtitle(status: IndexStatusDTO): String {
        val parts = mutableListOf<String>()
        parts += "DB rows ${formatNumber(status.totalFiles)}"
        if (status.pendingFiles > 0) {
            parts += "pending ${formatNumber(status.pendingFiles)}"
        }

        status.filesystem?.let { filesystem ->
            val stableCatalog = status.indexedFiles
            val delta = filesystem.totalFiles - stableCatalog
            val deltaText = when {
                delta == 0 -> "Δ 0"
                else -> "Δ ${formatSigned(delta)}"
            }
            parts += "filesystem ${formatNumber(filesystem.totalFiles)} ($deltaText)"
        }

        return parts.joinToString(" · ")
    }

    private fun FlowContent.filesystemDetailCard(filesystem: FilesystemStatusDTO) {
        if (filesystem.roots.isEmpty()) return

        div(classes = "card stat-card mt-sm") {
            attributes["data-testid"] = "filesystem-roots-card"
            attributes["style"] = "grid-column: 1 / -1;"

            div(classes = "stat-card__label text-muted mb-1") {
                +"Watch Roots"
            }

            ul(classes = "list-unstyled mb-0") {
                filesystem.roots.forEach { root ->
                    li {
                        attributes["data-testid"] = "filesystem-root-${root.path}"
                        span(classes = "font-semibold") {
                            +formatNumber(root.totalFiles)
                        }
                        +" · "
                        span(classes = "font-monospace text-muted") {
                            +root.path
                        }
                    }
                }
            }

            if (filesystem.missingTotal > 0) {
                div(classes = "mt-sm") {
                    span(classes = "stat-card__label text-muted") {
                        +"Present on disk, missing from catalog (${filesystem.missingTotal})"
                    }
                    ul(classes = "list-unstyled mb-0 mt-xs") {
                        filesystem.missingFromCatalog.forEach { path ->
                            li {
                                attributes["data-testid"] = "filesystem-missing-${path.hashCode()}"
                                +"• "
                                span(classes = "font-monospace") { +path }
                            }
                        }
                        if (filesystem.missingFromCatalog.size < filesystem.missingTotal) {
                            li(classes = "text-muted") {
                                +"• …and ${filesystem.missingTotal - filesystem.missingFromCatalog.size} more"
                            }
                        }
                    }
                }
            }

            if (filesystem.orphanedTotal > 0) {
                div(classes = "mt-sm") {
                    span(classes = "stat-card__label text-muted") {
                        +"In catalog, missing on disk (${filesystem.orphanedTotal})"
                    }
                    ul(classes = "list-unstyled mb-0 mt-xs") {
                        filesystem.orphanedInCatalog.forEach { path ->
                            li {
                                attributes["data-testid"] = "filesystem-orphaned-${path.hashCode()}"
                                +"• "
                                span(classes = "font-monospace") { +path }
                            }
                        }
                        if (filesystem.orphanedInCatalog.size < filesystem.orphanedTotal) {
                            li(classes = "text-muted") {
                                +"• …and ${filesystem.orphanedTotal - filesystem.orphanedInCatalog.size} more"
                            }
                        }
                    }
                }
            }

            filesystem.scannedAt?.let { timestamp ->
                span(classes = "stat-card__subtext text-muted mt-1") {
                    +"Scanned ${formatTimestamp(timestamp)}"
                }
            }
        }
    }

    private fun formatSigned(number: Int): String {
        val formatted = formatNumber(abs(number))
        return if (number >= 0) "+$formatted" else "-$formatted"
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

        config.status.filesystem
            ?.takeIf { it.totalFiles != config.status.totalFiles }
            ?.let { filesystem ->
                val delta = filesystem.totalFiles - config.status.totalFiles
                div(classes = "alert alert-warning mt-sm") {
                    attributes["role"] = "alert"
                    attributes["data-testid"] = "filesystem-mismatch-alert"
                    +"Filesystem scan found ${formatNumber(filesystem.totalFiles)} files, "
                    +"while the index catalog reports ${formatNumber(config.status.totalFiles)}. "
                    +"Δ ${formatSigned(delta)} files need reconciliation."
                }
            }

        // Two-column layout for Index Operations and Admin Actions
        div(classes = "grid grid-cols-2 gap-lg mt-xl") {
            progressSection()
            adminActions(config.actions)
        }

        providerSection(config.providers)
        filesSection(config.status.files)
    }
}
