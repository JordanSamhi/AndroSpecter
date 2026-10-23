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
 * A built-in {@code -h}/{@code --help} flag is always available. For example:
 * <pre>{@code
 * CommandLineOptions options = new CommandLineOptions("analyze");
 * options.addOption(new CommandLineOption("apk", "a", "APK file", true, true));
 * CommandLineOptions.ParseResult result = options.parse(args);
 * if (result.helpRequested()) {
 *     // Display result.helpText() using the application's chosen output.
 * } else {
 *     String apk = result.getOptionValue("apk");
 * }
 * }</pre>
 */
public class CommandLineOptions {
    private Options options = newOptions();
    private final List<RegisteredOption> optionList = new ArrayList<>();
    private ParseResult lastResult;
    private String appName;
    private static CommandLineOptions instance;

    /**
     * Creates an independent parser with the built-in help flag. Call
     * {@link #setAppName(String)} before parsing arguments or requesting help text.
     */
    public CommandLineOptions() {
    }

    /**
     * Creates an independent parser with an application name and the built-in help flag.
     *
     * @param appName executable name or command synopsis used in generated help
     * @throws IllegalArgumentException if {@code appName} is null or blank
     */
    public CommandLineOptions(String appName) {
        setAppName(appName);
    }

    /**
     * Returns the legacy mutable singleton. Only singleton creation is synchronized;
     * configuration and parsing on the returned instance are not thread-safe.
     *
     * @return the shared parser, initially without an application name
     * @deprecated Create a new parser for each caller instead of sharing mutable global state.
     */
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
     * Long-option abbreviations are not accepted. Each attempt clears legacy parsed values,
     * including a failed attempt; an earlier returned snapshot remains unchanged.
     *
     * @param args command-line arguments
     * @return parsed values, positional arguments, and help text
     * @throws ValidationException when arguments are null, contain null elements, or do not
     *         match the registered options
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
     * @throws ValidationException if arguments are invalid, as described by {@link #parse(String...)}
     * @throws IllegalStateException if no application name has been configured
     * @deprecated Use {@link #parse(String...)} and its returned result.
     */
    @Deprecated
    public void parseArgs(String[] args) {
        parse(args);
    }

    /**
     * Registers a snapshot of an option declaration. Later declaration changes do not modify
     * the parser. The legacy descriptor's value is still updated after each successful parse.
     * Use aliases without leading hyphens; {@code h} and {@code help} are reserved.
     *
     * @param option mutable descriptor whose declaration is copied at registration
     * @throws NullPointerException if {@code option} is null
     * @throws IllegalArgumentException if an alias is invalid, already registered, or reserved
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

    /**
     * Generates help for the current application name and registered options without writing
     * to any output stream. The caller chooses whether and where to display it.
     *
     * @return formatted help text, including the built-in help flag
     * @throws IllegalStateException if no application name has been configured
     */
    public String helpText() {
        requireAppName();
        StringWriter text = new StringWriter();
        PrintWriter writer = new PrintWriter(text);
        new HelpFormatter().printHelp(writer, 100, appName, null, options, 2, 2, null, true);
        writer.flush();
        return text.toString();
    }

    /**
     * Reports the help flag from the latest successful parse. A failed parse or reset clears
     * this state; a previously returned ParseResult retains its own independent value.
     *
     * @return true if the current stored result requests help, otherwise false
     */
    public boolean isHelpRequested() {
        return lastResult != null && lastResult.helpRequested();
    }

    /**
     * Reads the mutable legacy descriptor value for a registered short or long alias.
     * Because callers can modify descriptors, this may differ from the last parse snapshot.
     *
     * @param name alias without leading hyphens; null is permitted and returns null
     * @return the descriptor value, or null for an unknown alias, absent value, or flag
     * @deprecated Read values from the immutable result returned by {@link #parse(String...)}.
     */
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

    /**
     * Checks option presence in the latest stored parse snapshot, including flags without values.
     *
     * @param name short or long alias without leading hyphens
     * @return true if present in the latest result; false before parsing, after failure/reset,
     *         or for an unknown or null alias
     * @deprecated Read option presence from the result returned by {@link #parse(String...)}.
     */
    @Deprecated
    public boolean hasOption(String name) {
        return lastResult != null && lastResult.hasOption(name);
    }

    /**
     * Removes registered options, clears their legacy values and the latest result, and
     * unsets the application name. The built-in help flag remains. Previously returned
     * snapshots are unchanged; configure a new name before parsing again.
     */
    public void reset() {
        clearParsedValues();
        options = newOptions();
        optionList.clear();
        appName = null;
    }

    /**
     * Returns the name or synopsis used in subsequently generated help.
     *
     * @return the configured name, or null before configuration or after {@link #reset()}
     */
    public String getAppName() {
        return appName;
    }

    /**
     * Sets the name or synopsis used in future help text. This does not update help already
     * captured in a ParseResult, and surrounding whitespace is retained.
     *
     * @param appName nonblank executable name or command synopsis
     * @throws IllegalArgumentException if {@code appName} is null or blank
     */
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

        /**
         * Tests whether {@code -h} or {@code --help} was parsed as an option. An option
         * value or positional argument containing {@code --help} does not request help.
         *
         * @return true if this parse requested help
         */
        public boolean helpRequested() {
            return helpRequested;
        }

        /**
         * Returns help captured using the configuration at parse time, whether or not the
         * user requested it. Later parser configuration changes do not alter this text.
         *
         * @return formatted help text ready for caller-controlled display
         */
        public String helpText() {
            return helpText;
        }

        /**
         * Tests option presence independently of whether the option accepts a value.
         * Both registered aliases refer to the same parsed option.
         *
         * @param name short or long alias without leading hyphens
         * @return true if present; false for an absent, unknown, or null alias
         */
        public boolean hasOption(String name) {
            return values.containsKey(name);
        }

        /**
         * Returns an option's parsed argument using either registered alias. For repeated
         * occurrences, the first value is retained. Use {@link #hasOption(String)} to
         * distinguish a present flag from an absent option.
         *
         * @param name short or long alias without leading hyphens
         * @return the argument, or null for a flag or an absent, unknown, or null alias
         */
        public String getOptionValue(String name) {
            return values.get(name);
        }

        /**
         * Returns positional arguments in their original order, including tokens after the
         * {@code --} option terminator. The terminator itself is not included.
         *
         * @return an immutable list of non-option arguments
         */
        public List<String> arguments() {
            return arguments;
        }
    }

    /** Invalid command-line input, for the application to report and choose an exit status. */
    public static final class ValidationException extends IllegalArgumentException {
        /**
         * Creates an input-validation error without printing output or choosing an exit status.
         *
         * @param message explanation of the invalid input; may be null
         * @param cause underlying parser failure, or null when no cause is available
         */
        public ValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
