package com.jordansamhi.androspecter;

import java.util.SortedMap;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Instance-owned metrics. Prefer a new instance for each analysis. Methods are
 * synchronized; snapshots contain no live Soot objects and may be retained after
 * their analysis closes.
 *
 * @author <a href="https://jordansamhi.com">Jordan Samhi</a>
 */
public class ResultsAccumulator {
    private static final Logger LOG = LoggerFactory.getLogger(ResultsAccumulator.class);

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
     * Creates an accumulator with an empty application label and no metrics.
     * Counters are caller-managed; this class does not collect analysis events automatically.
     */
    public ResultsAccumulator() {
        this.appName = "";
        this.metrics = new TreeMap<>();
    }

    /**
     * Returns the shared legacy accumulator. Call {@link #reset()} between apps
     * when using this instance.
     *
     * @return singleton instance of ResultsAccumulator
     * @deprecated Create an accumulator per analysis or use the session's metrics.
     */
    @Deprecated
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
     * Returns the caller-assigned application label.
     *
     * @return non-null label, initially empty and reset to empty by {@link #reset()}
     */
    public synchronized String getAppName() {
        return appName;
    }

    /**
     * Setter for application name.
     *
     * @param appName the new application name
     * @throws NullPointerException if {@code appName} is null
     */
    public synchronized void setAppName(String appName) {
        this.appName = Objects.requireNonNull(appName, "appName");
    }

    /**
     * Method to increment a specific metric by 1. If the metric does not exist, it is created and set to 1.
     *
     * @param metric the metric to increment
     * @throws NullPointerException if {@code metric} is null
     */
    public synchronized void incrementMetric(String metric) {
        metrics.put(metric, metrics.getOrDefault(metric, 0) + 1);
    }

    /**
     * Method to get current count of a specific metric.
     *
     * @param metric the metric to get
     * @return the count of the metric, or 0 if the metric does not exist
     * @throws NullPointerException if {@code metric} is null
     */
    public synchronized int getMetric(String metric) {
        return metrics.getOrDefault(metric, 0);
    }

    /**
     * Method to set a specific metric to a certain value.
     *
     * @param metric the metric to set
     * @param value value to set; negative values are accepted
     * @throws NullPointerException if {@code metric} is null
     */
    public synchronized void setMetric(String metric, int value) {
        metrics.put(metric, value);
    }


    /**
     * Logs {@link #getVectorResults()} at SLF4J INFO level. The caller chooses
     * the logging provider and output destination.
     */
    public synchronized void printVectorResults() {
        LOG.info("{}", getVectorResults());
    }

    /**
     * Returns the application name followed by metric values sorted by metric name.
     * The same metric names therefore produce the same column order across runs.
     * The label is CSV-escaped when needed. No header or trailing newline is included;
     * keep metric sets consistent between rows, or use {@link #snapshot()} to retain names.
     *
     * @return a CSV row containing the application label followed by the sorted metric values
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
     * Logs the application name and metrics in ascending metric-name order at
     * SLF4J INFO level. The caller chooses the logging provider and destination.
     */
    public synchronized void printResults() {
        LOG.info("Results for {}: {}", appName, metrics);
    }

    /**
     * Copies the current application name and metrics into a detached value.
     * Subsequent increments or resets do not change the returned snapshot.
     *
     * @return an immutable snapshot with metrics ordered alphabetically by name
     */
    public synchronized Snapshot snapshot() {
        return new Snapshot(appName, metrics);
    }

    /**
     * Immutable metrics suitable for retention or serialization after analysis.
     *
     * @param appName application label, possibly empty but never null
     * @param metrics detached, unmodifiable map ordered alphabetically by metric name;
     *                snapshots taken from an accumulator contain non-null values, but direct
     *                construction permits null values and preserves them
     */
    public record Snapshot(String appName, Map<String, Integer> metrics) {
        /**
         * Copies the supplied metrics into an unmodifiable sorted map.
         *
         * @param appName non-null application label
         * @param metrics non-null metric map with non-null keys; caller mutation is not retained
         * @throws NullPointerException if the application name, map, or a key is null
         */
        public Snapshot {
            Objects.requireNonNull(appName, "appName");
            metrics = Collections.unmodifiableMap(new TreeMap<>(metrics));
        }
    }
}
