package com.jordansamhi.androspecter.commandlineoptions;

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
 * Represents a command line option.
 * Encapsulates all the properties of a command line option like its long and short form,
 * description, whether it requires an argument, whether it's mandatory, and its value.
 *
 * @author <a href="https://jordansamhi.com">Jordan Samhi</a>
 */
public class CommandLineOption {

    /**
     * The long form of the option. E.g., "help"
     */
    private String longOpt;

    /**
     * The short form of the option. E.g., "h"
     */
    private String shortOpt;

    /**
     * A brief description of what the option does.
     */
    private String description;

    /**
     * Flag to indicate if the option requires an argument.
     */
    private boolean hasArg;

    /**
     * Flag to indicate if the option is mandatory.
     */
    private boolean isRequired;

    /**
     * The value of the option if provided on the command line.
     */
    private String value;

    /**
     * Constructor for CommandLineOption.
     *
     * @param longOpt     The long form of the option.
     * @param shortOpt    The short form of the option.
     * @param description The description of the option.
     * @param hasArg      True if the option requires an argument.
     * @param isRequired  True if the option is mandatory.
     */
    public CommandLineOption(String longOpt, String shortOpt, String description, boolean hasArg, boolean isRequired) {
        this.longOpt = longOpt;
        this.shortOpt = shortOpt;
        this.description = description;
        this.hasArg = hasArg;
        this.isRequired = isRequired;
    }

    /**
     * Returns the long form of the option.
     *
     * @return The long form of the option.
     */
    public String getLongOpt() {
        return longOpt;
    }

    /**
     * Sets the long form of the option.
     *
     * @param longOpt The long form of the option.
     */
    public void setLongOpt(String longOpt) {
        this.longOpt = longOpt;
    }

    /**
     * Returns the short form of the option.
     *
     * @return The short form of the option.
     */
    public String getShortOpt() {
        return shortOpt;
    }

    /**
     * Sets the short form of the option.
     *
     * @param shortOpt The short form of the option.
     */
    public void setShortOpt(String shortOpt) {
        this.shortOpt = shortOpt;
    }

    /**
     * Returns the description of the option.
     *
     * @return The description of the option.
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the description of the option.
     *
     * @param description The description of the option.
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Returns whether the option requires an argument.
     *
     * @return True if the option requires an argument, false otherwise.
     */
    public boolean hasArg() {
        return hasArg;
    }

    /**
     * Sets whether the option requires an argument.
     *
     * @param hasArg True if the option requires an argument, false otherwise.
     */
    public void setHasArg(boolean hasArg) {
        this.hasArg = hasArg;
    }

    /**
     * Returns whether the option is mandatory.
     *
     * @return True if the option is mandatory, false otherwise.
     */
    public boolean isRequired() {
        return isRequired;
    }

    /**
     * Sets whether the option is mandatory.
     *
     * @param required True if the option is mandatory, false otherwise.
     */
    public void setRequired(boolean required) {
        isRequired = required;
    }

    /**
     * Returns the value of the option.
     *
     * @return The value of the option.
     */
    public String getValue() {
        return value;
    }

    /**
     * Sets the value of the option.
     *
     * @param value The value of the option.
     */
    public void setValue(String value) {
        this.value = value;
    }
}
