package com.jordansamhi.androspecter.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soot.jimple.infoflow.InfoflowConfiguration;
import soot.options.Options;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SootConfigTest {

    private SootConfig sootConfig;
    private Options options;
    private InfoflowConfiguration infoflowConfiguration;

    @BeforeEach
    void setUp() {
        sootConfig = new SootConfig();
        options = Options.v();
        infoflowConfiguration = new InfoflowConfiguration();
    }

    @Test
    void testSetSootOptions() {
        // Resetting the Soot Options
        options.set_process_multiple_dex(false);
        options.set_allow_phantom_refs(false);
        options.set_output_format(Options.output_format_dex);
        options.set_whole_program(false);

        // Calling setSootOptions method
        sootConfig.setSootOptions(options, infoflowConfiguration);

        // Asserting the Options have been set correctly
        assertTrue(options.process_multiple_dex());
        assertTrue(options.allow_phantom_refs());
        assertEquals(Options.output_format_none, options.output_format());
        assertTrue(options.whole_program());
    }
}
