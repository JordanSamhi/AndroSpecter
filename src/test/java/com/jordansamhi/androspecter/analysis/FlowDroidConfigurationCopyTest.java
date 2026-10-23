package com.jordansamhi.androspecter.analysis;

import org.junit.jupiter.api.Test;
import soot.jimple.infoflow.InfoflowConfiguration;
import soot.jimple.infoflow.android.InfoflowAndroidConfiguration;
import soot.jimple.infoflow.android.data.CategoryDefinition;
import soot.jimple.infoflow.config.PreciseCollectionStrategy;

import static org.junit.jupiter.api.Assertions.*;

class FlowDroidConfigurationCopyTest {
    @Test
    void preservesSettingsOmittedByUpstreamMerge() {
        var original = new InfoflowAndroidConfiguration();
        original.setPathAgnosticResults(!original.getPathAgnosticResults());
        original.setMaxAliasingBases(41);
        original.setPreciseCollectionTracking(PreciseCollectionStrategy.CONSTANT_MAP_SUPPORT);
        original.setPerformConstantPropagation(!original.getPerformConstantPropagation());
        original.getSolverConfiguration().setSleepTime(73);
        original.getSolverConfiguration().setFollowReturnsPastSources(
                !original.getSolverConfiguration().isFollowReturnsPastSources());
        var copy = FlowDroidConfigurationCopy.copy(original);
        assertEquals(original.getPathAgnosticResults(), copy.getPathAgnosticResults());
        assertEquals(41, copy.getMaxAliasingBases());
        assertEquals(PreciseCollectionStrategy.CONSTANT_MAP_SUPPORT, copy.getPreciseCollectionStrategy());
        assertEquals(original.getPerformConstantPropagation(), copy.getPerformConstantPropagation());
        assertEquals(73, copy.getSolverConfiguration().getSleepTime());
        assertEquals(original.getSolverConfiguration().isFollowReturnsPastSources(),
                copy.getSolverConfiguration().isFollowReturnsPastSources());
        original.getSolverConfiguration().setSleepTime(4);
        assertEquals(73, copy.getSolverConfiguration().getSleepTime());
    }

    @Test
    void categoryMapsAndMutableKeysAreDetachedInBothDirections() {
        var original = new InfoflowAndroidConfiguration();
        var category = new CategoryDefinition("location", "Location data");
        original.getSourceSinkConfig().addSourceCategory(category, InfoflowConfiguration.CategoryMode.Include);
        original.getSourceSinkConfig().addSinkCategory(category, InfoflowConfiguration.CategoryMode.Exclude);
        var copy = FlowDroidConfigurationCopy.copy(original);
        CategoryDefinition copiedSource = copy.getSourceSinkConfig().getSourceCategories().iterator().next();
        CategoryDefinition copiedSink = copy.getSourceSinkConfig().getSinkCategories().iterator().next();
        assertNotSame(category, copiedSource);
        assertNotSame(copiedSource, copiedSink);
        category.setCategoryId("changed");
        original.getSourceSinkConfig().getSinkCategoriesAndModes().clear();
        assertEquals("location", copiedSource.getCategoryId());
        assertEquals("Location data", copiedSource.getHumanReadableDescription());
        assertEquals(InfoflowConfiguration.CategoryMode.Include,
                copy.getSourceSinkConfig().getSourceCategoriesAndModes().get(copiedSource));
        assertEquals(1, copy.getSourceSinkConfig().getSinkCategories().size());
        copiedSink.setCategoryId("copy-only");
        assertEquals("changed", category.getCategoryId());
    }

    @Test
    void callbackSettingsRejectInvalidLimitsWithoutLoadingSoot() {
        assertThrows(NullPointerException.class, () -> new CallbackOptions(true, null, 100, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new CallbackOptions(true, CallbackOptions.Analyzer.DEFAULT, -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new CallbackOptions(true, CallbackOptions.Analyzer.DEFAULT, 100, -2));
        assertEquals(new CallbackOptions(true, CallbackOptions.Analyzer.DEFAULT, 100, -1), CallbackOptions.defaults());
    }
}
