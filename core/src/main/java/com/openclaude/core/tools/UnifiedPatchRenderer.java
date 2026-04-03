package com.openclaude.core.tools;

import java.util.ArrayList;
import java.util.List;

final class UnifiedPatchRenderer {
    private static final int CONTEXT_LINES = 3;

    private UnifiedPatchRenderer() {
    }

    static String render(String filePath, String original, String updated) {
        List<String> originalLines = splitLines(original);
        List<String> updatedLines = splitLines(updated);
        if (originalLines.equals(updatedLines)) {
            return "--- " + filePath + System.lineSeparator()
                    + "+++ " + filePath + System.lineSeparator()
                    + "@@ no changes @@";
        }

        int sharedPrefix = sharedPrefix(originalLines, updatedLines);
        int originalSuffix = originalLines.size() - 1;
        int updatedSuffix = updatedLines.size() - 1;
        while (originalSuffix >= sharedPrefix
                && updatedSuffix >= sharedPrefix
                && originalLines.get(originalSuffix).equals(updatedLines.get(updatedSuffix))) {
            originalSuffix -= 1;
            updatedSuffix -= 1;
        }

        int originalContextStart = Math.max(0, sharedPrefix - CONTEXT_LINES);
        int updatedContextStart = Math.max(0, sharedPrefix - CONTEXT_LINES);
        int originalContextEnd = Math.min(originalLines.size() - 1, originalSuffix + CONTEXT_LINES);
        int updatedContextEnd = Math.min(updatedLines.size() - 1, updatedSuffix + CONTEXT_LINES);
        int originalCount = originalContextEnd >= originalContextStart ? originalContextEnd - originalContextStart + 1 : 0;
        int updatedCount = updatedContextEnd >= updatedContextStart ? updatedContextEnd - updatedContextStart + 1 : 0;

        StringBuilder patch = new StringBuilder();
        patch.append("--- ").append(filePath).append(System.lineSeparator());
        patch.append("+++ ").append(filePath).append(System.lineSeparator());
        patch.append("@@ -")
                .append(formatRange(originalContextStart + 1, originalCount))
                .append(" +")
                .append(formatRange(updatedContextStart + 1, updatedCount))
                .append(" @@")
                .append(System.lineSeparator());

        for (int index = originalContextStart; index < sharedPrefix; index += 1) {
            patch.append(' ').append(originalLines.get(index)).append(System.lineSeparator());
        }
        for (int index = sharedPrefix; index <= originalSuffix; index += 1) {
            patch.append('-').append(originalLines.get(index)).append(System.lineSeparator());
        }
        for (int index = sharedPrefix; index <= updatedSuffix; index += 1) {
            patch.append('+').append(updatedLines.get(index)).append(System.lineSeparator());
        }
        for (int index = originalSuffix + 1; index <= originalContextEnd; index += 1) {
            patch.append(' ').append(originalLines.get(index)).append(System.lineSeparator());
        }
        return patch.toString().stripTrailing();
    }

    private static int sharedPrefix(List<String> originalLines, List<String> updatedLines) {
        int max = Math.min(originalLines.size(), updatedLines.size());
        int index = 0;
        while (index < max && originalLines.get(index).equals(updatedLines.get(index))) {
            index += 1;
        }
        return index;
    }

    private static String formatRange(int startLine, int count) {
        if (count == 0) {
            return Math.max(0, startLine - 1) + ",0";
        }
        if (count == 1) {
            return Integer.toString(startLine);
        }
        return startLine + "," + count;
    }

    private static List<String> splitLines(String value) {
        String normalized = value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.isEmpty()) {
            return List.of();
        }
        ArrayList<String> lines = new ArrayList<>(List.of(normalized.split("\n", -1)));
        if (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
        return List.copyOf(lines);
    }
}
