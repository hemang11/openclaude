package io.openclaude.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;

@Command(
        name = "openclaude",
        mixinStandardHelpOptions = true,
        versionProvider = OpenClaudeCli.VersionProvider.class,
        description = "Provider-neutral coding CLI in Java.",
        synopsisSubcommandLabel = "COMMAND")
public final class OpenClaudeCli implements Callable<Integer> {
    static final String VERSION = System.getProperty("openclaude.version", "0.1.0-SNAPSHOT");

    @Override
    public Integer call() {
        System.out.println("openclaude scaffold initialized");
        System.out.println("Available bootstrap commands: provider, models");
        System.out.println("Implementation details: openclaude/IMPLEMENTATION.md");
        System.out.println("Intentional deviations: openclaude/DRIFT.md");
        return 0;
    }

    public static final class VersionProvider implements IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{VERSION};
        }
    }
}
