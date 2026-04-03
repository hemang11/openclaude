package com.openclaude.core.tools;

import com.openclaude.core.session.ConversationSession;
import com.openclaude.core.session.FileReadState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class FileMutationGuards {
    static final String READ_BEFORE_WRITE_ERROR = "File has not been read yet. Read it first before writing to it.";
    static final String STALE_FILE_ERROR = "File has been modified since read, either by the user or by a linter. Read it again before attempting to write it.";

    private FileMutationGuards() {}

    static String requireFreshReadForMutation(
            ConversationSession session,
            Path filePath,
            String currentContent
    ) throws IOException {
        if (session == null) {
            return READ_BEFORE_WRITE_ERROR;
        }
        FileReadState lastRead = session.readFileState().get(normalize(filePath));
        if (lastRead == null || lastRead.partialView()) {
            return READ_BEFORE_WRITE_ERROR;
        }

        long lastWriteTime = getFileModificationTime(filePath);
        if (lastWriteTime > lastRead.timestamp()) {
            if (lastRead.isFullView() && currentContent.equals(lastRead.content())) {
                return null;
            }
            return STALE_FILE_ERROR;
        }
        return null;
    }

    static ToolSessionEffect recordReadState(
            ConversationSession session,
            Path filePath,
            String content,
            long timestamp,
            Integer offset,
            Integer limit,
            boolean partialView
    ) {
        Map<String, FileReadState> next = new LinkedHashMap<>(session == null ? Map.of() : session.readFileState());
        next.put(normalize(filePath), new FileReadState(content, timestamp, offset, limit, partialView));
        return new ToolSessionEffect(null, null, next);
    }

    static long getFileModificationTime(Path filePath) throws IOException {
        return Files.getLastModifiedTime(filePath).toMillis();
    }

    private static String normalize(Path filePath) {
        return filePath.toAbsolutePath().normalize().toString();
    }
}
