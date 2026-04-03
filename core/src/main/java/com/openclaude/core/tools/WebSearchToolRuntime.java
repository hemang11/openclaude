package com.openclaude.core.tools;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaude.provider.spi.ProviderId;
import com.openclaude.provider.spi.ProviderToolDefinition;
import com.openclaude.provider.spi.WebSearchResultContentBlock;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WebSearchToolRuntime extends AbstractSingleToolRuntime {
    private static final String TOOL_NAME = "WebSearch";
    private static final ProviderToolDefinition DEFINITION = new ProviderToolDefinition(
            TOOL_NAME,
            buildDescription(),
            """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "The search query to use."
                },
                "allowed_domains": {
                  "type": "array",
                  "description": "Only include search results from these domains.",
                  "items": { "type": "string" }
                },
                "blocked_domains": {
                  "type": "array",
                  "description": "Never include search results from these domains.",
                  "items": { "type": "string" }
                }
              },
              "required": ["query"],
              "additionalProperties": false
            }
            """
    );
    private static final int MAX_RESULTS = 8;
    private static final Pattern SEARCH_RESULT_LINK_PATTERN = Pattern.compile(
            "(?is)<a[^>]*class\\s*=\\s*\"[^\"]*(?:result__a|result-link)[^\"]*\"[^>]*href\\s*=\\s*\"([^\"]+)\"[^>]*>(.*?)</a>"
    );
    private static final Pattern FALLBACK_LINK_PATTERN = Pattern.compile(
            "(?is)<a[^>]*href\\s*=\\s*\"([^\"]+)\"[^>]*>(.*?)</a>"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final URI searchEndpoint;

    public WebSearchToolRuntime() {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                URI.create("https://duckduckgo.com/html/")
        );
    }

    WebSearchToolRuntime(HttpClient httpClient, URI searchEndpoint) {
        super(DEFINITION);
        this.httpClient = httpClient;
        this.searchEndpoint = searchEndpoint;
    }

    @Override
    protected boolean isConcurrencySafeSingle(String inputJson) {
        try {
            WebSearchInput input = objectMapper.readValue(inputJson, WebSearchInput.class);
            return normalize(input.query()).length() >= 2;
        } catch (IOException exception) {
            return false;
        }
    }

    @Override
    protected ToolExecutionResult executeSingle(
            ToolExecutionRequest request,
            ToolPermissionGateway permissionGateway,
            Consumer<ToolExecutionUpdate> updateConsumer
    ) {
        WebSearchInput input;
        try {
            input = objectMapper.readValue(request.inputJson(), WebSearchInput.class);
        } catch (IOException exception) {
            emit(updateConsumer, request, "failed", "Invalid WebSearch input: " + exception.getMessage(), "", true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Invalid WebSearch input: " + exception.getMessage(), true);
        }

        String query = normalize(input.query());
        List<String> allowedDomains = normalizeDomains(input.allowed_domains());
        List<String> blockedDomains = normalizeDomains(input.blocked_domains());
        ToolModelInvoker modelInvoker = request.modelInvoker();
        ProviderId nativeProviderId = modelInvoker == null ? null : modelInvoker.providerId();
        if (query.length() < 2) {
            emit(updateConsumer, request, "failed", "WebSearch requires a query with at least 2 characters.", "", true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "WebSearch requires a query with at least 2 characters.", true);
        }
        if (!allowedDomains.isEmpty() && !blockedDomains.isEmpty()) {
            emit(updateConsumer, request, "failed", "WebSearch cannot combine allowed_domains and blocked_domains.", "", true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "WebSearch cannot combine allowed_domains and blocked_domains.", true);
        }

        emit(updateConsumer, request, "started", "Queued web search.", query, false);
        ToolPermissionDecision permission = requestPermission(
                request,
                permissionGateway,
                updateConsumer,
                query,
                "Allow web search for \"" + query + "\"?",
                "web_search",
                request.inputJson()
        );
        if (!permission.allowed()) {
            String text = "Permission denied: " + permission.reason();
            emit(updateConsumer, request, "failed", text, query, true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), text, true);
        }

        try {
            List<SearchHit> hits;
            if (supportsNativeProvider(nativeProviderId)) {
                hits = searchWithProvider(request, query, allowedDomains, blockedDomains, updateConsumer);
            } else {
                emitProviderSearchProgress(
                        updateConsumer,
                        request,
                        new ToolModelProgress("web_search", "query_update", query, 0),
                        query
                );
                hits = search(query, allowedDomains, blockedDomains);
                emitProviderSearchProgress(
                        updateConsumer,
                        request,
                        new ToolModelProgress("web_search", "search_results_received", "", hits.size()),
                        query
                );
            }
            String summary = "Found " + hits.size() + " result" + (hits.size() == 1 ? "" : "s") + " for \"" + query + "\".";
            emit(updateConsumer, request, "completed", summary, query, false);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), formatResult(query, hits, allowedDomains, blockedDomains), false);
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String text = "WebSearch failed: " + exception.getMessage();
            emit(updateConsumer, request, "failed", text, query, true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), text, true);
        }
    }

    private List<SearchHit> search(String query, List<String> allowedDomains, List<String> blockedDomains) throws IOException, InterruptedException {
        URI uri = buildSearchUri(query);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "openclaude/0.1")
                .header("Accept", "text/html,application/xhtml+xml")
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode());
        }

        List<SearchHit> parsed = parseSearchResults(response.body());
        List<SearchHit> filtered = new ArrayList<>();
        for (SearchHit hit : parsed) {
            if (!domainAllowed(hit.url(), allowedDomains, blockedDomains)) {
                continue;
            }
            filtered.add(hit);
            if (filtered.size() == MAX_RESULTS) {
                break;
            }
        }
        return List.copyOf(filtered);
    }

    private List<SearchHit> searchWithProvider(
            ToolExecutionRequest request,
            String query,
            List<String> allowedDomains,
            List<String> blockedDomains,
            Consumer<ToolExecutionUpdate> updateConsumer
    ) throws IOException, InterruptedException {
        ToolModelInvoker modelInvoker = request.modelInvoker();
        if (modelInvoker == null) {
            return search(query, allowedDomains, blockedDomains);
        }
        ProviderId providerId = modelInvoker.providerId();
        if (!supportsNativeProvider(providerId)) {
            return search(query, allowedDomains, blockedDomains);
        }

        ToolModelResponse response = modelInvoker.invoke(
                new ToolModelRequest(
                        "Perform a web search for the query: " + query,
                        "You are an assistant for performing a web search tool use.",
                        preferredSearchModel(providerId, modelInvoker.currentModelId()),
                        List.of(nativeSearchTool(providerId, allowedDomains, blockedDomains)),
                        providerId == ProviderId.ANTHROPIC ? "web_search" : null,
                        true
                ),
                progress -> emitProviderSearchProgress(updateConsumer, request, progress, query)
        );
        List<SearchHit> hits = collectSearchHits(response.contentBlocks(), allowedDomains, blockedDomains);
        return hits;
    }

    private static boolean supportsNativeProvider(ProviderId providerId) {
        return providerId == ProviderId.ANTHROPIC || providerId == ProviderId.OPENAI;
    }

    private static String preferredSearchModel(ProviderId providerId, String currentModelId) {
        if (providerId == ProviderId.OPENAI) {
            return "gpt-5.4-mini";
        }
        return currentModelId == null || currentModelId.isBlank() ? null : currentModelId;
    }

    private static ProviderToolDefinition nativeSearchTool(
            ProviderId providerId,
            List<String> allowedDomains,
            List<String> blockedDomains
    ) {
        if (providerId == ProviderId.ANTHROPIC) {
            return ProviderToolDefinition.nativeTool(
                    "web_search",
                    "web_search_20250305",
                    """
                    {
                      "max_uses": 8,
                      "allowed_domains": %s,
                      "blocked_domains": %s
                    }
                    """.formatted(toJsonArray(allowedDomains), toJsonArray(blockedDomains))
            );
        }
        return ProviderToolDefinition.nativeTool(
                "web_search",
                "web_search",
                """
                {
                  "search_context_size": "medium",
                  "filters": {
                    "allowed_domains": %s
                  }
                }
                """.formatted(toJsonArray(allowedDomains))
        );
    }

    private static void emitProviderSearchProgress(
            Consumer<ToolExecutionUpdate> updateConsumer,
            ToolExecutionRequest request,
            ToolModelProgress progress,
            String query
    ) {
        if (progress == null) {
            return;
        }
        String text = switch (progress.type()) {
            case "query_update" -> "Searching: " + (progress.text().isBlank() ? query : progress.text());
            case "search_results_received" -> {
                if (progress.resultCount() > 0) {
                    yield "Found " + progress.resultCount() + " result"
                            + (progress.resultCount() == 1 ? "" : "s")
                            + " for \"" + query + "\"";
                }
                yield progress.text().isBlank() ? "Found search results for \"" + query + "\"" : progress.text();
            }
            default -> progress.text().isBlank() ? "Provider web search in progress." : progress.text();
        };
        emitStatic(updateConsumer, request, "progress", text, query, false);
    }

    private static void emitStatic(
            Consumer<ToolExecutionUpdate> updateConsumer,
            ToolExecutionRequest request,
            String phase,
            String text,
            String command,
            boolean error
    ) {
        if (updateConsumer == null) {
            return;
        }
        updateConsumer.accept(new ToolExecutionUpdate(
                request.toolUseId(),
                request.toolName(),
                phase,
                text,
                request.inputJson(),
                "",
                command == null ? "" : command,
                "",
                "",
                error
        ));
    }

    private static List<SearchHit> collectSearchHits(
            List<com.openclaude.provider.spi.PromptContentBlock> contentBlocks,
            List<String> allowedDomains,
            List<String> blockedDomains
    ) {
        List<SearchHit> hits = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();
        for (com.openclaude.provider.spi.PromptContentBlock block : contentBlocks) {
            if (!(block instanceof WebSearchResultContentBlock resultBlock)) {
                continue;
            }
            for (WebSearchResultContentBlock.SearchHit hit : resultBlock.hits()) {
                if (hit.url().isBlank() || !seenUrls.add(hit.url())) {
                    continue;
                }
                if (!domainAllowed(hit.url(), allowedDomains, blockedDomains)) {
                    continue;
                }
                hits.add(new SearchHit(
                        hit.title().isBlank() ? hit.url() : hit.title(),
                        hit.url()
                ));
                if (hits.size() == MAX_RESULTS) {
                    return List.copyOf(hits);
                }
            }
        }
        return List.copyOf(hits);
    }

    private static String toJsonArray(List<String> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < values.size(); index += 1) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append('"').append(jsonEscape(values.get(index))).append('"');
        }
        builder.append(']');
        return builder.toString();
    }

    private URI buildSearchUri(String query) throws IOException {
        try {
            String base = searchEndpoint.toString();
            String separator = base.contains("?") ? "&" : "?";
            return URI.create(base + separator + "q=" + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid web search endpoint: " + searchEndpoint, exception);
        }
    }

    private static List<SearchHit> parseSearchResults(String html) {
        List<SearchHit> hits = collectMatches(html, SEARCH_RESULT_LINK_PATTERN);
        if (!hits.isEmpty()) {
            return hits;
        }
        return collectMatches(html, FALLBACK_LINK_PATTERN);
    }

    private static List<SearchHit> collectMatches(String html, Pattern pattern) {
        List<SearchHit> hits = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(html == null ? "" : html);
        while (matcher.find()) {
            String rawHref = matcher.group(1);
            String rawTitle = matcher.group(2);
            String resolvedUrl = resolveSearchResultUrl(rawHref);
            if (resolvedUrl.isBlank() || !seenUrls.add(resolvedUrl)) {
                continue;
            }
            String title = stripTags(rawTitle);
            if (title.isBlank()) {
                title = resolvedUrl;
            }
            hits.add(new SearchHit(title, resolvedUrl));
        }
        return hits;
    }

    private static String resolveSearchResultUrl(String rawHref) {
        if (rawHref == null || rawHref.isBlank()) {
            return "";
        }

        String href = htmlDecode(rawHref.trim());
        if (href.startsWith("//")) {
            href = "https:" + href;
        }

        try {
            URI uri = new URI(href);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (host.contains("duckduckgo.com")) {
                String uddg = queryParam(uri, "uddg");
                if (!uddg.isBlank()) {
                    return uddg;
                }
            }
            if (uri.getScheme() == null) {
                return "";
            }
            return uri.toString();
        } catch (URISyntaxException exception) {
            return "";
        }
    }

    private static String queryParam(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return "";
        }
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            String key = separator >= 0 ? pair.substring(0, separator) : pair;
            if (!name.equals(key)) {
                continue;
            }
            String value = separator >= 0 ? pair.substring(separator + 1) : "";
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
        return "";
    }

    private static boolean domainAllowed(String url, List<String> allowedDomains, List<String> blockedDomains) {
        try {
            String host = new URI(url).getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            if (!allowedDomains.isEmpty() && allowedDomains.stream().noneMatch(domain -> domainMatches(normalizedHost, domain))) {
                return false;
            }
            return blockedDomains.stream().noneMatch(domain -> domainMatches(normalizedHost, domain));
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private static boolean domainMatches(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static String formatResult(String query, List<SearchHit> hits, List<String> allowedDomains, List<String> blockedDomains) {
        StringBuilder builder = new StringBuilder()
                .append("Web search results for query: \"")
                .append(query)
                .append("\"\n\n");

        if (hits.isEmpty()) {
            builder.append("No links found.\n\n");
        } else {
            builder.append("Links: ")
                    .append(toLinksJson(hits))
                    .append("\n\n");
        }

        builder.append("\nREMINDER: You MUST include the sources above in your response to the user using markdown hyperlinks.");
        return builder.toString().trim();
    }

    private static String buildDescription() {
        String currentMonthYear = LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH));
        return """
                - Allows Claude to search the web and use the results to inform responses
                - Provides up-to-date information for current events and recent data
                - Returns search result information formatted as search result blocks, including links as markdown hyperlinks
                - Use this tool for accessing information beyond Claude's knowledge cutoff
                - Searches are performed automatically within a single API call

                CRITICAL REQUIREMENT - You MUST follow this:
                  - After answering the user's question, you MUST include a "Sources:" section at the end of your response
                  - In the Sources section, list all relevant URLs from the search results as markdown hyperlinks: [Title](URL)
                  - This is MANDATORY - never skip including sources in your response
                  - Example format:

                    [Your answer here]

                    Sources:
                    - [Source Title 1](https://example.com/1)
                    - [Source Title 2](https://example.com/2)

                Usage notes:
                  - Domain filtering is supported to include or block specific websites
                  - Web search is only available in the US

                IMPORTANT - Use the correct year in search queries:
                  - The current month is %s. You MUST use this year when searching for recent information, documentation, or current events.
                  - Example: If the user asks for "latest React docs", search for "React documentation" with the current year, NOT last year
                """.formatted(currentMonthYear).strip();
    }

    private static String toLinksJson(List<SearchHit> hits) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < hits.size(); index += 1) {
            SearchHit hit = hits.get(index);
            if (index > 0) {
                builder.append(",");
            }
            builder.append("{\"title\":\"")
                    .append(jsonEscape(hit.title()))
                    .append("\",\"url\":\"")
                    .append(jsonEscape(hit.url()))
                    .append("\"}");
        }
        builder.append("]");
        return builder.toString();
    }

    private static String jsonEscape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private static List<String> normalizeDomains(List<String> domains) {
        if (domains == null) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String domain : domains) {
            String value = normalize(domain).toLowerCase(Locale.ROOT);
            if (value.isBlank()) {
                continue;
            }
            normalized.add(value);
        }
        return List.copyOf(normalized);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String stripTags(String value) {
        return htmlDecode((value == null ? "" : value)
                .replaceAll("(?is)<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim());
    }

    private static String htmlDecode(String value) {
        return value
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WebSearchInput(
            String query,
            List<String> allowed_domains,
            List<String> blocked_domains
    ) {
    }

    private record SearchHit(
            String title,
            String url
    ) {
    }
}
