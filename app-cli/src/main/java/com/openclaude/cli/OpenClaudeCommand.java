package com.openclaude.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "openclaude",
        mixinStandardHelpOptions = true,
        version = OpenClaudeCommand.VERSION,
        description = "OpenClaude Java CLI bootstrap"
)
public final class OpenClaudeCommand implements Runnable {
    public static final String VERSION = "0.1.0-SNAPSHOT";
    public static final String RESUME_PICKER = "__openclaude_resume_picker__";

    @Option(
            names = "--resume",
            arity = "0..1",
            fallbackValue = RESUME_PICKER,
            description = "Resume a session by id, or show resumable sessions for the current directory when no id is given"
    )
    private String resumeSessionId;

    @Override
    public void run() {
        System.out.println("Run `openclaude` for the interactive shell, or `openclaude stdio` for the React/Ink frontend backend bridge.");
    }

    public String resumeSessionId() {
        return resumeSessionId;
    }
}
