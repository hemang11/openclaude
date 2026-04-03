package io.openclaude.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

@Command(
        name = "openclaude",
        mixinStandardHelpOptions = true,
        version = "0.1.0-SNAPSHOT",
        description = "Provider-neutral coding CLI in Java.",
        synopsisSubcommandLabel = "COMMAND")
public final class OpenClaudeCli implements Callable<Integer> {
    @Override
    public Integer call() {
        System.out.println("openclaude scaffold initialized");
        System.out.println("Available bootstrap commands: provider, models");
        System.out.println("Implementation details: openclaude/IMPLEMENTATION.md");
        System.out.println("Intentional deviations: openclaude/DRIFT.md");
        return 0;
    }
}
