package com.jordansamhi.androspecter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TmpFolderTest {

    @Test
    void testSingletonInstance() {
        TmpFolder instance1 = TmpFolder.v();
        TmpFolder instance2 = TmpFolder.v();
        assertSame(instance1, instance2, "Instances should be the same as TmpFolder is a singleton");
    }

    @Test
    void testGetTemporaryDirectory() {
        TmpFolder tmpFolder = TmpFolder.v();
        String expectedTmpDir = System.getProperty("java.io.tmpdir");
        String actualTmpDir = tmpFolder.get();
        assertEquals(expectedTmpDir, actualTmpDir, "Temporary directory should match the system property");
    }
}