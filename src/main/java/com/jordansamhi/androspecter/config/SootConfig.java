package com.jordansamhi.androspecter.config;

import soot.jimple.infoflow.InfoflowConfiguration;
import soot.jimple.infoflow.config.IInfoflowConfig;
import soot.options.Options;

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
 * Implementation of the {@link IInfoflowConfig} interface.
 * This class sets the Soot options required for the analysis, based on the given configuration.
 */
public class SootConfig implements IInfoflowConfig {

    /**
     * Sets the Soot options required for the analysis, based on the given configuration.
     *
     * @param options the Soot options to set
     * @param config  the analysis configuration
     */
    public void setSootOptions(Options options, InfoflowConfiguration config) {
        Options.v().set_process_multiple_dex(true);
        Options.v().set_allow_phantom_refs(true);
        Options.v().set_output_format(Options.output_format_none);
        Options.v().set_whole_program(true);
        Options.v().setPhaseOption("cg", "enabled:true");
    }
}
