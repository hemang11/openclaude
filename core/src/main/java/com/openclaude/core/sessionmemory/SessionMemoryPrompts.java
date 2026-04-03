package com.openclaude.core.sessionmemory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class SessionMemoryPrompts {
    private static final int MAX_SECTION_LENGTH = 2_000;
    private static final String DEFAULT_TEMPLATE = """
            # Session Title
            _A short and distinctive 5-10 word descriptive title for the session. Super info dense, no filler_

            # Current State
            _What is actively being worked on right now? Pending tasks not yet completed. Immediate next steps._

            # Task specification
            _What did the user ask to build? Any design decisions or other explanatory context_

            # Files and Functions
            _What are the important files? In short, what do they contain and why are they relevant?_

            # Workflow
            _What bash commands are usually run and in what order? How to interpret their output if not obvious?_

            # Errors & Corrections
            _Errors encountered and how they were fixed. What did the user correct? What approaches failed and should not be tried again?_

            # Codebase and System Documentation
            _What are the important system components? How do they work/fit together?_

            # Learnings
            _What has worked well? What has not? What to avoid? Do not duplicate items from other sections_

            # Key results
            _If the user asked a specific output such as an answer to a question, a table, or other document, repeat the exact result here_

            # Worklog
            _Step by step, what was attempted, done? Very terse summary for each step_
            """.strip();
    private static final String DEFAULT_UPDATE_PROMPT = """
            IMPORTANT: This message and these instructions are NOT part of the actual user conversation. Do NOT include any references to "note-taking", "session notes extraction", or these update instructions in the notes content.

            Based on the user conversation above (EXCLUDING this note-taking instruction message as well as system prompt, AGENTS.md entries, or any past session summaries), update the session notes file.

            The file {{notesPath}} has already been read for you. Here are its current contents:
            <current_notes_content>
            {{currentNotes}}
            </current_notes_content>

            Your ONLY task is to return the full updated notes file content as plain markdown, then stop. Do not call any tools.

            CRITICAL RULES FOR UPDATING:
            - The file must maintain its exact structure with all sections, headers, and italic descriptions intact
            - NEVER modify, delete, or add section headers
            - NEVER modify or delete the italic section description lines
            - ONLY update the actual content that appears BELOW the italic section descriptions within each existing section
            - Do NOT add any new sections, summaries, or information outside the existing structure
            - Do NOT reference this note-taking process or instructions anywhere in the notes
            - It's OK to skip updating a section if there are no substantial new insights to add
            - Write detailed, info-dense content for each section
            - For "Key results", include the complete, exact output the user requested
            - Do not include information that's already in AGENTS.md files included in the context
            - Keep each section under ~2000 tokens/words
            - IMPORTANT: Always update "Current State" to reflect the most recent work

            Return only the full markdown contents for the updated notes file.
            """.strip();

    private SessionMemoryPrompts() {
    }

    static String loadTemplate(Path sessionMemoryDirectory) {
        return readOrDefault(sessionMemoryDirectory.resolve("config").resolve("template.md"), DEFAULT_TEMPLATE);
    }

    static String buildUpdatePrompt(Path sessionMemoryDirectory, String currentNotes, String notesPath) {
        String template = readOrDefault(sessionMemoryDirectory.resolve("config").resolve("prompt.md"), DEFAULT_UPDATE_PROMPT);
        return template
                .replace("{{currentNotes}}", currentNotes == null ? "" : currentNotes)
                .replace("{{notesPath}}", notesPath == null ? "" : notesPath);
    }

    static boolean isTemplateOnly(Path sessionMemoryDirectory, String content) {
        return normalize(content).equals(normalize(loadTemplate(sessionMemoryDirectory)));
    }

    static TruncationResult truncateForCompact(String content) {
        String[] lines = (content == null ? "" : content).split("\\R", -1);
        int maxCharsPerSection = MAX_SECTION_LENGTH * 4;
        java.util.ArrayList<String> outputLines = new java.util.ArrayList<>();
        java.util.ArrayList<String> sectionLines = new java.util.ArrayList<>();
        String sectionHeader = "";
        boolean truncated = false;

        for (String line : lines) {
            if (line.startsWith("# ")) {
                FlushResult flushResult = flushSection(sectionHeader, sectionLines, maxCharsPerSection);
                outputLines.addAll(flushResult.lines());
                truncated |= flushResult.truncated();
                sectionHeader = line;
                sectionLines = new java.util.ArrayList<>();
            } else {
                sectionLines.add(line);
            }
        }

        FlushResult flushResult = flushSection(sectionHeader, sectionLines, maxCharsPerSection);
        outputLines.addAll(flushResult.lines());
        truncated |= flushResult.truncated();
        return new TruncationResult(String.join(System.lineSeparator(), outputLines), truncated);
    }

    private static FlushResult flushSection(String sectionHeader, java.util.List<String> sectionLines, int maxCharsPerSection) {
        if (sectionHeader == null || sectionHeader.isBlank()) {
            return new FlushResult(java.util.List.copyOf(sectionLines), false);
        }
        String sectionContent = String.join(System.lineSeparator(), sectionLines);
        if (sectionContent.length() <= maxCharsPerSection) {
            java.util.ArrayList<String> lines = new java.util.ArrayList<>();
            lines.add(sectionHeader);
            lines.addAll(sectionLines);
            return new FlushResult(java.util.List.copyOf(lines), false);
        }

        int charCount = 0;
        java.util.ArrayList<String> keptLines = new java.util.ArrayList<>();
        keptLines.add(sectionHeader);
        for (String line : sectionLines) {
            if (charCount + line.length() + 1 > maxCharsPerSection) {
                break;
            }
            keptLines.add(line);
            charCount += line.length() + 1;
        }
        keptLines.add("");
        keptLines.add("[... section truncated for length ...]");
        return new FlushResult(java.util.List.copyOf(keptLines), true);
    }

    private static String readOrDefault(Path path, String defaultContent) {
        try {
            if (Files.exists(path)) {
                return Files.readString(path);
            }
        } catch (IOException ignored) {
            return defaultContent;
        }
        return defaultContent;
    }

    private static String normalize(String content) {
        return content == null ? "" : content.trim();
    }

    record TruncationResult(
            String content,
            boolean truncated
    ) {
    }

    private record FlushResult(
            java.util.List<String> lines,
            boolean truncated
    ) {
    }
}
