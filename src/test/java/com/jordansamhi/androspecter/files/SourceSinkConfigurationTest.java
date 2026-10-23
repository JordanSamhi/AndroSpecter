package com.jordansamhi.androspecter.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SourceSinkConfigurationTest {
    private static final String SOURCE = "<example.Source: java.lang.String read()>";
    private static final String SINK = "<example.Sink: void write(java.lang.String)>";

    @Test
    void immutableAndIndependentFromInputSetsAndBuilders() {
        Set<String> sources = new HashSet<>(Set.of(SOURCE));
        SourceSinkConfiguration configuration = new SourceSinkConfiguration(sources, Set.of());
        sources.clear();
        assertEquals(Set.of(SOURCE), configuration.sources());
        assertThrows(UnsupportedOperationException.class, () -> configuration.sources().clear());
        var builder = SourceSinkConfiguration.builder().include(configuration);
        var first = builder.build();
        builder.sink(SINK);
        assertTrue(first.sinks().isEmpty());
        assertEquals(Set.of(SINK), builder.build().sinks());
    }

    @Test
    void flowdroidObjectsAreFreshAndCannotChangeConfiguration() {
        var configuration = SourceSinkConfiguration.builder().source(SOURCE).sink(SINK).build();
        var first = configuration.toSourceMethods();
        var second = configuration.toSourceMethods();
        assertNotSame(first.iterator().next(), second.iterator().next());
        first.clear();
        assertEquals(Set.of(SOURCE), configuration.sources());
        assertEquals(1, configuration.toSourceMethods().size());
    }

    @Test
    void fileLoadingDoesNotImplicitlyIncludeDefaults(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("sources.txt");
        Files.writeString(file, "SOURCE|" + SOURCE + "\nSINK|" + SINK + "\n");
        var configuration = SourceSinkConfiguration.fromFile(file);
        assertEquals(Set.of(SOURCE), configuration.sources());
        assertEquals(Set.of(SINK), configuration.sinks());
        assertFalse(SourceSinkConfiguration.bundledDefaults().sources().isEmpty());
    }

    @Test
    void validatesAndNormalizesMethodSignatures() {
        var configuration = SourceSinkConfiguration.builder()
                .source(" <example.Source: void run(int, java.lang.String)> ").build();
        assertEquals(Set.of("<example.Source: void run(int,java.lang.String)>"), configuration.sources());
        assertThrows(IllegalArgumentException.class,
                () -> SourceSinkConfiguration.builder().sink("not-a-method").build());
    }

    @Test
    void legacyManagersCanNowOwnIndependentState() {
        SourcesSinksManager first = new SourcesSinksManager();
        SourcesSinksManager second = new SourcesSinksManager();
        first.getSources().clear();
        assertFalse(second.getSources().isEmpty());
    }
}
