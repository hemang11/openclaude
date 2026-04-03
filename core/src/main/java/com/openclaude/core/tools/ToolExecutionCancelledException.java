package com.openclaude.core.tools;

import java.util.concurrent.CancellationException;

final class ToolExecutionCancelledException extends CancellationException {
    ToolExecutionCancelledException(String message) {
        super(message == null || message.isBlank() ? "Prompt cancelled." : message);
    }
}
