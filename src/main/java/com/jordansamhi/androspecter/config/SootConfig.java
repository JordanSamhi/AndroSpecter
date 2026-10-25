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
 * Legacy FlowDroid configuration hook that modifies Soot's global {@link Options}.
 * Its public no-argument constructor creates a stateless hook; constructing it does
 * not configure Soot. Invoke it only while the caller owns the current Soot scene.
 *
 * @author Jordan Samhi
 */
public class SootConfig implements IInfoflowConfig {

    /**
     * Creates a stateless FlowDroid configuration hook without changing Soot options.
     * Supply the instance to FlowDroid's configuration API; its
     * {@link #setSootOptions(Options, InfoflowConfiguration)} callback applies the options
     * when invoked while the caller owns the Soot scene.
     */
    public SootConfig() {}

    /**
     * Enables phantom references, whole-program mode, and the call-graph phase, and
     * disables Soot file output. This implementation always modifies {@link Options#v()};
     * it ignores both supplied arguments and does not reset other existing options.
     *
     * @param options FlowDroid-supplied options; ignored by this implementation, may be null
     * @param config FlowDroid analysis configuration; ignored by this implementation, may be null
     */
    public void setSootOptions(Options options, InfoflowConfiguration config) {
        Options.v().set_allow_phantom_refs(true);
        Options.v().set_output_format(Options.output_format_none);
        Options.v().set_whole_program(true);
        Options.v().setPhaseOption("cg", "enabled:true");
    }
}
