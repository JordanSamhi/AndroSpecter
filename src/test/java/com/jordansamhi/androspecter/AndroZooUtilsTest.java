package com.jordansamhi.androspecter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class AndroZooUtilsTest {
    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @TempDir
    Path directory;

    @Test
    void successfulDownloadClosesResourcesAndSetsBoundedTimeouts() throws Exception {
        TrackingInputStream input = new TrackingInputStream(new byte[]{1, 2, 3});
        StubConnection connection = new StubConnection(input, 3);
        String result = downloader(connection).getApk(SHA);

        assertEquals(directory.resolve(SHA + ".apk").toString(), result);
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(directory.resolve(SHA + ".apk")));
        assertTrue(input.closed);
        assertTrue(connection.disconnected);
        assertTrue(connection.getConnectTimeout() > 0);
        assertTrue(connection.getReadTimeout() > 0);
        assertEquals(1, fileCount());
    }

    @Test
    void truncatedDownloadLeavesNoApkOrPartialFile() throws Exception {
        TrackingInputStream input = new TrackingInputStream(new byte[]{1});
        StubConnection connection = new StubConnection(input, 3);
        assertNull(downloader(connection).getApk(SHA));
        assertTrue(input.closed);
        assertTrue(connection.disconnected);
        assertEquals(0, fileCount());
    }

    @Test
    void readFailureClosesInputAndRemovesPartialDownload() throws Exception {
        TrackingInputStream input = new TrackingInputStream(new byte[]{1}) {
            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                throw new IOException("Simulated network failure");
            }
        };
        StubConnection connection = new StubConnection(input, -1);
        assertNull(downloader(connection).getApk(SHA));
        assertTrue(input.closed);
        assertTrue(connection.disconnected);
        assertEquals(0, fileCount());
    }

    @Test
    void httpErrorClosesErrorBodyAndDisconnects() throws Exception {
        TrackingInputStream error = new TrackingInputStream(new byte[]{1});
        StubConnection connection = new StubConnection(null, 0);
        connection.status = HttpURLConnection.HTTP_NOT_FOUND;
        connection.error = error;
        assertNull(downloader(connection).getApk(SHA));
        assertTrue(error.closed);
        assertTrue(connection.disconnected);
        assertEquals(0, fileCount());
    }

    @Test
    void preservesExistingDownloadWithoutOpeningNetworkConnection() throws Exception {
        Path existing = directory.resolve(SHA + ".apk");
        Files.write(existing, new byte[]{9, 8});
        AndroZooUtils downloader = new AndroZooUtils("key", directory.toString()) {
            @Override
            protected HttpURLConnection openConnection(URL url) {
                fail("An existing download must not be overwritten");
                return null;
            }
        };
        assertEquals(existing.toString(), downloader.getApk(SHA));
        assertArrayEquals(new byte[]{9, 8}, Files.readAllBytes(existing));
    }

    @Test
    void doesNotOverwriteDestinationCreatedDuringDownload() throws Exception {
        Path existing = directory.resolve(SHA + ".apk");
        TrackingInputStream input = new TrackingInputStream(new byte[]{1});
        StubConnection connection = new StubConnection(input, 1) {
            @Override
            public InputStream getInputStream() throws IOException {
                Files.write(existing, new byte[]{9});
                return super.getInputStream();
            }
        };
        assertNull(downloader(connection).getApk(SHA));
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(existing));
        assertTrue(input.closed);
        assertTrue(connection.disconnected);
        assertEquals(1, fileCount());
    }

    @Test
    void alreadyInterruptedCallerDoesNotConnectOrReturnCachedApk() throws Exception {
        Path existing = directory.resolve(SHA + ".apk");
        Files.write(existing, new byte[]{9});
        AndroZooUtils downloader = new AndroZooUtils("key", directory.toString()) {
            @Override
            protected HttpURLConnection openConnection(URL url) {
                fail("An interrupted caller must not open a connection");
                return null;
            }
        };
        Thread.currentThread().interrupt();
        try {
            assertNull(downloader.getApk(SHA));
            assertTrue(Thread.currentThread().isInterrupted());
            Files.delete(existing);
            assertNull(downloader.getApk(SHA));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, fileCount());
    }

    @Test
    void interruptedIoPreservesCancellationAndCleansPartialFile() throws Exception {
        TrackingInputStream input = new TrackingInputStream(new byte[]{1}) {
            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                throw new InterruptedIOException("Interrupted read");
            }
        };
        StubConnection connection = new StubConnection(input, -1);
        try {
            assertNull(downloader(connection).getApk(SHA));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertTrue(input.closed);
        assertTrue(connection.disconnected);
        assertEquals(0, fileCount());
    }

    @Test
    void socketTimeoutDoesNotMarkCallerInterrupted() throws Exception {
        TrackingInputStream input = new TrackingInputStream(new byte[]{1}) {
            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                throw new SocketTimeoutException("Read timed out");
            }
        };
        StubConnection connection = new StubConnection(input, -1);
        assertNull(downloader(connection).getApk(SHA));
        assertFalse(Thread.currentThread().isInterrupted());
        assertTrue(input.closed);
        assertTrue(connection.disconnected);
        assertEquals(0, fileCount());
    }

    private AndroZooUtils downloader(StubConnection connection) {
        return new AndroZooUtils("test-key", directory.toString()) {
            @Override
            protected HttpURLConnection openConnection(URL url) {
                return connection;
            }
        };
    }

    private long fileCount() throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.count();
        }
    }

    private static class TrackingInputStream extends InputStream {
        private final ByteArrayInputStream delegate;
        boolean closed;

        TrackingInputStream(byte[] bytes) {
            delegate = new ByteArrayInputStream(bytes);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            return delegate.read(bytes, offset, length);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            delegate.close();
        }
    }

    private static class StubConnection extends HttpURLConnection {
        private final InputStream input;
        private final long length;
        int status = HTTP_OK;
        InputStream error;
        boolean disconnected;

        StubConnection(InputStream input, long length) throws Exception {
            super(new URL("https://example.invalid/download"));
            this.input = input;
            this.length = length;
        }

        @Override
        public int getResponseCode() {
            return status;
        }

        @Override
        public long getContentLengthLong() {
            return length;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return input;
        }

        @Override
        public InputStream getErrorStream() {
            return error;
        }

        @Override
        public void disconnect() {
            disconnected = true;
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
        }
    }
}
