package com.jordansamhi.androspecter;

import com.jordansamhi.androspecter.printers.Writer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

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
        try {
            String fullUrl = String.format("%s?apikey=%s&sha256=%s", this.androzooUrl, URLEncoder.encode(this.apiKey, "UTF-8"), URLEncoder.encode(sha256, "UTF-8"));
            HttpURLConnection httpConn = (HttpURLConnection) new URL(fullUrl).openConnection();
            httpConn.setRequestMethod("GET");
            int responseCode = httpConn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                Writer.v().psuccess("APK found in AndroZoo, downloading in progress...");
                InputStream inputStream = httpConn.getInputStream();
                String filePath = String.format("%s/%s.apk", this.path, sha256);
                File f = new File(this.path);
                if (!f.canWrite()) {
                    Writer.v().perror(String.format("Cannot write file in %s", this.path));
                    return null;
                }
                //TODO: Check if file exists
                FileOutputStream outputStream = new FileOutputStream(filePath);
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                outputStream.close();
                inputStream.close();
                Writer.v().psuccess(String.format("File downloaded and stored in %s", filePath));
                return filePath;
            } else {

                Writer.v().perror(String.format("No file to download. Server replied with HTTP code: %s", responseCode));
            }
            httpConn.disconnect();
        } catch (Exception e) {
            Writer.v().perror(String.format("An exception occurred: %s", e.getMessage()));
        }
        return null;
    }
}
