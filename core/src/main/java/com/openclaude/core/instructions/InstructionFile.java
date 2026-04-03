package com.openclaude.core.instructions;

import java.nio.file.Path;
import java.util.Objects;

public record InstructionFile(
        Path path,
        InstructionScope scope,
        String content,
        Path parent
) {
    public InstructionFile {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        scope = Objects.requireNonNull(scope, "scope");
        content = content == null ? "" : content.strip();
        parent = parent == null ? null : parent.toAbsolutePath().normalize();
    }
}
