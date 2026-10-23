package com.jordansamhi.androspecter.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;

/** Standalone command-line entry point; the library has no dependency on this executable. */
public final class AndroSpecterCli {
    private AndroSpecterCli() { }

    /**
     * Runs one command and exits this CLI process with its status. Standard output is reserved for
     * command results; backend console output and logging are routed to standard error. The CLI's
     * logging provider defaults to WARN unless the caller supplies its own system property.
     *
     * @param args command name and options; use {@code --help} for available commands
     */
    public static void main(String[] args) {
        PrintWriter output = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        PrintWriter error = new PrintWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8), true);
        System.setOut(System.err);
        if (System.getProperty("org.slf4j.simpleLogger.defaultLogLevel") == null) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        }
        System.exit(run(args, output, error));
    }

    /**
     * Executes one command without exiting the JVM, closing supplied writers, or changing global
     * console/logging settings. Each invocation has independent options. Analysis commands still
     * require exclusive Soot ownership on the calling thread; backend logs use the host's logging
     * configuration. Prefer the library APIs for application integrations.
     *
     * @param args non-null command arguments
     * @param output non-null writer for command results or help
     * @param error non-null writer for diagnostics and progress
     * @return 0 for success, 1 for execution/I/O failure, 2 for usage errors, 3 for incomplete or
     *         cancelled analysis, or 4 for completed taint findings when explicitly requested
     * @throws NullPointerException if an argument is null
     */
    public static int run(String[] args, PrintWriter output, PrintWriter error) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");
        CommandLine command = new CommandLine(new RootCommand())
                .addSubcommand("metadata", new MetadataCommand())
                .addSubcommand("inspect", new InspectCommand())
                .addSubcommand("graph", new GraphCommand())
                .addSubcommand("query", new QueryCommand())
                .addSubcommand("taint", new TaintCommand());
        command.setOut(output).setErr(error).setCaseInsensitiveEnumValuesAllowed(true);
        command.setExecutionExceptionHandler((failure, cli, parsed) -> {
            error.println("Error: " + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
            error.flush();
            if (failure instanceof CommandLine.ParameterException) return 2;
            return failure instanceof CancellationException ? 3 : 1;
        });
        int status = command.execute(args);
        output.flush();
        error.flush();
        return status;
    }

    @Command(name = "androspecter", mixinStandardHelpOptions = true,
            versionProvider = Version.class, description = "Analyze Android APKs and explore detached static call graphs.")
    static final class RootCommand implements Callable<Integer> {
        @Spec CommandSpec spec;

        /**
         * Prints command help when no operation was selected.
         * @return usage-error status
         */
        @Override public Integer call() {
            spec.commandLine().usage(spec.commandLine().getOut());
            return 2;
        }
    }

    static final class Version implements CommandLine.IVersionProvider {
        /**
         * Returns the executable's build version.
         * @return one human-readable version line
         */
        @Override public String[] getVersion() {
            String version = AndroSpecterCli.class.getPackage().getImplementationVersion();
            return new String[]{"AndroSpecter " + (version == null ? "development" : version)};
        }
    }
}
