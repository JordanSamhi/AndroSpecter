package com.jordansamhi.androspecter.utils;

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
 * A collection of constant values used throughout the project.
 *
 * @author <a href="https://jordansamhi.com">Jordan Samhi</a>
 */
public class Constants {

    /**
     * Files
     */
    public static final String LIBRARIES_FILE = "/libraries.txt";
    public static final String SYSTEMS_FILE = "/systems.txt";

    public static final String SOURCES_SINKS_FILE = "/sourcesAndSinks.txt";

    /**
     * Classes
     */
    public static final String ANDROID_APP_ACTIVITY = "android.app.Activity";
    public static final String ANDROID_CONTENT_BROADCASTRECEIVER = "android.content.BroadcastReceiver";
    public static final String ANDROID_APP_SERVICE = "android.app.Service";
    public static final String ANDROID_CONTENT_CONTENTPROVIDER = "android.content.ContentProvider";
    public static final String DUMMYMAINCLASS = "dummyMainClass";
    public static final String ANDROID_UTIL_LOG = "android.util.Log";

    /**
     * Methods
     */
    public static final String LOG_D = "int d(java.lang.String,java.lang.String)";

    /**
     * Misc
     */
    public static final String BROADCAST_RECEIVER = "BroadcastReceiver";
    public static final String ACTIVITY = "Activity";
    public static final String CONTENT_PROVIDER = "ContentProvider";
    public static final String SERVICE = "Service";
    public static final String NON_COMPONENT = "non-component";
    public static final String SOURCE = "SOURCE";
    public static final String SINK = "SINK";
}