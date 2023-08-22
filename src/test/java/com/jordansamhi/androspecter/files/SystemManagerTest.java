package com.jordansamhi.androspecter.files;

import soot.SootClass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SystemManagerTest {

    private SystemManager systemManager;

    @BeforeEach
    void setUp() {
        systemManager = SystemManager.v();
    }

    @Test
    void testIsSystemClass() {
        // Test against known system classes
        assertTrue(systemManager.isSystemClass(new SootClass("android.app.Activity")));
        assertTrue(systemManager.isSystemClass(new SootClass("java.util.List")));
        assertTrue(systemManager.isSystemClass(new SootClass("com.android.SomeClass")));
        // Add more assertions based on the system classes you have
    }

    @Test
    void testIsNotSystemClass() {
        // Test against non-system classes
        assertFalse(systemManager.isSystemClass(new SootClass("com.myapp.MyActivity")));
        assertFalse(systemManager.isSystemClass(new SootClass("com.nonSystem.SomeClass")));
    }

    @Test
    void testSingleton() {
        SystemManager anotherInstance = SystemManager.v();
        assertSame(systemManager, anotherInstance);
    }

    @Test
    void testFileLoading() {
        // Assuming a valid file is returned by getFile(), check that items were loaded
        assertTrue(systemManager.items.contains("android"));
        assertTrue(systemManager.items.contains("java"));
        assertTrue(systemManager.items.contains("com.android"));
        // Add more assertions based on the items you expect to be loaded from the file
    }

    // More tests could be written depending on additional requirements and public methods.
}