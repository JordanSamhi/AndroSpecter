package com.jordansamhi.androspecter;

import com.jordansamhi.androspecter.network.RedisManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class AndroidAppsProcessorTest {
    @TempDir
    Path directory;

    @AfterEach
    void clearInterruptStatus() {
        Thread.interrupted();
    }

    @Test
    void savesResultsBeforeMarkingSuccessAndCleansApk() throws IOException {
        Path apk = apk("first");
        StubRedis redis = new StubRedis("first");
        AndroidAppsProcessor processor = processor(redis, sha -> apk.toString(), 1000, 1000,
                path -> assertEquals(apk.toString(), path), () -> redis.events.add("results"));
        processor.run();
        Thread.interrupted();

        assertEquals(Arrays.asList("results", "success:first"), redis.events);
        assertFalse(Files.exists(apk));
    }

    @Test
    void failedResultPersistenceIsAnErrorAndStillCleansApk() throws IOException {
        Path apk = apk("first");
        StubRedis redis = new StubRedis("first");
        processor(redis, sha -> apk.toString(), 1000, 1000, path -> { }, () -> {
            throw new IllegalStateException("Cannot save results");
        }).run();
        Thread.interrupted();

        assertEquals(Collections.singletonList("error:first"), redis.events);
        assertFalse(Files.exists(apk));
    }

    @Test
    void failedAnalysisDoesNotPreventProcessingTheNextApp() throws IOException {
        Path first = apk("first");
        Path second = apk("second");
        StubRedis redis = new StubRedis("first", "second");
        processor(redis, sha -> directory.resolve(sha + ".apk").toString(), 1000, 1000, path -> {
            if (path.equals(first.toString())) {
                throw new IllegalStateException("Analysis failed");
            }
        }, () -> redis.events.add("results")).run();
        Thread.interrupted();

        assertEquals(Arrays.asList("error:first", "results", "success:second"), redis.events);
        assertFalse(Files.exists(first));
        assertFalse(Files.exists(second));
    }

    @Test
    void missingDownloadIsRecordedAndNotPassedToAnalysis() throws IOException {
        Path second = apk("second");
        StubRedis redis = new StubRedis("missing", "second");
        List<String> processed = new ArrayList<>();
        processor(redis, sha -> sha.equals("missing") ? null : second.toString(), 1000, 1000,
                processed::add, () -> { }).run();
        Thread.interrupted();

        assertEquals(Collections.singletonList(second.toString()), processed);
        assertEquals(Arrays.asList("error:missing", "success:second"), redis.events);
        assertFalse(Files.exists(second));
    }

    @Test
    void timeoutInterruptsAnalysisAndShutsDownItsExecutor() throws IOException {
        Path apk = apk("first");
        StubRedis redis = new StubRedis("first");
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Thread> analysisThread = new AtomicReference<>();
        processor(redis, sha -> apk.toString(), 500, 1000, path -> {
            analysisThread.set(Thread.currentThread());
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
        }, () -> fail("Timed-out results must not be saved")).run();
        Thread.interrupted();

        assertTrue(interrupted.get());
        assertFalse(analysisThread.get().isAlive());
        assertEquals(Collections.singletonList("error:first"), redis.events);
        assertFalse(Files.exists(apk));
    }

    @Test
    void uncooperativeTimedOutTaskStopsPollingBeforeAnotherAnalysis() throws Exception {
        Path first = apk("first");
        StubRedis redis = new StubRedis("first", "second");
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> analysisThread = new AtomicReference<>();
        AndroidAppsProcessor processor = processor(redis, sha -> first.toString(), 500, 50, path -> {
            analysisThread.set(Thread.currentThread());
            boolean released = false;
            while (!released) {
                try {
                    release.await();
                    released = true;
                } catch (InterruptedException ignored) {
                    // Simulate an analysis that does not cooperate with cancellation.
                }
            }
        }, () -> fail("Timed-out results must not be saved"));
        try {
            processor.run();
            assertEquals(Collections.singletonList("error:first"), redis.events);
            assertEquals(1, redis.pending.size(), "A second APK must not be popped while analysis is still running");
            assertTrue(analysisThread.get().isAlive());
        } finally {
            release.countDown();
            if (analysisThread.get() != null) {
                analysisThread.get().join(2000);
            }
        }
        assertFalse(analysisThread.get().isAlive());
        assertFalse(Files.exists(first), "Cleanup must run when the cancelled analysis eventually exits");
    }

    @Test
    void interruptionStopsWorkerPreservesStatusAndCleansApk() throws Exception {
        Path apk = apk("first");
        StubRedis redis = new StubRedis("first", "second");
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean statusPreserved = new AtomicBoolean();
        AtomicBoolean analysisInterrupted = new AtomicBoolean();
        AndroidAppsProcessor processor = processor(redis, sha -> apk.toString(), 10000, 1000, path -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                analysisInterrupted.set(true);
                Thread.currentThread().interrupt();
            }
        }, () -> fail("Interrupted results must not be saved"));
        Thread worker = new Thread(() -> {
            processor.run();
            statusPreserved.set(Thread.currentThread().isInterrupted());
        });
        worker.setDaemon(true);
        worker.start();
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            worker.interrupt();
            worker.join(2000);
            assertFalse(worker.isAlive());
            assertTrue(statusPreserved.get());
            assertTrue(analysisInterrupted.get());
            assertEquals(Collections.singletonList("error:first"), redis.events);
            assertEquals(1, redis.pending.size());
            assertFalse(Files.exists(apk));
        } finally {
            worker.interrupt();
            worker.join(2000);
        }
    }

    @Test
    void interruptionWhileQueueIsEmptyStopsPolling() {
        StubRedis redis = new StubRedis();
        processor(redis, sha -> null, 1000, 1000,
                path -> fail("No app expected"), () -> fail("No results expected")).run();
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(1, redis.popCalls);
    }

    private Path apk(String name) throws IOException {
        return Files.write(directory.resolve(name + ".apk"), new byte[]{1});
    }

    private AndroidAppsProcessor processor(StubRedis redis, Function<String, String> download,
                                           long timeoutMillis, long terminationWaitMillis,
                                           Consumer<String> analysis, Runnable results) {
        AndroZooUtils downloader = new AndroZooUtils("unused", directory.toString()) {
            @Override
            public String getApk(String sha256) {
                return download.apply(sha256);
            }
        };
        return new AndroidAppsProcessor(redis, downloader, "test", timeoutMillis, terminationWaitMillis) {
            @Override
            protected void processApp(String appName) {
                analysis.accept(appName);
            }

            @Override
            protected void processResults() {
                results.run();
            }
        };
    }

    private static class StubRedis extends RedisManager {
        final Queue<String> pending = new ArrayDeque<>();
        final List<String> events = new ArrayList<>();
        int popCalls;

        StubRedis(String... shas) {
            super(new Jedis());
            pending.addAll(Arrays.asList(shas));
        }

        @Override
        public String spop(String key) {
            popCalls++;
            String sha = pending.poll();
            if (sha == null) {
                Thread.currentThread().interrupt();
            }
            return sha;
        }

        @Override
        public boolean sismember(String key, String value) {
            return false;
        }

        @Override
        public void sadd(String key, String value) {
            events.add((key.endsWith(":success") ? "success:" : "error:") + value);
        }
    }
}
