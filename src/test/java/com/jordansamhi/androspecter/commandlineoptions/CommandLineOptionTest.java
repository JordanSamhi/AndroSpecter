package com.jordansamhi.androspecter.commandlineoptions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandLineOptionTest {

    private CommandLineOption option;

    @BeforeEach
    void setUp() {
        option = new CommandLineOption("longOption", "s", "description", true, true);
    }

    @Test
    void testLongOpt() {
        assertEquals("longOption", option.getLongOpt());
        option.setLongOpt("newLongOption");
        assertEquals("newLongOption", option.getLongOpt());
    }

    @Test
    void testShortOpt() {
        assertEquals("s", option.getShortOpt());
        option.setShortOpt("n");
        assertEquals("n", option.getShortOpt());
    }

    @Test
    void testDescription() {
        assertEquals("description", option.getDescription());
        option.setDescription("newDescription");
        assertEquals("newDescription", option.getDescription());
    }

    @Test
    void testHasArg() {
        assertTrue(option.hasArg());
        option.setHasArg(false);
        assertFalse(option.hasArg());
    }

    @Test
    void testIsRequired() {
        assertTrue(option.isRequired());
        option.setRequired(false);
        assertFalse(option.isRequired());
    }

    @Test
    void testValue() {
        assertNull(option.getValue());
        option.setValue("value");
        assertEquals("value", option.getValue());
    }
}