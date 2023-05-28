package com.jordansamhi.androspecter.files;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;

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
 * Abstract class to load a file into memory and extract relevant items into a set.
 */
public abstract class FileLoader {
	protected Set<String> items;

	protected FileLoader() {
		this.items = new HashSet<>();
		this.loadFile(this.getFile());
	}

	protected abstract String getFile();

	/**
	 * Loads a file into memory and extracts relevant items into a list.
	 *
	 * @param file the path to the file to load into memory
	 */
	protected void loadFile(String file) {
		InputStream fis;
		BufferedReader br;
		String line;
		try {
			fis = this.getClass().getResourceAsStream(file);
			br = new BufferedReader(new InputStreamReader(fis));
			while ((line = br.readLine()) != null)   {
				if(!line.startsWith("#") && !line.isEmpty()) {
					this.items.add(line);
				}
			}
			br.close();
			fis.close();
		} catch (IOException e) {
			System.err.println(e.getMessage());
		}
	}
}
