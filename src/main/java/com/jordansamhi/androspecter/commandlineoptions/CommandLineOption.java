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
 * Mutable declaration of one command-line option, including its aliases, help text,
 * argument requirement, and mandatory status. Configure a descriptor before passing it to
 * {@link CommandLineOptions#addOption(CommandLineOption)}, which snapshots the declaration.
 * Later setter calls do not reconfigure that parser. The value property supports legacy
 * consumers and is updated by that parser; prefer an immutable ParseResult for new code.
 * Descriptors perform no validation themselves and are not thread-safe.
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
     * Creates an option declaration without registering or validating it. Alias names omit
     * leading hyphens. Register the descriptor with CommandLineOptions to validate it; that
     * parser reserves the {@code h} and {@code help} aliases for its built-in help flag.
     *
     * @param longOpt long alias, such as {@code apk}; may be null when a short alias is used
     * @param shortOpt short alias, such as {@code a}; may be null when a long alias is used
     * @param description text shown in generated help; may be null
     * @param hasArg true if this option takes one argument, false for a flag
     * @param isRequired true if the option must appear unless help is requested
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
     * @return the stored long alias without leading hyphens, or null if none was assigned
     */
    public String getLongOpt() {
        return longOpt;
    }

    /**
     * Sets the long alias on this descriptor. Already registered parsers keep their copy.
     *
     * @param longOpt long alias without leading hyphens, or null to remove it
     */
    public void setLongOpt(String longOpt) {
        this.longOpt = longOpt;
    }

    /**
     * Returns the short form of the option.
     *
     * @return the stored short alias without leading hyphens, or null if none was assigned
     */
    public String getShortOpt() {
        return shortOpt;
    }

    /**
     * Sets the short alias on this descriptor. Already registered parsers keep their copy.
     *
     * @param shortOpt short alias without leading hyphens, or null to remove it
     */
    public void setShortOpt(String shortOpt) {
        this.shortOpt = shortOpt;
    }

    /**
     * Returns the description of the option.
     *
     * @return the stored help description, or null if none was assigned
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets this descriptor's help text. Already registered parsers keep their copy.
     *
     * @param description description for generated help, or null to omit it
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Returns whether this declaration takes one argument rather than being a flag.
     *
     * @return true for an option with one argument, false for a flag
     */
    public boolean hasArg() {
        return hasArg;
    }

    /**
     * Sets whether this declaration takes an argument. Already registered parsers keep their copy.
     *
     * @param hasArg true to require one argument when present, false for a flag
     */
    public void setHasArg(boolean hasArg) {
        this.hasArg = hasArg;
    }

    /**
     * Returns whether this declaration requires the option to appear in normal parses.
     *
     * @return true if the option is mandatory; built-in help bypasses this requirement
     */
    public boolean isRequired() {
        return isRequired;
    }

    /**
     * Sets whether the option is mandatory. Already registered parsers keep their copy.
     *
     * @param required true if the option must appear unless help is requested
     */
    public void setRequired(boolean required) {
        isRequired = required;
    }

    /**
     * Returns the mutable legacy value. A registered parser clears it before each parse and
     * sets it after success; callers may also replace it directly. It is not an immutable
     * record of a parse, and flags can be present while their value is null.
     *
     * @return the stored argument value, or null when no value is stored
     */
    public String getValue() {
        return value;
    }

    /**
     * Replaces the mutable legacy value without changing the option declaration, parser
     * configuration, or previously returned parse snapshots.
     *
     * @param value value to store, or null to clear it
     */
    public void setValue(String value) {
        this.value = value;
    }
}
