package com.jordansamhi.androspecter;

import com.jordansamhi.androspecter.network.RedisManager;
import com.jordansamhi.androspecter.printers.Writer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This abstract class defines the structure and flow of an Android application processing framework.
 * <p>
 * The AndroidAppProcessor works by fetching Android application's SHA256 from a Redis instance,
 * downloading the corresponding APK file using AndroZoo service, processing the APK file as per
 * the logic defined in the abstract method {@link #processApp(String)}, and then removing the APK file.
 * <p>
 * The main method is {@link #run()} which continuously checks for new SHA256 from Redis, and starts the
 * process for each new SHA256 found. If no new SHA256 is found, it waits for 30 seconds before checking again.
 * <p>
 * Specific processing logic needs to be provided by subclasses by implementing the abstract method {@link #processApp(String)}.
 * <p>
 * This class can be used as the base for creating Android application analysis, testing or data extraction frameworks.
 *
 * @author <a href="https://jordansamhi.com">Jordan Samhi</a>
 */
public abstract class AndroidAppsProcessor {

    /**
     * The root key used in Redis to store and retrieve information related to the processing of Android apps.
     */
    private final String redisRoot;

    /**
     * The maximum time that processApp may run, and the grace period for cancellation.
     */
    private final long timeoutMillis;
    private final long terminationWaitMillis;

    /**
     * The RedisManager used to interact with the Redis database.
     */
    private final RedisManager rm;

    /**
     * The AndroZooUtils instance used to download the APK files.
     */
    private final AndroZooUtils au;


    /**
     * Constructs a new AndroidAppProcessor instance with the specified RedisManager, AndroZooUtils, redisRoot, and timeout.
     * <p>
     * The RedisManager is used to interact with the Redis database, retrieving the SHA-256 hashes of the Android apps.
     * The AndroZooUtils instance is used to download the APK file corresponding to the given SHA-256 hash.
     * The redisRoot is a key that is used in Redis to store and retrieve information related to the processing of Android apps.
     * The timeout parameter specifies the maximum number of minutes that the processApp method is allowed to run for each Android app.
     *
     * @param rm        the RedisManager used to interact with the Redis database.
     * @param au        the AndroZooUtils instance used to download the APK files.
     * @param redisRoot the root key used in Redis to store and retrieve information related to the processing of Android apps.
     * @param timeout   the maximum number of minutes that the processApp method is allowed to run for each Android app.
     */

    public AndroidAppsProcessor(RedisManager rm, AndroZooUtils au, String redisRoot, int timeout) {
        this(rm, au, redisRoot, TimeUnit.MINUTES.toMillis(timeout), 5000);
    }

    AndroidAppsProcessor(RedisManager rm, AndroZooUtils au, String redisRoot,
                         long timeoutMillis, long terminationWaitMillis) {
        if (timeoutMillis <= 0 || terminationWaitMillis <= 0) {
            throw new IllegalArgumentException("Timeout and cancellation grace period must be positive");
        }
        this.rm = Objects.requireNonNull(rm, "rm");
        this.redisRoot = Objects.requireNonNull(redisRoot, "redisRoot");
        this.timeoutMillis = timeoutMillis;
        this.terminationWaitMillis = terminationWaitMillis;
        this.au = Objects.requireNonNull(au, "au");
    }

    /**
     * Processes the Android application with the given name.
     * <p>
     * This method is expected to be implemented in any subclass of AndroidAppProcessor.
     * It defines the specific operations that should be performed on each Android application.
     * The operations could include analysis, data extraction, testing, etc.
     * <p>
     * The Android application is specified by its name, which is expected to be the path to the APK file.
     *
     * @param appName the path to the Android application's APK file.
     */

    protected abstract void processApp(String appName);

    /**
     * Processes the results of the processApp method.
     * <p>
     * This method is expected to be implemented in any subclass of AndroidAppProcessor.
     * It defines the specific operations that should be performed on the results obtained from the processApp method.
     */
    protected abstract void processResults();


    /**
     * Begins the process of fetching, processing, and deleting Android apps.
     * <p>
     * This method retrieves the SHA256 of an Android app from a Redis manager,
     * downloads the APK file using the AndroZoo manager, processes the app by invoking
     * the processApp method (which is to be implemented in a subclass), and finally deletes the APK file.
     * <p>
     * The process runs in an infinite loop, making the method ideal for use in a background service.
     * If the Redis manager does not provide a SHA256, the method waits for 30 seconds before trying again.
     * <p>
     * The processing of each app is performed in its own thread, with a timeout. If the processing
     * takes longer than the timeout, it is cancelled. If any other exception occurs during the processing,
     * it is logged and the process continues with the next app. If cancellation does not stop
     * an analysis within five seconds, this worker stops polling to avoid overlapping analyses.
     * The caller retains ownership of the Redis manager and should close it when finished.
     */
    public void run() {
        String redisSpop = String.format("%s:pop", this.redisRoot);
        String redisSuccess = String.format("%s:success", this.redisRoot);
        String redisErrors = String.format("%s:errors", this.redisRoot);

        try {
            while (!Thread.currentThread().isInterrupted()) {
                String sha = rm.spop(redisSpop);
                if (sha == null) {
                    Writer.v().pwarning("No SHA received, sleeping for 30 sec...");
                    Thread.sleep(30000);
                    continue;
                }
                Writer.v().psuccess(String.format("SHA well received: %s", sha));

                if (rm.sismember(redisSuccess, sha)) {
                    Writer.v().pwarning(String.format("Skipping app %s since already successfully processed", sha));
                    continue;
                }

                if (!processOneApp(sha, redisSuccess, redisErrors)) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Writer.v().pwarning("App processing interrupted; stopping worker");
        } catch (Exception e) {
            Writer.v().perror(String.format("An exception occurred: %s", e.getMessage()));
        }
    }

    private boolean processOneApp(String sha, String redisSuccess, String redisErrors)
            throws InterruptedException {
        String apkPath = null;
        ExecutorService executor = null;
        Future<?> future = null;
        AtomicBoolean cancelled = new AtomicBoolean();
        boolean terminated = true;
        try {
            apkPath = this.au.getApk(sha);
            if (apkPath == null || !Files.isRegularFile(Paths.get(apkPath))
                    || !Files.isReadable(Paths.get(apkPath)) || Files.size(Paths.get(apkPath)) == 0) {
                throw new IOException("No readable APK was downloaded for " + sha);
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            final String downloadedApk = apkPath;
            executor = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "androspecter-analysis");
                thread.setDaemon(true);
                return thread;
            });
            future = executor.submit(() -> {
                try {
                    processApp(downloadedApk);
                } finally {
                    // An uncooperative task may finish after the worker has stopped polling.
                    if (cancelled.get()) {
                        deleteApk(downloadedApk);
                    }
                }
            });
            future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            processResults();
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            rm.sadd(redisSuccess, sha);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelled.set(true);
            if (future != null) {
                future.cancel(true);
            }
            rm.sadd(redisErrors, sha);
            throw e;
        } catch (Exception e) {
            cancelled.set(true);
            if (future != null) {
                future.cancel(true);
            }
            rm.sadd(redisErrors, sha);
            Writer.v().perror(e instanceof TimeoutException
                    ? "Timeout reached for " + sha
                    : "Error processing " + sha + ": " + e.getMessage());
        } finally {
            if (executor != null) {
                cancelled.set(true);
                if (future != null && !future.isDone()) {
                    future.cancel(true);
                }
                executor.shutdownNow();
                terminated = awaitTermination(executor);
            }
            if (terminated) {
                deleteApk(apkPath);
            } else {
                Writer.v().perror("Analysis did not stop after cancellation; stopping worker to avoid "
                        + "overlapping analyses. APK cleanup will finish when the analysis exits.");
            }
        }
        return terminated;
    }

    private boolean awaitTermination(ExecutorService executor) {
        boolean interrupted = Thread.interrupted();
        try {
            return executor.awaitTermination(terminationWaitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            interrupted = true;
            return false;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void deleteApk(String apkPath) {
        if (apkPath == null) {
            return;
        }
        try {
            Path apk = Paths.get(apkPath);
            if (Files.isRegularFile(apk) && Files.deleteIfExists(apk)) {
                Writer.v().psuccess("App deleted successfully");
            }
        } catch (IOException e) {
            Writer.v().perror("Failed to delete APK: " + apkPath);
        }
    }
}
