package com.jordansamhi.utils.printers;

/*-
 * #%L
 * Utils
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

public class Writer {

    private static Writer instance;

    public Writer() {
    }

    public static Writer v() {
        if (instance == null) {
            instance = new Writer();
        }
        return instance;
    }

    /**
     * Prints an error message to the console, with a prefix indicating an error.
     *
     * @param s the error message to print
     */
    public void perror(String s) {
        this.print('x', s);
    }


    /**
     * Prints a success message to the console, with a prefix indicating success.
     *
     * @param s the success message to print
     */
    public void psuccess(String s) {
        this.print('✓', s);
    }

    /**
     * Prints a warning message to the console, with a prefix indicating a warning.
     *
     * @param s the warning message to print
     */
    public void pwarning(String s) {
        this.print('!', s);
    }

    /**
     * Prints an informational message to the console, with a prefix indicating information.
     *
     * @param s the informational message to print
     */
    public void pinfo(String s) {
        this.print('*', s);
    }

    /**
     * Prints a message to the console, with the given prefix character.
     *
     * @param c the prefix character to print before the message
     * @param s the message to print
     */
    private void print(char c, String s) {
        System.out.printf("[%c] %s%n", c, s);
    }
}
