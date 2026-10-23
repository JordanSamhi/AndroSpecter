package com.jordansamhi.androspecter.analysis;

import soot.jimple.infoflow.android.InfoflowAndroidConfiguration;
import soot.jimple.infoflow.android.data.CategoryDefinition;

/** Copy support audited against the pinned FlowDroid 2.15.1 configuration classes. */
final class FlowDroidConfigurationCopy {
    private FlowDroidConfigurationCopy() {
    }

    static InfoflowAndroidConfiguration copy(InfoflowAndroidConfiguration source) {
        InfoflowAndroidConfiguration target = new InfoflowAndroidConfiguration();
        copyInto(source, target);
        return target;
    }

    static void copyInto(InfoflowAndroidConfiguration source, InfoflowAndroidConfiguration target) {
        target.merge(source);
        // Upstream merge omits these instance settings in 2.15.1.
        target.setPathAgnosticResults(source.getPathAgnosticResults());
        target.setMaxAliasingBases(source.getMaxAliasingBases());
        target.setPreciseCollectionTracking(source.getPreciseCollectionStrategy());
        target.setPerformConstantPropagation(source.getPerformConstantPropagation());
        target.getSolverConfiguration().setSleepTime(source.getSolverConfiguration().getSleepTime());
        target.getSolverConfiguration().setFollowReturnsPastSources(
                source.getSolverConfiguration().isFollowReturnsPastSources());

        // Upstream merges the maps but shares their mutable CategoryDefinition keys.
        var sourceSettings = source.getSourceSinkConfig();
        var targetSettings = target.getSourceSinkConfig();
        targetSettings.getSourceCategoriesAndModes().clear();
        sourceSettings.getSourceCategoriesAndModes().forEach((category, mode) ->
                targetSettings.addSourceCategory(copyCategory(category), mode));
        targetSettings.getSinkCategoriesAndModes().clear();
        sourceSettings.getSinkCategoriesAndModes().forEach((category, mode) ->
                targetSettings.addSinkCategory(copyCategory(category), mode));
    }

    private static CategoryDefinition copyCategory(CategoryDefinition category) {
        return new CategoryDefinition(category.getCategoryId(), category.getHumanReadableDescription());
    }
}
