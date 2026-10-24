package com.jordansamhi.androspecter;

import com.jordansamhi.androspecter.printers.Writer;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;

/*-
 * #%L
 * AndroSpecter
 *
 * %%
 * Copyright (C) 2023 Jordan Samhi
 * All rights reserved
 *
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-2.1.html>.
 * #L%
 */

/**
 * Downloads APKs from AndroZoo into a caller-selected directory. Download errors are
 * logged through the library facade and represented by a null result. The caller owns
 * successful files and their cleanup; constructing this helper performs no download.
 *
 * @author Jordan Samhi
 */
public class AndroZooUtils {

    private static final int CONNECT_TIMEOUT_MILLIS = 15000;
    private static final int READ_TIMEOUT_MILLIS = 60000;

    private final String apiKey;
    private final String androzooUrl = "https://androzoo.uni.lu/api/download";
    private final String path;

    /**
     * Stores the API key and download directory without validating them or creating the directory.
     * Configuration and filesystem failures are reported when {@link #getApk(String)} runs.
     *
     * @param apiKey API key required for a new download; use a non-null valid key
     * @param path path to an existing writable directory, required to be non-null at download time
     */
    public AndroZooUtils(String apiKey, String path) {
        this.apiKey = apiKey;
        this.path = path;
    }

    /**
     * Constructs a new AndroZooUtils object with the given API key and sets
     * the download directory to the current {@code java.io.tmpdir} property.
     * The property is captured now; no private subdirectory is created or reserved.
     *
     * @param apiKey API key required for a new download; use a non-null valid key
     * @throws SecurityException if access to the temporary-directory property is denied
     */
    public AndroZooUtils(String apiKey) {
        this.apiKey = apiKey;
        this.path = TmpFolder.v().get();
    }

    /**
     * Returns an existing readable, nonempty {@code <sha256>.apk} in the configured directory,
     * or downloads that file without replacing an existing destination. The supplied hash
     * must contain exactly 64 hexadecimal characters; its case is retained in the filename.
     * Existing and downloaded files are not checked against the hash or parsed as APKs.
     * <p>A new download uses a temporary file, checks that it is nonempty and matches the
     * declared content length when available, then moves it to the final path. Partial
     * files are removed on failure where possible. The caller owns the returned file,
     * including a pre-existing file returned without downloading.
     * <p>Connection and individual read timeouts are 15 and 60 seconds respectively,
     * not a total runtime limit. Thread interruption is checked between I/O operations;
     * a blocked read can delay cancellation. Interrupt status is preserved when observed.
     *
     * @param sha256 64-character hexadecimal APK identifier
     * @return downloaded or cached file path, absolute only if the configured directory is absolute,
     *         or null on invalid input, interruption, HTTP failure, or download/filesystem error;
     *         these failures are logged rather than rethrown
     */
    public String getApk(String sha256) {
        HttpURLConnection httpConn = null;
        Path partial = null;
        try {
            checkInterrupted();
            if (sha256 == null || !sha256.matches("[a-fA-F0-9]{64}")) {
                throw new IllegalArgumentException("Expected a 64-character SHA-256 hash");
            }
            Path directory = Paths.get(this.path);
            if (!Files.isDirectory(directory) || !Files.isWritable(directory)) {
                throw new IOException("Download directory is not writable: " + directory);
            }
            Path destination = directory.resolve(sha256 + ".apk");
            if (Files.exists(destination)) {
                if (Files.isRegularFile(destination) && Files.isReadable(destination)
                        && Files.size(destination) > 0) {
                    checkInterrupted();
                    return destination.toString();
                }
                throw new IOException("Download destination already exists but is not a readable APK file");
            }
            String fullUrl = String.format("%s?apikey=%s&sha256=%s", this.androzooUrl, URLEncoder.encode(this.apiKey, "UTF-8"), URLEncoder.encode(sha256, "UTF-8"));
            checkInterrupted();
            httpConn = openConnection(new URL(fullUrl));
            httpConn.setRequestMethod("GET");
            httpConn.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            httpConn.setReadTimeout(READ_TIMEOUT_MILLIS);
            checkInterrupted();
            int responseCode = httpConn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                Writer.v().psuccess("APK found in AndroZoo, downloading in progress...");
                long fileSize = httpConn.getContentLengthLong();
                if (fileSize != -1) {
                    Writer.v().pinfo(String.format("APK size: %s bytes", readableFileSize(fileSize)));
                }
                partial = Files.createTempFile(directory, sha256 + "-", ".part");
                long downloaded = 0;
                checkInterrupted();
                try (InputStream inputStream = httpConn.getInputStream();
                     OutputStream outputStream = Files.newOutputStream(partial)) {
                    byte[] buffer = new byte[4096];
                    while (true) {
                        checkInterrupted();
                        int bytesRead = inputStream.read(buffer);
                        if (bytesRead == -1) {
                            break;
                        }
                        checkInterrupted();
                        outputStream.write(buffer, 0, bytesRead);
                        downloaded += bytesRead;
                    }
                }
                if (downloaded == 0 || (fileSize >= 0 && downloaded != fileSize)) {
                    throw new IOException("Incomplete APK download");
                }
                // Never replace an existing download, including one created by another worker.
                checkInterrupted();
                Files.move(partial, destination);
                partial = null;
                Writer.v().psuccess(String.format("File downloaded and stored in %s", destination));
                return destination.toString();
            } else {

                Writer.v().perror(String.format("No file to download. Server replied with HTTP code: %s", responseCode));
            }
        } catch (Exception e) {
            if (e instanceof InterruptedIOException && !(e instanceof SocketTimeoutException)) {
                Thread.currentThread().interrupt();
            }
            Writer.v().perror(String.format("An exception occurred: %s", e.getMessage()));
        } finally {
            if (httpConn != null) {
                InputStream errorStream = httpConn.getErrorStream();
                if (errorStream != null) {
                    try {
                        errorStream.close();
                    } catch (IOException ignored) {
                        // The connection is disconnected below even if closing fails.
                    }
                }
                httpConn.disconnect();
            }
            if (partial != null) {
                try {
                    Files.deleteIfExists(partial);
                } catch (IOException e) {
                    Writer.v().perror("Cannot remove partial download: " + partial);
                }
            }
        }
        return null;
    }

    /**
     * Opens an unconnected HTTP connection. {@link #getApk(String)} configures its request
     * and timeouts, consumes its streams, and disconnects it. Subclasses may supply a
     * compatible connection, for example for a local test server.
     *
     * @param url non-null HTTP(S) download URL, including the API key and APK identifier
     * @return non-null connection whose lifecycle is transferred to {@code getApk}
     * @throws IOException if a connection cannot be opened
     * @throws NullPointerException if {@code url} is null
     * @throws ClassCastException if the URL protocol does not provide an HTTP connection
     */
    protected HttpURLConnection openConnection(URL url) throws IOException {
        return (HttpURLConnection) url.openConnection();
    }

    private void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Download interrupted");
        }
    }

    /**
     * Formats a byte count using powers of 1024 and the JVM's default number locale.
     * Unit labels range from B through TB, with at most one fractional digit.
     *
     * @param size byte count; zero or negative values produce {@code "0 B"}
     * @return the formatted value followed by a space and its unit
     * @throws ArrayIndexOutOfBoundsException if the computed unit exceeds TB
     */
    public static String readableFileSize(long size) {
        if (size <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return new DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }
}
