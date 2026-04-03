package com.openclaude.cli;

import com.openclaude.core.provider.PromptRouter;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import picocli.CommandLine;

public final class InteractiveShell {
    private final CommandLine commandLine;
    private final PromptRouter promptRouter;

    public InteractiveShell(CommandLine commandLine, PromptRouter promptRouter) {
        this.commandLine = commandLine;
        this.promptRouter = promptRouter;
    }

    public int run() {
        System.out.println("OpenClaude interactive shell");
        System.out.println("Available slash commands: /provider, /models, /help, /exit");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (true) {
                System.out.print("> ");
                String line = reader.readLine();
                if (line == null) {
                    return 0;
                }

                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                if ("/exit".equals(trimmed) || "/quit".equals(trimmed)) {
                    return 0;
                }

                if ("/help".equals(trimmed)) {
                    printHelp();
                    continue;
                }

                if (trimmed.startsWith("/")) {
                    String[] args = Arrays.stream(trimmed.substring(1).split("\\s+"))
                            .filter(token -> !token.isBlank())
                            .toArray(String[]::new);
                    if (args.length == 0) {
                        continue;
                    }
                    commandLine.execute(args);
                    continue;
                }

                try {
                    AtomicBoolean streamed = new AtomicBoolean(false);
                    promptRouter.execute(trimmed, delta -> {
                        streamed.set(true);
                        System.out.print(delta);
                        System.out.flush();
                    });
                    if (streamed.get()) {
                        System.out.println();
                    }
                } catch (RuntimeException exception) {
                    System.out.println("Prompt execution failed: " + exception.getMessage());
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to run interactive shell", exception);
        }
    }

    private static void printHelp() {
        System.out.println("/provider list");
        System.out.println("/provider status");
        System.out.println("/provider connect <provider> [--api-key-env VAR | --api-key KEY | --aws-profile NAME | --browser]");
        System.out.println("/provider use <provider>");
        System.out.println("/provider disconnect <provider>");
        System.out.println("/models");
        System.out.println("/models current");
        System.out.println("/models use <model-id>");
        System.out.println("/exit");
    }
}
