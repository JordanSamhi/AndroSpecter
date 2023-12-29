package com.jordansamhi.androspecter;

import com.jordansamhi.androspecter.network.RedisManager;
import com.jordansamhi.androspecter.printers.Writer;

import java.io.File;
import java.util.concurrent.*;

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
    private String redisRoot;

    /**
     * The key used in Redis for popping an element from the set.
     */
    private String redisSpop;

    /**
     * The key used in Redis for checking the success of processing an Android app.
     */
    private String redisSuccess;

    /**
     * The maximum number of minutes that the processApp method is allowed to run for each Android app.
     */
    private int timeout;

    /**
     * The RedisManager used to interact with the Redis database.
     */
    private RedisManager rm;

    /**
     * The AndroZooUtils instance used to download the APK files.
     */
    private AndroZooUtils au;


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
        this.rm = rm;
        this.redisRoot = redisRoot;
        this.timeout = timeout;
        this.au = au;
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
     * it is logged and the process continues with the next app.
     */
    public void run() {
        String redisSpop = String.format("%s:pop", this.redisRoot);
        String redisSuccess = String.format("%s:success", this.redisRoot);
        String redisErrors = String.format("%s:errors", this.redisRoot);

        try {
            while (true) {
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

                String apkPath = this.au.getApk(sha);

                ExecutorService executor = Executors.newSingleThreadExecutor();
                Future<String> future = executor.submit(new Callable<String>() {
                    @Override
                    public String call() throws Exception {
                        processApp(apkPath);
                        return "Task completed successfully";
                    }
                });
                try {
                    String result = future.get(timeout, TimeUnit.MINUTES);
                    if (result.equals("Task completed successfully")) {
                        rm.sadd(redisSuccess, sha);
                        this.processResults();
                    } else {
                        rm.sadd(redisErrors, sha);
                        Writer.v().perror("Error in processing app");
                    }
                } catch (TimeoutException e) {
                    rm.sadd(redisErrors, sha);
                    Writer.v().perror("Timeout reached");
                } catch (ExecutionException e) {
                    rm.sadd(redisErrors, sha);
                    Writer.v().perror(String.format("An exception occurred within the task: %s", e.getMessage()));
                }
                if (new File(apkPath).delete()) {
                    Writer.v().psuccess("App deleted successfully");
                } else {
                    Writer.v().perror("Failed to delete file");
                }
            }
        } catch (Exception e) {
            Writer.v().perror(String.format("An exception occurred: %s", e.getMessage()));
        }
    }
}
