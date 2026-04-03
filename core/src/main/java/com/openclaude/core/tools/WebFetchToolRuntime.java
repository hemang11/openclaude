package com.openclaude.core.tools;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaude.provider.spi.AuthMethod;
import com.openclaude.provider.spi.ProviderId;
import com.openclaude.provider.spi.ProviderToolDefinition;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

public final class WebFetchToolRuntime extends AbstractSingleToolRuntime {
    private static final URI ANTHROPIC_DOMAIN_INFO_URI = URI.create("https://api.anthropic.com/api/web/domain_info");
    private static final String WEB_FETCH_DESCRIPTION = """
            IMPORTANT: WebFetch WILL FAIL for authenticated or private URLs. Before using this tool, check if the URL points to an authenticated service (e.g. Google Docs, Confluence, Jira, GitHub). If so, look for a specialized MCP tool that provides authenticated access.
            - Fetches content from a specified URL and processes it using an AI model
            - Takes a URL and a prompt as input
            - Fetches the URL content, converts HTML to markdown
            - Processes the content with the prompt using a small, fast model
            - Returns the model's response about the content
            - Use this tool when you need to retrieve and analyze web content

            Usage notes:
              - IMPORTANT: If an MCP-provided web fetch tool is available, prefer using that tool instead of this one, as it may have fewer restrictions.
              - The URL must be a fully-formed valid URL
              - HTTP URLs will be automatically upgraded to HTTPS
              - The prompt should describe what information you want to extract from the page
              - This tool is read-only and does not modify any files
              - Results may be summarized if the content is very large
              - Includes a self-cleaning 15-minute cache for faster responses when repeatedly accessing the same URL
              - When a URL redirects to a different host, the tool will inform you and provide the redirect URL in a special format. You should then make a new WebFetch request with the redirect URL to fetch the content.
              - For GitHub URLs, prefer using the gh CLI via Bash instead (e.g., gh pr view, gh issue view, gh api).
            """;
    private static final ProviderToolDefinition DEFINITION = new ProviderToolDefinition(
            "WebFetch",
            WEB_FETCH_DESCRIPTION,
            """
            {
              "type": "object",
              "properties": {
                "url": {
                  "type": "string",
                  "description": "The URL to fetch content from."
                },
                "prompt": {
                  "type": "string",
                  "description": "What information to extract from the fetched page."
                }
              },
              "required": ["url", "prompt"],
              "additionalProperties": false
            }
            """
    );
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DOMAIN_CHECK_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CACHE_TTL = Duration.ofMinutes(15);
    private static final Duration DOMAIN_CHECK_CACHE_TTL = Duration.ofMinutes(5);
    private static final int MAX_CONTENT_CHARS = 100_000;
    private static final int MAX_MARKDOWN_LENGTH = 100_000;
    private static final int MAX_URL_LENGTH = 2_000;
    private static final int MAX_REDIRECTS = 10;
    private static final int MAX_CACHE_ENTRIES = 64;
    private static final int MAX_DOMAIN_CHECK_CACHE_ENTRIES = 128;
    private static final Map<String, CachedFetchResult> FETCH_CACHE = new LinkedHashMap<>(MAX_CACHE_ENTRIES, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedFetchResult> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };
    private static final Map<String, Long> ALLOWED_DOMAIN_CACHE =
            new LinkedHashMap<>(MAX_DOMAIN_CHECK_CACHE_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > MAX_DOMAIN_CHECK_CACHE_ENTRIES;
                }
            };

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final URI domainInfoEndpoint;

    public WebFetchToolRuntime() {
        this(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    WebFetchToolRuntime(HttpClient httpClient) {
        this(httpClient, ANTHROPIC_DOMAIN_INFO_URI);
    }

    WebFetchToolRuntime(HttpClient httpClient, URI domainInfoEndpoint) {
        super(DEFINITION);
        this.httpClient = httpClient;
        this.domainInfoEndpoint = domainInfoEndpoint == null ? ANTHROPIC_DOMAIN_INFO_URI : domainInfoEndpoint;
    }

    @Override
    protected boolean isConcurrencySafeSingle(String inputJson) {
        try {
            WebFetchInput input = objectMapper.readValue(inputJson, WebFetchInput.class);
            return !normalize(input.url()).isBlank() && !normalize(input.prompt()).isBlank();
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
        WebFetchInput input;
        try {
            input = objectMapper.readValue(request.inputJson(), WebFetchInput.class);
        } catch (IOException exception) {
            emit(updateConsumer, request, "failed", "Invalid WebFetch input: " + exception.getMessage(), "", true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "Invalid WebFetch input: " + exception.getMessage(), true);
        }

        String rawUrl = normalize(input.url());
        String prompt = normalize(input.prompt());
        if (rawUrl.isBlank() || prompt.isBlank()) {
            emit(updateConsumer, request, "failed", "WebFetch requires both url and prompt.", "", true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), "WebFetch requires both url and prompt.", true);
        }

        URI uri;
        try {
            uri = normalizeUri(rawUrl);
        } catch (IllegalArgumentException exception) {
            emit(updateConsumer, request, "failed", exception.getMessage(), rawUrl, true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), exception.getMessage(), true);
        }

        emit(updateConsumer, request, "started", "Queued web fetch.", uri.toString(), false);
        boolean preapprovedHost = WebFetchPreapprovedHosts.isPreapprovedHost(
                uri.getHost(),
                uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath()
        );
        if (!preapprovedHost) {
            ToolPermissionDecision permission = requestPermission(
                    request,
                    permissionGateway,
                    updateConsumer,
                    uri.getHost(),
                    "Allow fetching content from " + uri.getHost() + "?",
                    "web_fetch",
                    request.inputJson()
            );
            if (!permission.allowed()) {
                String text = "Permission denied: " + permission.reason();
                emit(updateConsumer, request, "failed", text, uri.toString(), true);
                return new ToolExecutionResult(request.toolUseId(), request.toolName(), text, true);
            }
        }

        emit(updateConsumer, request, "progress", "Fetching " + uri, uri.toString(), false);
        try {
            DomainPreflightResult preflightResult = checkDomainPreflight(request.modelInvoker(), uri);
            if (preflightResult == DomainPreflightResult.BLOCKED) {
                String text = "Claude Code is unable to fetch from " + safeHost(uri);
                emit(updateConsumer, request, "failed", text, uri.toString(), true);
                return new ToolExecutionResult(request.toolUseId(), request.toolName(), text, true);
            }
            if (preflightResult == DomainPreflightResult.CHECK_FAILED) {
                String text = "Unable to verify if domain " + safeHost(uri)
                        + " is safe to fetch. This may be due to network restrictions or enterprise security policies blocking claude.ai.";
                emit(updateConsumer, request, "failed", text, uri.toString(), true);
                return new ToolExecutionResult(request.toolUseId(), request.toolName(), text, true);
            }
            FetchResult fetchResult = fetch(uri, 0);
            String text = formatResult(fetchResult, prompt);
            if (preapprovedHost
                    && fetchResult.contentType().toLowerCase(Locale.ROOT).contains("text/markdown")
                    && fetchResult.content().length() < MAX_MARKDOWN_LENGTH) {
                text = fetchResult.content();
            } else if ((request.modelInvoker() == null || request.modelInvoker().providerId() == null)
                    && fetchResult.contentType().toLowerCase(Locale.ROOT).contains("text/markdown")
                    && fetchResult.content().length() < MAX_MARKDOWN_LENGTH) {
                text = fetchResult.content();
            } else if (request.modelInvoker() != null
                    && shouldApplyPrompt(fetchResult)
                    && shouldApplySecondaryPrompt(request.modelInvoker())) {
                emit(updateConsumer, request, "progress", "Applying prompt to fetched content.", uri.toString(), false);
                text = applyPromptSafely(fetchResult, prompt, request.modelInvoker(), preapprovedHost);
            }
            boolean isError = fetchResult.statusCode() >= 400;
            emit(updateConsumer, request, isError ? "failed" : "completed", text, uri.toString(), isError);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), text, isError);
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String text = "WebFetch failed: " + exception.getMessage();
            emit(updateConsumer, request, "failed", text, uri.toString(), true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), text, true);
        } catch (RuntimeException exception) {
            String text = "WebFetch failed: " + describeFailure(exception);
            emit(updateConsumer, request, "failed", text, uri.toString(), true);
            return new ToolExecutionResult(request.toolUseId(), request.toolName(), text, true);
        }
    }

    private FetchResult fetch(URI uri, int depth) throws IOException, InterruptedException {
        FetchResult cached = cachedFetch(uri);
        if (cached != null) {
            return cached;
        }

        if (depth > MAX_REDIRECTS) {
            throw new IOException("Too many redirects (exceeded " + MAX_REDIRECTS + ")");
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "openclaude/0.1")
                .header("Accept", "text/markdown, text/html, */*")
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        int statusCode = response.statusCode();
        if (statusCode >= 300 && statusCode < 400) {
            Optional<String> location = response.headers().firstValue("location");
            if (location.isPresent()) {
                URI redirectUri = uri.resolve(location.get());
                if (!isPermittedRedirect(uri, redirectUri)) {
                    return new FetchResult(
                            uri,
                            statusCode,
                            humanizeRedirectStatus(statusCode),
                            """
                            REDIRECT DETECTED: The URL redirects to a different host.

                            Original URL: %s
                            Redirect URL: %s
                            Status: %s %s

                            To complete your request, make a new WebFetch request with the redirected URL and the same prompt.
                            """.formatted(uri, redirectUri, statusCode, humanizeRedirectStatus(statusCode)).strip(),
                            "text/plain"
                    );
                }
                return fetch(redirectUri, depth + 1);
            }
        }

        String contentType = response.headers().firstValue("content-type").orElse("text/plain");
        String body = response.body() == null ? "" : response.body();
        FetchResult fetchResult = new FetchResult(uri, statusCode, "", extractReadableContent(body, contentType, uri), contentType);
        cacheFetch(fetchResult);
        return fetchResult;
    }

    private static String formatResult(FetchResult fetchResult, String prompt) {
        String content = truncate(fetchResult.content());
        return """
                URL: %s
                HTTP status: %s
                Prompt: %s

                Fetched content:
                %s
                """.formatted(
                fetchResult.uri(),
                fetchResult.statusCode(),
                prompt,
                content.isBlank() ? "(no readable content extracted)" : content
        ).stripTrailing();
    }

    private static boolean shouldApplyPrompt(FetchResult fetchResult) {
        return fetchResult.statusCode() < 300 && !fetchResult.content().isBlank();
    }

    private static String applyPrompt(
            FetchResult fetchResult,
            String prompt,
            ToolModelInvoker modelInvoker,
            boolean isPreapprovedDomain
    ) {
        String fetchedContent = fetchResult.content();
        if (fetchedContent.length() > MAX_MARKDOWN_LENGTH) {
            fetchedContent = fetchedContent.substring(0, MAX_MARKDOWN_LENGTH)
                    + "\n\n[Content truncated due to length...]";
        }
        ToolModelResponse response = modelInvoker.invoke(new ToolModelRequest(
                makeSecondaryModelPrompt(fetchedContent, prompt, isPreapprovedDomain),
                "",
                preferredSecondaryModel(modelInvoker),
                List.of(),
                null,
                false
        ));
        String text = response.text();
        return text == null || text.isBlank() ? formatResult(fetchResult, prompt) : text.trim();
    }

    private static String applyPromptSafely(
            FetchResult fetchResult,
            String prompt,
            ToolModelInvoker modelInvoker,
            boolean isPreapprovedDomain
    ) {
        try {
            return applyPrompt(fetchResult, prompt, modelInvoker, isPreapprovedDomain);
        } catch (RuntimeException exception) {
            return formatResult(fetchResult, prompt);
        }
    }

    private static String preferredSecondaryModel(ToolModelInvoker modelInvoker) {
        if (modelInvoker == null) {
            return null;
        }
        ProviderId providerId = modelInvoker.providerId();
        AuthMethod authMethod = modelInvoker.authMethod();
        if (providerId == ProviderId.ANTHROPIC) {
            return "anthropic/haiku";
        }
        if (providerId == ProviderId.OPENAI) {
            if (authMethod == AuthMethod.BROWSER_SSO) {
                return null;
            }
            return "gpt-5.4-mini";
        }
        return null;
    }

    private static boolean shouldApplySecondaryPrompt(ToolModelInvoker modelInvoker) {
        if (modelInvoker == null) {
            return false;
        }
        return !(modelInvoker.providerId() == ProviderId.OPENAI && modelInvoker.authMethod() == AuthMethod.BROWSER_SSO);
    }

    private static String makeSecondaryModelPrompt(String fetchedContent, String prompt, boolean isPreapprovedDomain) {
        String guidelines = isPreapprovedDomain
                ? "Provide a concise response based on the content above. Include relevant details, code examples, and documentation excerpts as needed."
                : """
                Provide a concise response based only on the content above. In your response:
                 - Enforce a strict 125-character maximum for quotes from any source document. Open Source Software is ok as long as we respect the license.
                 - Use quotation marks for exact language from articles; any language outside of the quotation should never be word-for-word the same.
                 - You are not a lawyer and never comment on the legality of your own prompts and responses.
                 - Never produce or reproduce exact song lyrics.
                """.strip();

        return """
                Web page content:
                ---
                %s
                ---

                %s

                %s
                """.formatted(fetchedContent, prompt, guidelines).strip();
    }

    private static synchronized FetchResult cachedFetch(URI uri) {
        CachedFetchResult cached = FETCH_CACHE.get(uri.toString());
        if (cached == null) {
            return null;
        }
        if (cached.expiresAtMillis() < System.currentTimeMillis()) {
            FETCH_CACHE.remove(uri.toString());
            return null;
        }
        return cached.fetchResult();
    }

    private static synchronized void cacheFetch(FetchResult fetchResult) {
        FETCH_CACHE.put(
                fetchResult.uri().toString(),
                new CachedFetchResult(fetchResult, System.currentTimeMillis() + CACHE_TTL.toMillis())
        );
    }

    private static URI normalizeUri(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank() || rawUrl.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("Invalid URL: " + rawUrl);
        }
        try {
            URI uri = new URI(rawUrl);
            if (uri.getScheme() == null || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("Invalid URL: " + rawUrl);
            }
            if (!isLocalHost(uri.getHost()) && uri.getHost().split("\\.").length < 2) {
                throw new IllegalArgumentException("Invalid URL: " + rawUrl);
            }
            if ("http".equalsIgnoreCase(uri.getScheme()) && shouldUpgradeToHttps(uri.getHost())) {
                return new URI(
                        "https",
                        uri.getUserInfo(),
                        uri.getHost(),
                        uri.getPort(),
                        uri.getPath(),
                        uri.getQuery(),
                        uri.getFragment()
                );
            }
            return uri;
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid URL: " + rawUrl, exception);
        }
    }

    private static boolean shouldUpgradeToHttps(String host) {
        String normalized = host == null ? "" : host.toLowerCase(Locale.ROOT);
        return !normalized.equals("localhost") && !normalized.equals("127.0.0.1");
    }

    private static String safeHost(URI uri) {
        return uri.getHost() == null ? "" : uri.getHost();
    }

    private DomainPreflightResult checkDomainPreflight(ToolModelInvoker modelInvoker, URI uri) throws IOException, InterruptedException {
        if (modelInvoker == null || modelInvoker.providerId() != ProviderId.ANTHROPIC) {
            return DomainPreflightResult.ALLOWED;
        }
        String host = safeHost(uri);
        if (host.isBlank() || isLocalHost(host) || WebFetchPreapprovedHosts.isPreapprovedHost(host, uri.getPath() == null ? "/" : uri.getPath())) {
            return DomainPreflightResult.ALLOWED;
        }
        if (isAllowedDomainCached(host)) {
            return DomainPreflightResult.ALLOWED;
        }

        URI requestUri = URI.create(domainInfoEndpoint + "?domain=" + URLEncoder.encode(host, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(requestUri)
                .GET()
                .timeout(DOMAIN_CHECK_TIMEOUT)
                .header("User-Agent", "openclaude/0.1")
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return DomainPreflightResult.CHECK_FAILED;
            }
            DomainInfoResponse domainInfo = objectMapper.readValue(response.body(), DomainInfoResponse.class);
            if (Boolean.TRUE.equals(domainInfo.can_fetch())) {
                cacheAllowedDomain(host);
                return DomainPreflightResult.ALLOWED;
            }
            return DomainPreflightResult.BLOCKED;
        } catch (IOException exception) {
            return DomainPreflightResult.CHECK_FAILED;
        }
    }

    private static boolean isLocalHost(String host) {
        String normalized = host == null ? "" : host.toLowerCase(Locale.ROOT);
        return normalized.equals("localhost")
                || normalized.equals("127.0.0.1")
                || normalized.equals("::1");
    }

    private static String extractReadableContent(String body, String contentType, URI uri) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String normalizedContentType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (normalizedContentType.contains("markdown")) {
            return body.trim();
        }
        if (normalizedContentType.contains("html")) {
            return htmlToMarkdown(body, uri == null ? "" : uri.toString());
        }
        return body.trim();
    }

    private static String htmlToMarkdown(String body, String baseUri) {
        Document document = Jsoup.parse(body, baseUri == null ? "" : baseUri);
        document.select("script,style,noscript,template").remove();
        Element root = document.body();
        if (root == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (Node child : root.childNodes()) {
            renderMarkdown(child, builder, 0);
        }
        return normalizeMarkdownOutput(builder.toString());
    }

    private static void renderMarkdown(Node node, StringBuilder builder, int listDepth) {
        if (node instanceof TextNode textNode) {
            builder.append(textNode.getWholeText().replace('\u00A0', ' '));
            return;
        }
        if (!(node instanceof Element element)) {
            return;
        }

        String tag = element.tagName().toLowerCase(Locale.ROOT);
        switch (tag) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                int level = Math.max(1, Math.min(6, Character.digit(tag.charAt(1), 10)));
                builder.append("#".repeat(level)).append(' ');
                renderChildren(element, builder, listDepth);
                builder.append("\n\n");
            }
            case "p", "div", "section", "article" -> {
                renderChildren(element, builder, listDepth);
                builder.append("\n\n");
            }
            case "main" -> {
                renderChildren(element, builder, listDepth);
                builder.append("\n\n");
            }
            case "br" -> builder.append('\n');
            case "hr" -> builder.append("---\n\n");
            case "ul" -> {
                for (Element li : element.children()) {
                    if (!"li".equalsIgnoreCase(li.tagName())) {
                        continue;
                    }
                    builder.append("  ".repeat(Math.max(0, listDepth))).append("- ");
                    renderChildren(li, builder, listDepth + 1);
                    builder.append('\n');
                }
                builder.append('\n');
            }
            case "ol" -> {
                int index = 1;
                for (Element li : element.children()) {
                    if (!"li".equalsIgnoreCase(li.tagName())) {
                        continue;
                    }
                    builder.append("  ".repeat(Math.max(0, listDepth))).append(index).append(". ");
                    renderChildren(li, builder, listDepth + 1);
                    builder.append('\n');
                    index += 1;
                }
                builder.append('\n');
            }
            case "pre" -> {
                builder.append("```\n").append(element.wholeText().trim()).append("\n```\n\n");
            }
            case "code" -> {
                builder.append('`').append(element.text()).append('`');
            }
            case "a" -> {
                String href = element.absUrl("href");
                if (href.isBlank()) {
                    href = element.attr("href");
                }
                String text = element.text().isBlank() ? href : element.text();
                if (href == null || href.isBlank()) {
                    builder.append(text);
                } else {
                    builder.append('[').append(text).append("](").append(href).append(')');
                }
            }
            case "img" -> {
                String src = element.absUrl("src");
                if (src.isBlank()) {
                    src = element.attr("src");
                }
                if (!src.isBlank()) {
                    String alt = element.attr("alt");
                    builder.append("![").append(alt == null ? "" : alt).append("](").append(src).append(')');
                }
            }
            case "strong", "b" -> {
                builder.append("**");
                renderChildren(element, builder, listDepth);
                builder.append("**");
            }
            case "em", "i" -> {
                builder.append('_');
                renderChildren(element, builder, listDepth);
                builder.append('_');
            }
            case "blockquote" -> {
                String text = element.text().trim();
                if (!text.isBlank()) {
                    for (String line : text.split("\\R")) {
                        builder.append("> ").append(line).append('\n');
                    }
                    builder.append('\n');
                }
            }
            case "table" -> renderTable(element, builder);
            case "li" -> renderChildren(element, builder, listDepth);
            default -> renderChildren(element, builder, listDepth);
        }
    }

    private static void renderTable(Element table, StringBuilder builder) {
        List<List<String>> rows = new java.util.ArrayList<>();
        for (Element row : table.select("tr")) {
            List<String> cells = new java.util.ArrayList<>();
            for (Element cell : row.select("> th, > td")) {
                cells.add(normalizeInlineText(cell.text()));
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        if (rows.isEmpty()) {
            return;
        }

        int columnCount = rows.stream().mapToInt(List::size).max().orElse(0);
        if (columnCount == 0) {
            return;
        }

        List<String> header = padRow(rows.getFirst(), columnCount);
        appendTableRow(builder, header);
        appendTableSeparator(builder, columnCount);
        for (int index = 1; index < rows.size(); index += 1) {
            appendTableRow(builder, padRow(rows.get(index), columnCount));
        }
        builder.append('\n');
    }

    private static List<String> padRow(List<String> row, int columnCount) {
        List<String> padded = new java.util.ArrayList<>(row);
        while (padded.size() < columnCount) {
            padded.add("");
        }
        return padded;
    }

    private static void appendTableRow(StringBuilder builder, List<String> cells) {
        builder.append('|');
        for (String cell : cells) {
            builder.append(' ').append(cell == null ? "" : cell).append(" |");
        }
        builder.append('\n');
    }

    private static void appendTableSeparator(StringBuilder builder, int columnCount) {
        builder.append('|');
        for (int index = 0; index < columnCount; index += 1) {
            builder.append(" --- |");
        }
        builder.append('\n');
    }

    private static String normalizeInlineText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private static void renderChildren(Element element, StringBuilder builder, int listDepth) {
        for (Node child : element.childNodes()) {
            renderMarkdown(child, builder, listDepth);
        }
    }

    private static String normalizeMarkdownOutput(String markdown) {
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder builder = new StringBuilder();
        boolean insideCodeFence = false;
        int blankLineCount = 0;
        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.replace('\u00A0', ' ');
            String normalized = insideCodeFence ? line.replaceAll("\\s+$", "") : line.stripTrailing();
            if (normalized.startsWith("```")) {
                insideCodeFence = !insideCodeFence;
            }
            if (!insideCodeFence && normalized.isBlank()) {
                blankLineCount += 1;
                if (blankLineCount > 2) {
                    continue;
                }
                builder.append('\n');
                continue;
            }
            blankLineCount = 0;
            builder.append(normalized).append('\n');
        }
        return builder.toString().trim();
    }

    private static boolean isPermittedRedirect(URI originalUri, URI redirectUri) {
        if (originalUri == null || redirectUri == null) {
            return false;
        }
        if (!normalizeScheme(originalUri).equalsIgnoreCase(normalizeScheme(redirectUri))) {
            return false;
        }
        if (effectivePort(originalUri) != effectivePort(redirectUri)) {
            return false;
        }
        if ((redirectUri.getUserInfo() != null && !redirectUri.getUserInfo().isBlank())
                || (originalUri.getUserInfo() != null && !originalUri.getUserInfo().isBlank())) {
            return false;
        }
        return stripWww(safeHost(originalUri)).equalsIgnoreCase(stripWww(safeHost(redirectUri)));
    }

    private static String normalizeScheme(URI uri) {
        return uri == null || uri.getScheme() == null ? "" : uri.getScheme();
    }

    private static int effectivePort(URI uri) {
        if (uri == null) {
            return -1;
        }
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : "http".equalsIgnoreCase(uri.getScheme()) ? 80 : -1;
    }

    private static String stripWww(String host) {
        String normalized = host == null ? "" : host.toLowerCase(Locale.ROOT);
        return normalized.startsWith("www.") ? normalized.substring(4) : normalized;
    }

    private static String humanizeRedirectStatus(int statusCode) {
        return switch (statusCode) {
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 307 -> "Temporary Redirect";
            case 308 -> "Permanent Redirect";
            default -> "Redirect";
        };
    }

    private static synchronized boolean isAllowedDomainCached(String host) {
        Long expiresAt = ALLOWED_DOMAIN_CACHE.get(host);
        if (expiresAt == null) {
            return false;
        }
        if (expiresAt < System.currentTimeMillis()) {
            ALLOWED_DOMAIN_CACHE.remove(host);
            return false;
        }
        return true;
    }

    private static synchronized void cacheAllowedDomain(String host) {
        if (host == null || host.isBlank()) {
            return;
        }
        ALLOWED_DOMAIN_CACHE.put(host, System.currentTimeMillis() + DOMAIN_CHECK_CACHE_TTL.toMillis());
    }

    private static String truncate(String value) {
        if (value.length() <= MAX_CONTENT_CHARS) {
            return value;
        }
        return value.substring(0, MAX_CONTENT_CHARS) + System.lineSeparator() + "... truncated ...";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String describeFailure(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        Throwable current = throwable;
        while (current.getCause() != null
                && current.getMessage() != null
                && current.getMessage().isBlank()) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return current.getClass().getSimpleName();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WebFetchInput(
            String url,
            String prompt
    ) {
    }

    private record FetchResult(
            URI uri,
            int statusCode,
            String statusText,
            String content,
            String contentType
    ) {
    }

    private record CachedFetchResult(
            FetchResult fetchResult,
            long expiresAtMillis
    ) {
    }

    private enum DomainPreflightResult {
        ALLOWED,
        BLOCKED,
        CHECK_FAILED
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DomainInfoResponse(
            Boolean can_fetch
    ) {
    }
}
