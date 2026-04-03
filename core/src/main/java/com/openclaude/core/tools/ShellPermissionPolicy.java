package com.openclaude.core.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ShellPermissionPolicy {
    private static final Set<String> READ_ONLY_COMMANDS = Set.of(
            "cd", "pwd", "ls", "find", "rg", "grep", "cat", "head", "tail", "wc", "stat",
            "tree", "git", "sort", "uniq", "cut", "tr", "sed", "basename", "dirname",
            "readlink", "realpath", "fd", "du", "echo", "jq", "history", "alias",
            "docker", "gh", "diff", "test", "["
    );
    private static final Set<String> READ_ONLY_GIT_SUBCOMMANDS = Set.of(
            "status", "diff", "log", "show", "branch", "rev-parse", "ls-files", "grep",
            "blame", "config", "remote", "ls-remote"
    );
    private static final Set<String> READ_ONLY_DOCKER_SUBCOMMANDS = Set.of(
            "logs", "inspect", "ps", "images"
    );

    public static boolean isReadOnlyCommand(String command) {
        return new ShellPermissionPolicy().evaluate(command).allowsExecutionWithoutPrompt();
    }

    PermissionDecision evaluate(String command) {
        if (command == null || command.isBlank()) {
            return PermissionDecision.deny("Command must not be blank.");
        }

        String normalized = command.strip();
        if (normalized.contains("$(") || normalized.contains("`")) {
            return PermissionDecision.ask("Shell command uses command substitution and requires approval.");
        }
        RedirectionNormalization normalization = stripSafeRedirections(normalized);
        if (normalization.error() != null) {
            if (containsUnescapedCharacterOutsideQuotes(normalized, '>') || containsUnescapedCharacterOutsideQuotes(normalized, '<')) {
                return PermissionDecision.ask("Shell command uses redirection and requires approval.");
            }
            return PermissionDecision.deny(normalization.error());
        }

        ParseResult parseResult = splitCommandSegments(normalization.command());
        if (parseResult.error() != null) {
            return PermissionDecision.deny(parseResult.error());
        }

        String previousCdTarget = "";
        for (String segment : parseResult.segments()) {
            List<String> tokens = shellTokens(segment);
            String commandName = tokens.isEmpty() ? "" : tokens.getFirst();
            if (commandName.isEmpty()) {
                return PermissionDecision.deny("Failed to parse the shell command.");
            }

            String lowerCommand = commandName.toLowerCase(Locale.ROOT);
            if (!READ_ONLY_COMMANDS.contains(lowerCommand)) {
                return PermissionDecision.ask("Shell command requires approval: " + lowerCommand);
            }

            if ("cd".equals(lowerCommand) && !isAllowedCdCommand(segment)) {
                return PermissionDecision.ask("Shell command requires approval: cd");
            }

            if ("git".equals(lowerCommand)) {
                if (isExternalCdTarget(previousCdTarget)) {
                    return PermissionDecision.ask("Shell command requires approval: cd && git");
                }
                String gitReason = validateGitTokens(tokens);
                if (gitReason != null) {
                    return PermissionDecision.ask(gitReason);
                }
            }

            if ("docker".equals(lowerCommand)) {
                String dockerReason = validateDockerTokens(tokens);
                if (dockerReason != null) {
                    return PermissionDecision.ask(dockerReason);
                }
            }

            if ("gh".equals(lowerCommand)) {
                String ghReason = validateGhTokens(tokens);
                if (ghReason != null) {
                    return PermissionDecision.ask(ghReason);
                }
            }

            if ("sed".equals(lowerCommand)) {
                String sedReason = validateSedTokens(tokens);
                if (sedReason != null) {
                    return PermissionDecision.ask(sedReason);
                }
            }

            String loweredSegment = segment.toLowerCase(Locale.ROOT);
            if (loweredSegment.contains(" -exec ") || loweredSegment.contains(" -delete") || loweredSegment.contains(" -ok ")) {
                return PermissionDecision.ask("Shell command requires approval: find -exec/-delete");
            }
            if (loweredSegment.matches(".*\\bsed\\s+-i(?:\\s|$).*")) {
                return PermissionDecision.ask("Shell command requires approval: sed -i");
            }

            previousCdTarget = "cd".equals(lowerCommand) && tokens.size() >= 2 ? tokens.get(1) : "";
        }

        return PermissionDecision.allow("allowed");
    }

    private static ParseResult splitCommandSegments(String command) {
        ArrayList<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean escaped = false;

        for (int index = 0; index < command.length(); index += 1) {
            char character = command.charAt(index);
            if (escaped) {
                current.append(character);
                escaped = false;
                continue;
            }
            if (character == '\\') {
                current.append(character);
                escaped = true;
                continue;
            }
            if (character == '\'' && !doubleQuoted) {
                singleQuoted = !singleQuoted;
                current.append(character);
                continue;
            }
            if (character == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
                current.append(character);
                continue;
            }
            if (!singleQuoted && !doubleQuoted) {
                if (character == '\r' || character == '\n' || character == ';') {
                    if (!flushSegment(segments, current)) {
                        return new ParseResult(List.of(), "Failed to parse the shell command.");
                    }
                    if (character == '\r' && index + 1 < command.length() && command.charAt(index + 1) == '\n') {
                        index += 1;
                    }
                    continue;
                }
                if (character == '&') {
                    if (index + 1 < command.length() && command.charAt(index + 1) == '&') {
                        if (!flushSegment(segments, current)) {
                            return new ParseResult(List.of(), "Failed to parse the shell command.");
                        }
                        index += 1;
                        continue;
                    }
                    return new ParseResult(List.of(), "Shell redirection and background operators are not allowed.");
                }
                if (character == '|') {
                    if (index + 1 < command.length() && command.charAt(index + 1) == '|') {
                        if (!flushSegment(segments, current)) {
                            return new ParseResult(List.of(), "Failed to parse the shell command.");
                        }
                        index += 1;
                        continue;
                    }
                    if (!flushSegment(segments, current)) {
                        return new ParseResult(List.of(), "Failed to parse the shell command.");
                    }
                    continue;
                }
            }
            current.append(character);
        }

        if (!flushSegment(segments, current)) {
            return new ParseResult(List.of(), "Failed to parse the shell command.");
        }

        return new ParseResult(List.copyOf(segments), null);
    }

    private static String firstToken(String segment) {
        List<String> tokens = shellTokens(segment);
        return tokens.isEmpty() ? "" : tokens.getFirst();
    }

    private static String secondToken(String segment) {
        List<String> tokens = shellTokens(segment);
        return tokens.size() < 2 ? "" : tokens.get(1);
    }

    private static String validateGitTokens(List<String> tokens) {
        String subcommand = tokens.size() < 2 ? "" : tokens.get(1).toLowerCase(Locale.ROOT);
        if (!READ_ONLY_GIT_SUBCOMMANDS.contains(subcommand)) {
            return "Shell command requires approval: git " + subcommand;
        }
        if ("branch".equals(subcommand)) {
            for (int index = 2; index < tokens.size(); index += 1) {
                String token = tokens.get(index);
                if (Set.of("-d", "-D", "-m", "-M", "-c", "-C", "--delete", "--move", "--copy", "-u", "--set-upstream-to").contains(token)) {
                    return "Shell command requires approval: git branch";
                }
            }
        }
        if ("config".equals(subcommand)) {
            for (int index = 2; index < tokens.size(); index += 1) {
                String token = tokens.get(index);
                if (token.startsWith("--get")) {
                    return null;
                }
            }
            return "Shell command requires approval: git config";
        }
        return null;
    }

    private static String validateDockerTokens(List<String> tokens) {
        String subcommand = tokens.size() < 2 ? "" : tokens.get(1).toLowerCase(Locale.ROOT);
        return READ_ONLY_DOCKER_SUBCOMMANDS.contains(subcommand)
                ? null
                : "Shell command requires approval: docker " + subcommand;
    }

    private static String validateGhTokens(List<String> tokens) {
        if (tokens.size() < 3) {
            return "Shell command requires approval: gh";
        }
        String group = tokens.get(1).toLowerCase(Locale.ROOT);
        String subcommand = tokens.get(2).toLowerCase(Locale.ROOT);
        if ("pr".equals(group) && Set.of("view", "list", "diff").contains(subcommand)) {
            return null;
        }
        return "Shell command requires approval: gh " + group + " " + subcommand;
    }

    private static String validateSedTokens(List<String> tokens) {
        String script = firstNonOptionSedToken(tokens);
        if (script == null || script.isBlank()) {
            return null;
        }
        String normalized = script.toLowerCase(Locale.ROOT);
        if (
                normalized.startsWith("w ")
                        || normalized.startsWith("r ")
                        || normalized.startsWith("e ")
                        || normalized.contains(";w ")
                        || normalized.contains(";r ")
                        || normalized.contains(";e ")
                        || normalized.contains(" w ")
                        || normalized.contains(" r ")
                        || normalized.contains(" e ")
        ) {
            return "Shell command requires approval: sed script";
        }
        return null;
    }

    private static String firstNonOptionSedToken(List<String> tokens) {
        boolean scriptExpected = false;
        for (int index = 1; index < tokens.size(); index += 1) {
            String token = tokens.get(index);
            if (scriptExpected) {
                return token;
            }
            if ("-e".equals(token) || "-f".equals(token)) {
                scriptExpected = true;
                continue;
            }
            if (token.startsWith("-")) {
                continue;
            }
            return token;
        }
        return null;
    }

    private static boolean isAllowedCdCommand(String segment) {
        List<String> tokens = shellTokens(segment);
        if (tokens.isEmpty() || !"cd".equals(tokens.getFirst().toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (tokens.size() == 1) {
            return true;
        }
        if (tokens.size() > 2) {
            return false;
        }
        return !tokens.get(1).startsWith("-");
    }

    private static boolean isExternalCdTarget(String target) {
        if (target == null || target.isBlank()) {
            return false;
        }
        return target.startsWith("/") || target.startsWith("~");
    }

    private static List<String> shellTokens(String segment) {
        ArrayList<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean escaped = false;

        for (int index = 0; index < segment.length(); index += 1) {
            char character = segment.charAt(index);
            if (escaped) {
                current.append(character);
                escaped = false;
                continue;
            }
            if (character == '\\') {
                escaped = true;
                continue;
            }
            if (character == '\'' && !doubleQuoted) {
                singleQuoted = !singleQuoted;
                continue;
            }
            if (character == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
                continue;
            }
            if (Character.isWhitespace(character) && !singleQuoted && !doubleQuoted) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(character);
        }

        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static boolean flushSegment(List<String> segments, StringBuilder current) {
        String trimmed = current.toString().trim();
        current.setLength(0);
        if (trimmed.isEmpty()) {
            return false;
        }
        segments.add(trimmed);
        return true;
    }

    private static boolean containsUnescapedCharacterOutsideQuotes(String command, char target) {
        boolean escaped = false;
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int index = 0; index < command.length(); index += 1) {
            char character = command.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (character == '\\') {
                escaped = true;
                continue;
            }
            if (character == '\'' && !doubleQuoted) {
                singleQuoted = !singleQuoted;
                continue;
            }
            if (character == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
                continue;
            }
            if (character == target && !singleQuoted && !doubleQuoted) {
                return true;
            }
        }
        return false;
    }

    private static RedirectionNormalization stripSafeRedirections(String command) {
        StringBuilder sanitized = new StringBuilder();
        boolean escaped = false;
        boolean singleQuoted = false;
        boolean doubleQuoted = false;

        for (int index = 0; index < command.length(); index += 1) {
            char character = command.charAt(index);
            if (escaped) {
                sanitized.append(character);
                escaped = false;
                continue;
            }
            if (character == '\\') {
                sanitized.append(character);
                escaped = true;
                continue;
            }
            if (character == '\'' && !doubleQuoted) {
                singleQuoted = !singleQuoted;
                sanitized.append(character);
                continue;
            }
            if (character == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
                sanitized.append(character);
                continue;
            }
            if (!singleQuoted && !doubleQuoted) {
                if (character == '<') {
                    return new RedirectionNormalization("", "Shell redirection and background operators are not allowed.");
                }
                if (character == '>') {
                    SafeRedirectionMatch match = matchSafeRedirection(command, index, sanitized);
                    if (match == null) {
                        return new RedirectionNormalization("", "Shell redirection and background operators are not allowed.");
                    }
                    index = match.nextIndex();
                    continue;
                }
            }
            sanitized.append(character);
        }

        return new RedirectionNormalization(sanitized.toString(), null);
    }

    private static SafeRedirectionMatch matchSafeRedirection(String command, int operatorIndex, StringBuilder sanitized) {
        trimFileDescriptorPrefix(sanitized);

        int cursor = operatorIndex + 1;
        if (cursor < command.length() && command.charAt(cursor) == '>') {
            cursor += 1;
        }
        while (cursor < command.length() && Character.isWhitespace(command.charAt(cursor))) {
            cursor += 1;
        }

        if (cursor < command.length() && command.charAt(cursor) == '&') {
            int fdCursor = cursor + 1;
            while (fdCursor < command.length() && Character.isDigit(command.charAt(fdCursor))) {
                fdCursor += 1;
            }
            if (fdCursor > cursor + 1) {
                return new SafeRedirectionMatch(fdCursor - 1);
            }
            return null;
        }

        String devNull = "/dev/null";
        if (cursor + devNull.length() > command.length()) {
            return null;
        }
        if (!command.regionMatches(cursor, devNull, 0, devNull.length())) {
            return null;
        }
        int next = cursor + devNull.length();
        if (next < command.length()) {
            char boundary = command.charAt(next);
            if (!Character.isWhitespace(boundary) && boundary != '\n' && boundary != '\r' && boundary != ';' && boundary != '|' && boundary != '&') {
                return null;
            }
        }
        return new SafeRedirectionMatch(next - 1);
    }

    private static void trimFileDescriptorPrefix(StringBuilder sanitized) {
        int cursor = sanitized.length() - 1;
        while (cursor >= 0 && Character.isDigit(sanitized.charAt(cursor))) {
            cursor -= 1;
        }
        int digitsStart = cursor + 1;
        if (digitsStart >= sanitized.length()) {
            return;
        }
        if (digitsStart == 0 || Character.isWhitespace(sanitized.charAt(digitsStart - 1))) {
            sanitized.setLength(digitsStart);
        }
    }

    private record ParseResult(
            List<String> segments,
            String error
    ) {
    }

    private record RedirectionNormalization(
            String command,
            String error
    ) {
    }

    private record SafeRedirectionMatch(
            int nextIndex
    ) {
    }

    record PermissionDecision(
            Behavior behavior,
            String reason
    ) {
        boolean allowsExecutionWithoutPrompt() {
            return behavior == Behavior.ALLOW;
        }

        boolean requiresApproval() {
            return behavior == Behavior.ASK;
        }

        boolean denied() {
            return behavior == Behavior.DENY;
        }

        static PermissionDecision allow(String reason) {
            return new PermissionDecision(Behavior.ALLOW, reason);
        }

        static PermissionDecision ask(String reason) {
            return new PermissionDecision(Behavior.ASK, reason);
        }

        static PermissionDecision deny(String reason) {
            return new PermissionDecision(Behavior.DENY, reason);
        }
    }

    enum Behavior {
        ALLOW,
        ASK,
        DENY
    }
}
