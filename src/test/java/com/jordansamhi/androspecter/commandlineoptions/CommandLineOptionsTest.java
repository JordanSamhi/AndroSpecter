package com.jordansamhi.androspecter.commandlineoptions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommandLineOptionsTest {

    private CommandLineOptions commandLineOptions;

    @BeforeEach
    void setUp() {
        commandLineOptions = CommandLineOptions.v();
        commandLineOptions.reset();
    }

    @AfterEach
    void tearDown() {
        commandLineOptions.reset();
    }

    @Test
    void testSingletonInstance() {
        CommandLineOptions anotherInstance = CommandLineOptions.v();
        assertSame(commandLineOptions, anotherInstance, "Instances should be the same");
    }

    @Test
    void testAddAndGetOptionValue() {
        CommandLineOption option = new CommandLineOption("testLong", "t", "description", true, true);
        commandLineOptions.addOption(option);
        assertNull(commandLineOptions.getOptionValue("testLong"));
        option.setValue("value");
        assertEquals("value", commandLineOptions.getOptionValue("testLong"));
    }

    @Test
    void testAppName() {
        assertNull(commandLineOptions.getAppName());
        commandLineOptions.setAppName("appName");
        assertEquals("appName", commandLineOptions.getAppName());
    }

    @Test
    void repeatedParseClearsOmittedOptionValues() {
        commandLineOptions.setAppName("app");
        CommandLineOption option = new CommandLineOption("output", "o", "output path", true, false);
        commandLineOptions.addOption(option);
        commandLineOptions.parseArgs(new String[]{"--output", "first"});
        assertEquals("first", commandLineOptions.getOptionValue("output"));
        assertTrue(commandLineOptions.hasOption("o"));

        commandLineOptions.parseArgs(new String[0]);
        assertNull(commandLineOptions.getOptionValue("output"));
        assertNull(option.getValue());
        assertFalse(commandLineOptions.hasOption("o"));
    }

    @Test
    void resetAllowsIndependentConfiguration() {
        commandLineOptions.setAppName("first");
        commandLineOptions.addOption(new CommandLineOption("input", "i", "input", true, true));
        commandLineOptions.parseArgs(new String[]{"-i", "old"});
        commandLineOptions.reset();
        assertNull(commandLineOptions.getAppName());
        assertFalse(commandLineOptions.hasOption("i"));
        commandLineOptions.setAppName("second");
        commandLineOptions.parseArgs(new String[0]);
        assertNull(commandLineOptions.getOptionValue("input"));
    }
}
