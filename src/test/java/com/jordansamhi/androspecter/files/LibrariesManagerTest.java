package com.jordansamhi.androspecter.files;

import org.junit.jupiter.api.Test;
import soot.SootClass;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class LibrariesManagerTest {
    @Test
    void concurrentLibraryQueriesRemainConsistent() {
        LibrariesManager manager = LibrariesManager.v();
        assertTrue(IntStream.range(0, 128).parallel().allMatch(i ->
                manager.isLibrary(new SootClass("pro.cryptoevil.Concurrent" + i))));
        assertTrue(IntStream.range(0, 128).parallel().allMatch(i ->
                manager.isLibrary(new SootClass("pro.cryptoevil.Concurrent" + i))));
        assertFalse(manager.isLibrary(new SootClass("pro.cryptoevilish.Application")));
    }

    @Test
    void renamedClassesAreClassifiedByTheirCurrentName() {
        LibrariesManager manager = LibrariesManager.v();
        SootClass sc = new SootClass("pro.cryptoevil.Renamed");
        assertTrue(manager.isLibrary(sc));
        sc.setName("example.Application");
        assertFalse(manager.isLibrary(sc));
    }
}
