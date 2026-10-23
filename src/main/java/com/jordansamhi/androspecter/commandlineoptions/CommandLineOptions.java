package com.jordansamhi.androspecter.commandlineoptions;

import org.apache.commons.cli.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/*-
 * #%L
 * AndroSpecter
 *
 * %%
 * Copyright (C) 2023 Jordan Samhi
 * All rights reserved
 *
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-2.1.html>.
 * #L%
 */


/**
 * Caller-owned command-line parser. Parsing returns immutable data, never prints help,
 * and never exits the JVM. Instances are independent; configure and use each on one thread.
 */
public class CommandLineOptions {
    private Options options = newOptions();
    private final List<RegisteredOption> optionList = new ArrayList<>();
    private ParseResult lastResult;
    private String appName;
    private static CommandLineOptions instance;

    public CommandLineOptions() {
    }

    public CommandLineOptions(String appName) {
        setAppName(appName);
    }

    /** @deprecated Create a new parser for each caller instead of sharing mutable global state. */
    @Deprecated
    public static synchronized CommandLineOptions v() {
        if (instance == null) {
            instance = new CommandLineOptions();
        }
        return instance;
    }

    /**
     * Parses arguments and returns a snapshot. Help is recognized alongside regular options,
     * including when required options are absent. Unknown options and missing argument values
     * still produce validation errors. Tokens after {@code --} are positional arguments.
     *
     * @param args command-line arguments
     * @return parsed values, positional arguments, and help text
     * @throws ValidationException when the arguments do not match the registered options
     * @throws IllegalStateException when no application name has been configured
     */
    public ParseResult parse(String... args) {
        clearParsedValues();
        requireAppName();
        if (args == null || Arrays.stream(args).anyMatch(Objects::isNull)) {
            throw new ValidationException("Arguments must not be null", null);
        }
        try {
            // First recognize help with required checks disabled; do not mistake an option
            // value such as --input=--help or a positional --help for a help request.
            Options optional = new Options();
            for (Option option : options.getOptions()) {
                Option copy = (Option) option.clone();
                copy.setRequired(false);
                optional.addOption(copy);
            }
            CommandLineParser parser = new DefaultParser(false);
            CommandLine commandLine = parser.parse(optional, args);
            boolean help = commandLine.hasOption("help");
            if (!help) {
                commandLine = parser.parse(options, args);
            }
            lastResult = new ParseResult(commandLine, help, helpText());
            for (RegisteredOption option : optionList) {
                option.original.setValue(lastResult.getOptionValue(option.cli.getKey()));
            }
            return lastResult;
        } catch (ParseException e) {
            throw new ValidationException(e.getMessage(), e);
        }
    }

    /**
     * Compatibility wrapper that stores the latest result for the legacy getters.
     * Help and errors no longer print or exit; inspect {@link #isHelpRequested()} and
     * {@link #helpText()}, or catch {@link ValidationException} in the application.
     *
     * @param args command-line arguments
     * @deprecated Use {@link #parse(String...)} and its returned result.
     */
    @Deprecated
    public void parseArgs(String[] args) {
        parse(args);
    }

    /**
     * Registers a snapshot of an option declaration. Later declaration changes do not modify
     * the parser. The legacy descriptor's value is still updated after each successful parse.
     */
    public void addOption(CommandLineOption option) {
        Objects.requireNonNull(option, "option");
        Option cliOption = Option.builder(option.getShortOpt())
                .longOpt(option.getLongOpt())
                .desc(option.getDescription())
                .hasArg(option.hasArg())
                .argName(option.getLongOpt())
                .required(option.isRequired())
                .build();
        for (String alias : new String[]{cliOption.getOpt(), cliOption.getLongOpt()}) {
            if (alias != null && options.hasOption(alias)) {
                throw new IllegalArgumentException("Option is already registered or reserved: " + alias);
            }
        }
        options.addOption(cliOption);
        optionList.add(new RegisteredOption(option, cliOption));
    }

    /** Returns generated help text without writing to any output stream. */
    public String helpText() {
        requireAppName();
        StringWriter text = new StringWriter();
        PrintWriter writer = new PrintWriter(text);
        new HelpFormatter().printHelp(writer, 100, appName, null, options, 2, 2, null, true);
        writer.flush();
        return text.toString();
    }

    public boolean isHelpRequested() {
        return lastResult != null && lastResult.helpRequested();
    }

    /** @deprecated Read values from the immutable result returned by {@link #parse(String...)}. */
    @Deprecated
    public String getOptionValue(String name) {
        if (name == null) {
            return null;
        }
        for (RegisteredOption option : optionList) {
            if (Objects.equals(option.cli.getOpt(), name)
                    || Objects.equals(option.cli.getLongOpt(), name)) {
                return option.original.getValue();
            }
        }
        return null;
    }

    /** @deprecated Read option presence from the result returned by {@link #parse(String...)}. */
    @Deprecated
    public boolean hasOption(String name) {
        return lastResult != null && lastResult.hasOption(name);
    }

    /** Clears the configuration and the latest parse result, retaining built-in help. */
    public void reset() {
        clearParsedValues();
        options = newOptions();
        optionList.clear();
        appName = null;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        if (appName == null || appName.trim().isEmpty()) {
            throw new IllegalArgumentException("Application name must not be blank");
        }
        this.appName = appName;
    }

    private void requireAppName() {
        if (appName == null) {
            throw new IllegalStateException("Set the application name before parsing arguments");
        }
    }

    private void clearParsedValues() {
        lastResult = null;
        for (RegisteredOption option : optionList) {
            option.original.setValue(null);
        }
    }

    private static Options newOptions() {
        return new Options().addOption(Option.builder("h").longOpt("help").desc("Show help").build());
    }

    private static final class RegisteredOption {
        private final CommandLineOption original;
        private final Option cli;

        private RegisteredOption(CommandLineOption original, Option cli) {
            this.original = original;
            this.cli = cli;
        }
    }

    /** Immutable snapshot independent of subsequent parses or descriptor mutations. */
    public static final class ParseResult {
        private final Map<String, String> values;
        private final List<String> arguments;
        private final boolean helpRequested;
        private final String helpText;

        private ParseResult(CommandLine commandLine, boolean helpRequested, String helpText) {
            Map<String, String> parsed = new LinkedHashMap<>();
            for (Option option : commandLine.getOptions()) {
                String value = commandLine.getOptionValue(option.getKey());
                if (option.getOpt() != null) {
                    parsed.put(option.getOpt(), value);
                }
                if (option.getLongOpt() != null) {
                    parsed.put(option.getLongOpt(), value);
                }
            }
            this.values = Collections.unmodifiableMap(parsed);
            this.arguments = List.copyOf(Arrays.asList(commandLine.getArgs()));
            this.helpRequested = helpRequested;
            this.helpText = helpText;
        }

        public boolean helpRequested() {
            return helpRequested;
        }

        public String helpText() {
            return helpText;
        }

        public boolean hasOption(String name) {
            return values.containsKey(name);
        }

        public String getOptionValue(String name) {
            return values.get(name);
        }

        public List<String> arguments() {
            return arguments;
        }
    }

    /** Invalid command-line input, for the application to report and choose an exit status. */
    public static final class ValidationException extends IllegalArgumentException {
        public ValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
