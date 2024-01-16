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
    public static final String JAVA_LANG_VOID = "java.lang.Void";
    public static final String LOG_CHECKER_CLASS = "LogCheckerClass";
    public static final String JAVA_LANG_OBJECT = "java.lang.Object";
    public static final String JAVA_UTIL_SET = "java.util.Set";
    public static final String JAVA_UTIL_HASHSET = "java.util.HashSet";
    public static final String JAVA_LANG_STRING = "java.lang.String";
    public static final String SUB_SIG_JAVA_UTIL_SET_ADD = "boolean add(java.lang.Object)";
    public static final String SUB_SIG_JAVA_UTIL_SET_CONTAINS = "boolean contains(java.lang.Object)";

    /**
     * Methods
     */
    public static final String LOG_D = "int d(java.lang.String,java.lang.String)";
    public static final String ONCREATE_ACTIVITY = "void onCreate(android.os.Bundle)";
    public static final String ONCREATE1_ACTIVITY = "void onCreate(android.os.Bundle,android.os.PersistableBundle)";
    public static final String ONSTART_ACTIVITY = "void onStart()";
    public static final String ONRESTART_ACTIVITY = "void onRestart()";
    public static final String ONSTATENOTSAVED_ACTIVITY = "void onStateNotSaved()";
    public static final String ONRESUME_ACTIVITY = "void onResume()";
    public static final String ONPAUSE_ACTIVITY = "void onPause()";
    public static final String ONSTOP_ACTIVITY = "void onStop()";
    public static final String ONDESTROY_ACTIVITY = "void onDestroy()";
    public static final String ONCREATE_SERVICE = "void onCreate()";
    public static final String ONSTART_SERVICE = "void onStart(android.content.Intent,int)";
    public static final String ONSTARTCOMMAND_SERVICE = "int onStartCommand(android.content.Intent,int,int)";
    public static final String ONDESTROY_SERVICE = "void onDestroy()";
    public static final String ONBIND_SERVICE = "android.os.IBinder onBind(android.content.Intent)";
    public static final String ONUNBIND_SERVICE = "boolean onUnbind(android.content.Intent)";
    public static final String ONREBIND_SERVICE = "void onRebind(android.content.Intent)";
    public static final String ONRECEIVE = "void onReceive(android.content.Context,android.content.Intent)";
    public static final String ONCREATE_CONTENT_PROVIDER = "boolean onCreate()";
    public static final String INIT = "<init>";
    public static final String SUB_SIG_INIT = "void <init>()";
    public static final String CLINIT = "<clinit>";
    public static final String ADD_LOG = "addLog";
    public static final String SUB_SIG_ADD_LOG = "void addLog(java.lang.String)";
    public static final String ALREADY_LOGGED = "alreadyLogged";
    public static final String SUB_SIG_ALREADY_LOGGED = "boolean alreadyLogged(java.lang.String)";
    public static final String LOG = "log";
    public static final String SUB_SIG_LOG = "void log(java.lang.String,java.lang.String)";

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
    public static final String LOGS = "LOGS";
}