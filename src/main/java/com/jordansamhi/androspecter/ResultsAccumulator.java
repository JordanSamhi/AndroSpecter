package com.jordansamhi.androspecter;

import java.util.HashMap;
import java.util.StringJoiner;

/**
 * Singleton class that accumulates results metrics.
 */
public class ResultsAccumulator {

    /**
     * Singleton instance.
     */
    private static ResultsAccumulator instance;

    /**
     * Application name.
     */
    private String appName;

    /**
     * Map to hold various metrics and their counts.
     */
    private final HashMap<String, Integer> metrics;

    /**
     * Private constructor to enforce singleton usage.
     */
    private ResultsAccumulator() {
        this.appName = "";
        this.metrics = new HashMap<>();
    }

    /**
     * Method to get singleton instance.
     *
     * @return singleton instance of ResultsAccumulator
     */
    public static ResultsAccumulator v() {
        if (instance == null) {
            instance = new ResultsAccumulator();
        }
        return instance;
    }

    /**
     * Getter for application name.
     *
     * @return application name
     */
    public String getAppName() {
        return appName;
    }

    /**
     * Setter for application name.
     *
     * @param appName the new application name
     */
    public void setAppName(String appName) {
        this.appName = appName;
    }

    /**
     * Method to increment a specific metric by 1. If the metric does not exist, it is created and set to 1.
     *
     * @param metric the metric to increment
     */
    public void incrementMetric(String metric) {
        metrics.put(metric, metrics.getOrDefault(metric, 0) + 1);
    }

    /**
     * Method to get current count of a specific metric.
     *
     * @param metric the metric to get
     * @return the count of the metric, or 0 if the metric does not exist
     */
    public int getMetric(String metric) {
        return metrics.getOrDefault(metric, 0);
    }

    /**
     * Method to print all the metrics in a vector form.
     */
    public void printVectorResults() {
        System.out.println(getVectorResults());
    }

    /**
     * Method to get a string representation of the metrics in a vector form.
     *
     * @return a string of metrics in a vector form
     */
    public String getVectorResults() {
        StringJoiner sj = new StringJoiner(",");
        sj.add(this.getAppName());

        for (String metric : metrics.keySet()) {
            sj.add(String.valueOf(metrics.get(metric)));
        }

        return sj.toString();
    }

    /**
     * Method to print all the metrics in a readable format.
     */
    public void printResults() {
        System.out.println("Results:");
        System.out.printf(" - App name: %s%n", this.getAppName());

        for (String metric : metrics.keySet()) {
            System.out.printf(" - Number of %s: %s%n", metric, metrics.get(metric));
        }
    }
}