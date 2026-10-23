package com.jordansamhi.androspecter;

import java.util.SortedMap;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * Singleton class that accumulates results metrics.
 *
 * @author <a href="https://jordansamhi.com">Jordan Samhi</a>
 */
public class ResultsAccumulator {

    /**
     * Singleton instance.
     */
    private static final ResultsAccumulator instance = new ResultsAccumulator();

    /**
     * Application name.
     */
    private String appName;

    /**
     * Map to hold various metrics and their counts.
     */
    private final SortedMap<String, Integer> metrics;

    /**
     * Private constructor to enforce singleton usage.
     */
    private ResultsAccumulator() {
        this.appName = "";
        this.metrics = new TreeMap<>();
    }

    /**
     * Method to get singleton instance.
     *
     * @return singleton instance of ResultsAccumulator
     */
    public static ResultsAccumulator v() {
        return instance;
    }

    /**
     * Clears the application name and metrics before collecting another application's results.
     */
    public synchronized void reset() {
        appName = "";
        metrics.clear();
    }

    /**
     * Getter for application name.
     *
     * @return application name
     */
    public synchronized String getAppName() {
        return appName;
    }

    /**
     * Setter for application name.
     *
     * @param appName the new application name
     */
    public synchronized void setAppName(String appName) {
        this.appName = appName;
    }

    /**
     * Method to increment a specific metric by 1. If the metric does not exist, it is created and set to 1.
     *
     * @param metric the metric to increment
     */
    public synchronized void incrementMetric(String metric) {
        metrics.put(metric, metrics.getOrDefault(metric, 0) + 1);
    }

    /**
     * Method to get current count of a specific metric.
     *
     * @param metric the metric to get
     * @return the count of the metric, or 0 if the metric does not exist
     */
    public synchronized int getMetric(String metric) {
        return metrics.getOrDefault(metric, 0);
    }

    /**
     * Method to set a specific metric to a certain value.
     *
     * @param metric the metric to set
     * @param value  the value to set
     */
    public synchronized void setMetric(String metric, int value) {
        metrics.put(metric, value);
    }


    /**
     * Method to print all the metrics in a vector form.
     */
    public synchronized void printVectorResults() {
        System.out.println(getVectorResults());
    }

    /**
     * Returns the application name followed by metric values sorted by metric name.
     * The same metric names therefore produce the same column order across runs.
     *
     * @return a string of metrics in a vector form
     */
    public synchronized String getVectorResults() {
        StringJoiner sj = new StringJoiner(",");
        sj.add(csvField(this.getAppName()));

        for (String metric : metrics.keySet()) {
            sj.add(String.valueOf(metrics.get(metric)));
        }

        return sj.toString();
    }

    private String csvField(String value) {
        if (value != null && (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r"))) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * Prints all metrics in ascending metric-name order.
     */
    public synchronized void printResults() {
        System.out.println("Results:");
        System.out.printf(" - App name: %s%n", this.getAppName());

        for (String metric : metrics.keySet()) {
            System.out.printf(" - Number of %s: %s%n", metric, metrics.get(metric));
        }
    }
}
