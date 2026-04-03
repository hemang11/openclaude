package com.openclaude.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Option;

@Command(
        name = "openclaude",
        mixinStandardHelpOptions = true,
        versionProvider = OpenClaudeCommand.VersionProvider.class,
        description = "OpenClaude Java CLI bootstrap"
)
public final class OpenClaudeCommand implements Runnable {
    public static final String VERSION = System.getProperty("openclaude.version", "0.1.0-SNAPSHOT");
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

    public static final class VersionProvider implements IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{VERSION};
        }
    }
}
