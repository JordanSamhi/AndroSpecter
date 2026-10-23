package com.jordansamhi.androspecter.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import picocli.CommandLine;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

abstract class CliSupport {
    static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    enum ReportFormat { JSON, TEXT }
    @Spec CommandSpec spec;
    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this command's help.") boolean help;

    CommandLine.ParameterException usage(String message) {
        return new CommandLine.ParameterException(spec.commandLine(), message);
    }

    void requireFile(Path path, String description) {
        if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw usage(description + " must be a readable file: " + path);
        }
    }

    PrintWriter err() { return spec.commandLine().getErr(); }

    void write(OutputOptions options, List<Path> inputs, OutputAction action) throws IOException {
        options.validate(this, inputs);
        if (options.path == null) {
            PrintWriter output = spec.commandLine().getOut();
            action.write(output);
            output.flush();
            if (output.checkError()) throw new IOException("Cannot write standard output");
            return;
        }
        Path target = options.path.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".androspecter-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                action.write(writer);
            }
            // Recheck aliases/existing destinations after analysis, then publish a fully written file.
            options.validate(this, inputs);
            if (options.overwrite) Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temporary, target);
            err().println("Written: " + target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @FunctionalInterface interface OutputAction { void write(Writer writer) throws IOException; }

    static final class OutputOptions {
        @Option(names = {"-o", "--output"}, paramLabel = "FILE", description = "Write to a file (default: standard output).") Path path;
        @Option(names = "--overwrite", description = "Allow replacement of an existing output file; inputs remain protected.") boolean overwrite;

        void validate(CliSupport command, List<Path> inputs) throws IOException {
            if (path == null) return;
            Path target = path.toAbsolutePath().normalize();
            for (Path input : inputs) {
                if (target.equals(input.toAbsolutePath().normalize())
                        || (Files.exists(target) && Files.exists(input) && Files.isSameFile(target, input))) {
                    throw command.usage("Output must not replace an input: " + path);
                }
            }
            if (Files.exists(target) && (!Files.isRegularFile(target) || !overwrite)) {
                throw command.usage("Output already exists or is not a regular file: " + path
                        + ". Choose another path or use --overwrite for an existing file.");
            }
            if (Files.isSymbolicLink(target) && !Files.exists(target)) {
                throw command.usage("Output must not be a dangling symbolic link: " + path);
            }
        }
    }
}
