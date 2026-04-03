package com.openclaude.cli.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.openclaude.core.config.OpenClaudePaths;
import com.openclaude.core.config.OpenClaudeStateStore;
import com.openclaude.core.provider.OpenClaudeState;
import com.openclaude.core.tools.ToolPermissionDecision;
import com.openclaude.core.tools.PermissionRuleStringCodec;
import com.openclaude.core.tools.ToolPermissionRequest;
import com.openclaude.core.tools.ToolPermissionRule;
import com.openclaude.core.tools.ToolPermissionSources;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class PermissionRulesStore {
    private static final Path MANAGED_SETTINGS_PATH = Path.of("/etc/openclaude/settings.json");

    private final OpenClaudeStateStore stateStore;
    private final ObjectMapper objectMapper;

    public PermissionRulesStore(OpenClaudeStateStore stateStore) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    public List<ToolPermissionRule> loadRules(Path workspaceRoot) {
        ArrayList<ToolPermissionRule> rules = new ArrayList<>();
        for (String source : ToolPermissionSources.precedence()) {
            rules.addAll(loadRulesForSource(source, workspaceRoot));
        }
        return List.copyOf(rules);
    }

    public List<ToolPermissionRule> loadRulesForSource(String source, Path workspaceRoot) {
        String normalizedSource = ToolPermissionSources.normalize(source);
        if (ToolPermissionSources.SESSION.equals(normalizedSource)) {
            return stateStore.load().settings().permissionRules().stream()
                    .map(rule -> rule.withSource(ToolPermissionSources.SESSION))
                    .sorted(Comparator.comparing(ToolPermissionRule::createdAt).reversed())
                    .toList();
        }

        Path settingsFile = settingsFileForSource(normalizedSource, workspaceRoot);
        if (settingsFile == null || !Files.exists(settingsFile)) {
            return List.of();
        }

        try {
            JsonNode root = objectMapper.readTree(settingsFile.toFile());
            JsonNode permissionsNode = root.path("permissions");
            ArrayList<ToolPermissionRule> rules = new ArrayList<>();
            for (String behavior : List.of("allow", "deny", "ask")) {
                JsonNode rulesNode = permissionsNode.path(behavior);
                if (!rulesNode.isArray()) {
                    continue;
                }
                for (JsonNode ruleNode : rulesNode) {
                    String ruleString = ruleNode.asText("").trim();
                    if (ruleString.isBlank()) {
                        continue;
                    }
                    rules.add(ToolPermissionRule.fromPermissionRuleString(normalizedSource, behavior, ruleString));
                }
            }
            return List.copyOf(rules);
        } catch (IOException exception) {
            return List.of();
        }
    }

    public ToolPermissionDecision findDecision(ToolPermissionRequest request, Path workspaceRoot) {
        ToolPermissionRule match = null;
        for (ToolPermissionRule rule : loadRules(workspaceRoot)) {
            if (!rule.matches(request)) {
                continue;
            }
            match = rule;
        }
        if (match == null) {
            return null;
        }
        return match.toDecision();
    }

    public boolean addRule(String source, ToolPermissionRule rule, Path workspaceRoot) {
        String normalizedSource = ToolPermissionSources.normalize(source);
        if (!ToolPermissionSources.isEditable(normalizedSource)) {
            return false;
        }
        if (ToolPermissionSources.SESSION.equals(normalizedSource)) {
            OpenClaudeState state = stateStore.load();
            ArrayList<ToolPermissionRule> nextRules = new ArrayList<>(state.settings().permissionRules());
            nextRules.removeIf(existing -> sameRule(existing, rule));
            nextRules.add(rule.withSource(ToolPermissionSources.SESSION));
            stateStore.setSettings(state.settings().withPermissionRules(nextRules));
            return true;
        }
        return updateSettingsFile(normalizedSource, workspaceRoot, root -> {
            ObjectNode permissionsNode = ensureObject(root, "permissions");
            ArrayNode behaviorNode = ensureArray(permissionsNode, rule.behavior());
            String ruleString = rule.toRuleString();
            if (!containsRuleString(behaviorNode, ruleString)) {
                behaviorNode.add(ruleString);
            }
        });
    }

    public boolean removeRule(String source, ToolPermissionRule rule, Path workspaceRoot) {
        String normalizedSource = ToolPermissionSources.normalize(source);
        if (!ToolPermissionSources.isEditable(normalizedSource)) {
            return false;
        }
        if (ToolPermissionSources.SESSION.equals(normalizedSource)) {
            OpenClaudeState state = stateStore.load();
            ArrayList<ToolPermissionRule> nextRules = new ArrayList<>(state.settings().permissionRules());
            int before = nextRules.size();
            nextRules.removeIf(existing -> sameRule(existing, rule));
            if (before == nextRules.size()) {
                return false;
            }
            stateStore.setSettings(state.settings().withPermissionRules(nextRules));
            return true;
        }
        return updateSettingsFile(normalizedSource, workspaceRoot, root -> {
            ObjectNode permissionsNode = ensureObject(root, "permissions");
            ArrayNode behaviorNode = ensureArray(permissionsNode, rule.behavior());
            removeRuleString(behaviorNode, rule.toRuleString());
            cleanupPermissionsObject(root, permissionsNode);
        });
    }

    public int clearRules(String source, Path workspaceRoot) {
        String normalizedSource = ToolPermissionSources.normalize(source);
        if (!ToolPermissionSources.isEditable(normalizedSource)) {
            return 0;
        }
        if (ToolPermissionSources.SESSION.equals(normalizedSource)) {
            OpenClaudeState state = stateStore.load();
            int count = state.settings().permissionRules().size();
            stateStore.setSettings(state.settings().withPermissionRules(List.of()));
            return count;
        }

        List<ToolPermissionRule> existing = loadRulesForSource(normalizedSource, workspaceRoot);
        if (existing.isEmpty()) {
            return 0;
        }
        boolean updated = updateSettingsFile(normalizedSource, workspaceRoot, root -> {
            root.remove("permissions");
        });
        return updated ? existing.size() : 0;
    }

    public void persistSessionDecision(ToolPermissionRequest request, ToolPermissionDecision decision) {
        if (decision == null || decision.asks() || decision.passthrough()) {
            return;
        }
        ToolPermissionRule rule = decision.allowed()
                ? ToolPermissionRule.allow(request)
                : ToolPermissionRule.deny(request);
        addRule(ToolPermissionSources.SESSION, rule, null);
    }

    private boolean updateSettingsFile(String source, Path workspaceRoot, java.util.function.Consumer<ObjectNode> mutator) {
        Path settingsFile = settingsFileForSource(source, workspaceRoot);
        if (settingsFile == null) {
            return false;
        }
        try {
            ObjectNode root = Files.exists(settingsFile)
                    ? (ObjectNode) objectMapper.readTree(settingsFile.toFile())
                    : objectMapper.createObjectNode();
            mutator.accept(root);
            Files.createDirectories(settingsFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(settingsFile.toFile(), root);
            return true;
        } catch (IOException | ClassCastException exception) {
            return false;
        }
    }

    private static ObjectNode ensureObject(ObjectNode root, String fieldName) {
        JsonNode existing = root.get(fieldName);
        if (existing instanceof ObjectNode objectNode) {
            return objectNode;
        }
        ObjectNode next = root.objectNode();
        root.set(fieldName, next);
        return next;
    }

    private static ArrayNode ensureArray(ObjectNode root, String fieldName) {
        JsonNode existing = root.get(fieldName);
        if (existing instanceof ArrayNode arrayNode) {
            return arrayNode;
        }
        ArrayNode next = root.arrayNode();
        root.set(fieldName, next);
        return next;
    }

    private static boolean containsRuleString(ArrayNode node, String ruleString) {
        for (JsonNode child : node) {
            if (normalizeRuleString(child.asText("")).equals(normalizeRuleString(ruleString))) {
                return true;
            }
        }
        return false;
    }

    private static void removeRuleString(ArrayNode node, String ruleString) {
        String normalizedTarget = normalizeRuleString(ruleString);
        for (int index = node.size() - 1; index >= 0; index -= 1) {
            if (normalizeRuleString(node.get(index).asText("")).equals(normalizedTarget)) {
                node.remove(index);
            }
        }
    }

    private static String normalizeRuleString(String ruleString) {
        PermissionRuleStringCodec.RuleValue parsed = PermissionRuleStringCodec.parse(ruleString);
        return PermissionRuleStringCodec.format(parsed.toolName(), parsed.ruleContent());
    }

    private static void cleanupPermissionsObject(ObjectNode root, ObjectNode permissionsNode) {
        List<String> emptyFields = new ArrayList<>();
        permissionsNode.fields().forEachRemaining(entry -> {
            if (entry.getValue() instanceof ArrayNode arrayNode && arrayNode.isEmpty()) {
                emptyFields.add(entry.getKey());
            }
        });
        emptyFields.forEach(permissionsNode::remove);
        if (permissionsNode.isEmpty()) {
            root.remove("permissions");
        }
    }

    private static boolean sameRule(ToolPermissionRule left, ToolPermissionRule right) {
        return ToolPermissionSources.normalize(left.source()).equals(ToolPermissionSources.normalize(right.source()))
                && left.behavior().equalsIgnoreCase(right.behavior())
                && normalizeRuleString(left.toRuleString()).equals(normalizeRuleString(right.toRuleString()))
                && left.signature().equals(right.signature());
    }

    private static Path settingsFileForSource(String source, Path workspaceRoot) {
        return switch (ToolPermissionSources.normalize(source)) {
            case ToolPermissionSources.USER_SETTINGS -> OpenClaudePaths.configHome().resolve("settings.json");
            case ToolPermissionSources.PROJECT_SETTINGS -> workspaceRoot == null ? null : workspaceRoot.resolve(".openclaude").resolve("settings.json");
            case ToolPermissionSources.LOCAL_SETTINGS -> workspaceRoot == null ? null : workspaceRoot.resolve(".openclaude").resolve("settings.local.json");
            case ToolPermissionSources.POLICY_SETTINGS -> MANAGED_SETTINGS_PATH;
            default -> null;
        };
    }
}
