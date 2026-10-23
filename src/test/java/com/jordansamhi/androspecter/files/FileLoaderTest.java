package com.jordansamhi.androspecter.files;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class FileLoaderTest {
    @Test
    void loadsTrimmedUtf8EntriesAndSkipsWhitespaceAndComments() {
        FileLoader loader = new FileLoader() {
            @Override
            protected String getFile() {
                return "/file-loader-fixture.txt";
            }
        };
        assertEquals(new HashSet<>(Arrays.asList("example.package", "échantillon.paquet")), loader.items);
    }

    @Test
    void missingResourceIdentifiesItsPath() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new FileLoader() {
            @Override
            protected String getFile() {
                return "/missing-resource.txt";
            }
        });
        assertTrue(error.getMessage().contains("/missing-resource.txt"));
    }
}
