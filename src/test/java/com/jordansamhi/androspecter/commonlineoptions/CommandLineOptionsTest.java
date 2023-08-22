package com.jordansamhi.androspecter.commonlineoptions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommandLineOptionsTest {

    private CommandLineOptions commandLineOptions;

    @BeforeEach
    void setUp() {
        commandLineOptions = CommandLineOptions.v();
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
}
