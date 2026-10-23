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

    @Test
    void helpReturnsDataWithoutRequiringMandatoryOptionsOrExiting() {
        CommandLineOptions parser = new CommandLineOptions("inspect");
        parser.addOption(new CommandLineOption("apk", "a", "APK path", true, true));
        parser.addOption(new CommandLineOption("verbose", "v", "Detailed output", false, false));
        CommandLineOptions.ParseResult result = parser.parse("--verbose", "--help");
        assertTrue(result.helpRequested());
        assertTrue(result.hasOption("v"));
        assertTrue(result.helpText().contains("--apk"));
        assertTrue(result.helpText().contains("--help"));
        assertTrue(result.helpText().contains("inspect"));
    }

    @Test
    void invalidArgumentsThrowAndDoNotRetainAPreviousResult() {
        CommandLineOptions parser = new CommandLineOptions("inspect");
        parser.addOption(new CommandLineOption("apk", "a", "APK path", true, true));
        parser.parse("--apk", "first.apk");
        assertThrows(CommandLineOptions.ValidationException.class, () -> parser.parse());
        assertFalse(parser.hasOption("apk"));
        assertNull(parser.getOptionValue("apk"));
        assertThrows(CommandLineOptions.ValidationException.class, () -> parser.parse("--unknown"));
        assertThrows(CommandLineOptions.ValidationException.class, () -> parser.parse("--apk"));
        assertThrows(IllegalStateException.class, () -> new CommandLineOptions().parse());
    }

    @Test
    void returnedSnapshotsAndParserInstancesAreIndependent() {
        CommandLineOptions first = new CommandLineOptions("first");
        first.addOption(new CommandLineOption("output", "o", "Output path", true, false));
        CommandLineOptions second = new CommandLineOptions("second");
        CommandLineOptions.ParseResult result = first.parse("-o", "first.txt", "argument");
        first.parse("-o", "second.txt");
        second.parse("--help");
        first.reset();
        assertEquals("first.txt", result.getOptionValue("output"));
        assertEquals("first.txt", result.getOptionValue("o"));
        assertEquals(java.util.Collections.singletonList("argument"), result.arguments());
        assertThrows(UnsupportedOperationException.class, () -> result.arguments().add("other"));
        assertFalse(result.helpRequested());
    }

    @Test
    void helpTextUsedAsAnOptionValueOrPositionalArgumentDoesNotRequestHelp() {
        CommandLineOptions parser = new CommandLineOptions("inspect");
        parser.addOption(new CommandLineOption("input", "i", "Input", true, false));
        CommandLineOptions.ParseResult value = parser.parse("--input=--help");
        assertFalse(value.helpRequested());
        assertEquals("--help", value.getOptionValue("input"));
        CommandLineOptions.ParseResult positional = parser.parse("--", "--help");
        assertFalse(positional.helpRequested());
        assertEquals(java.util.Collections.singletonList("--help"), positional.arguments());
    }

    @Test
    void legacyHelpWrapperReturnsControlAndReservedOptionsCannotBeOverwritten() {
        CommandLineOptions parser = new CommandLineOptions("inspect");
        parser.parseArgs(new String[]{"--help"});
        assertTrue(parser.isHelpRequested());
        assertThrows(IllegalArgumentException.class,
                () -> parser.addOption(new CommandLineOption("help", "x", "Override", false, false)));
        assertThrows(IllegalArgumentException.class,
                () -> parser.addOption(new CommandLineOption("other", "h", "Override", false, false)));
    }

    @Test
    void repeatedOptionsRetainCommonsCliFirstValueSemantics() {
        CommandLineOptions parser = new CommandLineOptions("inspect");
        parser.addOption(new CommandLineOption("input", "i", "Input", true, false));
        CommandLineOptions.ParseResult result = parser.parse("-i", "first", "--input", "second");
        assertEquals("first", result.getOptionValue("input"));
        assertEquals("first", parser.getOptionValue("input"));
    }
}
