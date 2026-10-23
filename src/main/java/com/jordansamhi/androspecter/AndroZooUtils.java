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
 * Utility class for downloading APK files from AndroZoo using the provided API key and path.
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
     * Constructs a new AndroZooUtils object with the given API key and path to store downloaded APKs.
     *
     * @param apiKey the API key for accessing the AndroZoo API
     * @param path   the path where downloaded APKs will be stored
     */
    public AndroZooUtils(String apiKey, String path) {
        this.apiKey = apiKey;
        this.path = path;
    }

    /**
     * Constructs a new AndroZooUtils object with the given API key and sets
     * the path to store downloaded APK files to the system temporary folder.
     *
     * @param apiKey the API key for accessing the AndroZoo API
     */
    public AndroZooUtils(String apiKey) {
        this.apiKey = apiKey;
        this.path = TmpFolder.v().get();
    }

    /**
     * Downloads an APK file with the given SHA-256 hash from AndroZoo, using the provided API key and path to store the file.
     *
     * @param sha256 the SHA-256 hash of the APK file to download
     * @return the path of the downloaded APK file, or null if the download was unsuccessful or if the folder is not writeable
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

    /** Opens the download connection; subclasses can supply a connection for testing. */
    protected HttpURLConnection openConnection(URL url) throws IOException {
        return (HttpURLConnection) url.openConnection();
    }

    private void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Download interrupted");
        }
    }

    public static String readableFileSize(long size) {
        if (size <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return new DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }
}
