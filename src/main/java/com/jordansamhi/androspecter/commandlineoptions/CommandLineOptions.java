package com.jordansamhi.androspecter.commandlineoptions;

import com.jordansamhi.androspecter.printers.Writer;
import org.apache.commons.cli.*;

import java.util.ArrayList;
import java.util.List;

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
 * A singleton class to handle command line options parsing and validation.
 * Encapsulates Apache Commons CLI library for parsing command line options.
 *
 * @author <a href="https://jordansamhi.com">Jordan Samhi</a>
 */
public class CommandLineOptions {

    /**
     * The Apache Commons CLI Options object for regular options.
     */
    private final Options options;

    /**
     * The Apache Commons CLI Options object for first options (like help).
     */
    private final Options firstOptions;

    /**
     * The Apache Commons CLI Parser object to parse command line options.
     */
    private final CommandLineParser parser;

    /**
     * The Apache Commons CLI CommandLine object, holds parsed command line options.
     */
    private CommandLine cmdLine;

    /**
     * The application name.
     */
    private String appName;

    /**
     * Singleton instance of this class.
     */
    private static CommandLineOptions instance;

    /**
     * List of CommandLineOption objects.
     */
    private final List<CommandLineOption> optionList;

    /**
     * Constructor for CommandLineOptions.
     */
    private CommandLineOptions() {
        this.options = new Options();
        this.firstOptions = new Options();
        this.parser = new DefaultParser();
        this.optionList = new ArrayList<>();
        final Option help = Option.builder("h")
                .longOpt("help")
                .desc("Prints this message")
                .argName("h")
                .build();
        this.firstOptions.addOption(help);
        this.appName = null;
    }

    /**
     * Returns the singleton instance of CommandLineOptions.
     *
     * @return The singleton instance of CommandLineOptions.
     */
    public static CommandLineOptions v() {
        if (instance == null) {
            instance = new CommandLineOptions();
        }
        return instance;
    }

    /**
     * Parses command line arguments.
     *
     * @param args The command line arguments.
     */
    public void parseArgs(String[] args) {
        if (this.appName == null) {
            Writer.v().perror("First sets the name of your program, use the setAppName method.");
            System.exit(1);
        }
        HelpFormatter formatter;
        try {
            CommandLine cmdFirstLine = this.parser.parse(this.firstOptions, args, true);
            if (cmdFirstLine.hasOption("help")) {
                formatter = new HelpFormatter();
                formatter.printHelp(this.getAppName(), this.options, true);
                System.exit(0);
            }
            cmdLine = parser.parse(options, args);

            for (CommandLineOption opt : optionList) {
                if (cmdLine.hasOption(opt.getShortOpt())) {
                    opt.setValue(cmdLine.getOptionValue(opt.getShortOpt()));
                }
            }
        } catch (ParseException e) {
            Writer.v().perror(e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Adds a CommandLineOption to the list of options.
     *
     * @param option The CommandLineOption to add.
     */
    public void addOption(CommandLineOption option) {
        optionList.add(option);
        Option cliOption = Option.builder(option.getShortOpt())
                .longOpt(option.getLongOpt())
                .desc(option.getDescription())
                .hasArg(option.hasArg())
                .argName(option.getLongOpt())
                .required(option.isRequired())
                .build();
        options.addOption(cliOption);
    }

    /**
     * Returns the value of the specified option.
     *
     * @param longOpt The long form of the option.
     * @return The value of the specified option, or null if not found.
     */
    public String getOptionValue(String longOpt) {
        for (CommandLineOption opt : optionList) {
            if (opt.getLongOpt().equals(longOpt)) {
                return opt.getValue();
            }
        }
        return null;
    }

    /**
     * Returns whether an option is set.
     *
     * @param shortOpt The short form of the option.
     * @return Whether the option is set or not.
     */
    public boolean hasOption(String shortOpt) {
        return cmdLine.hasOption(shortOpt);
    }

    /**
     * Returns the application name.
     *
     * @return The application name.
     */
    public String getAppName() {
        return appName;
    }

    /**
     * Sets the application name.
     *
     * @param appName The application name.
     */
    public void setAppName(String appName) {
        this.appName = appName;
    }
}