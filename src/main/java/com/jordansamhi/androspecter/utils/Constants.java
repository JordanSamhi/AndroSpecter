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
     * Creates an instance for compatibility with existing callers and subclasses.
     * All constants are static and can be accessed through {@code Constants} without an instance.
     */
    public Constants() {}

    /** Classpath resource containing the bundled library-package prefixes. */
    public static final String LIBRARIES_FILE = "/libraries.txt";
    /** Classpath resource containing the bundled system-package prefixes. */
    public static final String SYSTEMS_FILE = "/systems.txt";

    /** Classpath resource containing the bundled source and sink definitions. */
    public static final String SOURCES_SINKS_FILE = "/sourcesAndSinks.txt";

    /** Fully qualified Android activity base-class name. */
    public static final String ANDROID_APP_ACTIVITY = "android.app.Activity";
    /** Fully qualified Android broadcast receiver base-class name. */
    public static final String ANDROID_CONTENT_BROADCASTRECEIVER = "android.content.BroadcastReceiver";
    /** Fully qualified Android service base-class name. */
    public static final String ANDROID_APP_SERVICE = "android.app.Service";
    /** Fully qualified Android content provider base-class name. */
    public static final String ANDROID_CONTENT_CONTENTPROVIDER = "android.content.ContentProvider";
    /** Name used to identify FlowDroid's generated Android entry-point class. */
    public static final String DUMMYMAINCLASS = "dummyMainClass";
    /** Fully qualified Android logging class used by injected probes. */
    public static final String ANDROID_UTIL_LOG = "android.util.Log";
    /** Fully qualified boxed void class name; distinct from the primitive Jimple void type. */
    public static final String JAVA_LANG_VOID = "java.lang.Void";
    /** Name reserved for the generated probe helper class. */
    public static final String LOG_CHECKER_CLASS = "LogCheckerClass";
    /** Fully qualified Java root class name. */
    public static final String JAVA_LANG_OBJECT = "java.lang.Object";
    /** Fully qualified set interface used to store previously emitted probe messages. */
    public static final String JAVA_UTIL_SET = "java.util.Set";
    /** Fully qualified implementation used by the generated probe helper. */
    public static final String JAVA_UTIL_HASHSET = "java.util.HashSet";
    /** Fully qualified string class name used in probe signatures. */
    public static final String JAVA_LANG_STRING = "java.lang.String";
    /** Jimple sub-signature of the set insertion method. */
    public static final String SUB_SIG_JAVA_UTIL_SET_ADD = "boolean add(java.lang.Object)";
    /** Jimple sub-signature of the set membership method. */
    public static final String SUB_SIG_JAVA_UTIL_SET_CONTAINS = "boolean contains(java.lang.Object)";

    /** Jimple sub-signature of Android debug logging with tag and message arguments. */
    public static final String LOG_D = "int d(java.lang.String,java.lang.String)";
    /** Jimple sub-signature of the activity creation callback. */
    public static final String ONCREATE_ACTIVITY = "void onCreate(android.os.Bundle)";
    /** Jimple sub-signature of the activity creation callback with persistent state. */
    public static final String ONCREATE1_ACTIVITY = "void onCreate(android.os.Bundle,android.os.PersistableBundle)";
    /** Jimple sub-signature of the activity start callback. */
    public static final String ONSTART_ACTIVITY = "void onStart()";
    /** Jimple sub-signature of the activity restart callback. */
    public static final String ONRESTART_ACTIVITY = "void onRestart()";
    /** Jimple sub-signature of the activity state-not-saved callback. */
    public static final String ONSTATENOTSAVED_ACTIVITY = "void onStateNotSaved()";
    /** Jimple sub-signature of the activity resume callback. */
    public static final String ONRESUME_ACTIVITY = "void onResume()";
    /** Jimple sub-signature of the activity pause callback. */
    public static final String ONPAUSE_ACTIVITY = "void onPause()";
    /** Jimple sub-signature of the activity stop callback. */
    public static final String ONSTOP_ACTIVITY = "void onStop()";
    /** Jimple sub-signature of the activity destruction callback. */
    public static final String ONDESTROY_ACTIVITY = "void onDestroy()";
    /** Jimple sub-signature of the service creation callback. */
    public static final String ONCREATE_SERVICE = "void onCreate()";
    /** Jimple sub-signature of the legacy service start callback. */
    public static final String ONSTART_SERVICE = "void onStart(android.content.Intent,int)";
    /** Jimple sub-signature of the service start-command callback. */
    public static final String ONSTARTCOMMAND_SERVICE = "int onStartCommand(android.content.Intent,int,int)";
    /** Jimple sub-signature of the service destruction callback. */
    public static final String ONDESTROY_SERVICE = "void onDestroy()";
    /** Jimple sub-signature of the service binding callback. */
    public static final String ONBIND_SERVICE = "android.os.IBinder onBind(android.content.Intent)";
    /** Jimple sub-signature of the service unbinding callback. */
    public static final String ONUNBIND_SERVICE = "boolean onUnbind(android.content.Intent)";
    /** Jimple sub-signature of the service rebinding callback. */
    public static final String ONREBIND_SERVICE = "void onRebind(android.content.Intent)";
    /** Jimple sub-signature of the broadcast receiver callback. */
    public static final String ONRECEIVE = "void onReceive(android.content.Context,android.content.Intent)";
    /** Jimple sub-signature of the content provider creation callback. */
    public static final String ONCREATE_CONTENT_PROVIDER = "boolean onCreate()";
    /** Jimple method name for an instance constructor. */
    public static final String INIT = "<init>";
    /** Jimple sub-signature of a no-argument instance constructor. */
    public static final String SUB_SIG_INIT = "void <init>()";
    /** Jimple method name for a static class initializer. */
    public static final String CLINIT = "<clinit>";
    /** Name of the generated helper method that records an emitted message. */
    public static final String ADD_LOG = "addLog";
    /** Jimple sub-signature of the helper method that records an emitted message. */
    public static final String SUB_SIG_ADD_LOG = "void addLog(java.lang.String)";
    /** Name of the generated helper method that checks message membership. */
    public static final String ALREADY_LOGGED = "alreadyLogged";
    /** Jimple sub-signature of the helper method that checks message membership. */
    public static final String SUB_SIG_ALREADY_LOGGED = "boolean alreadyLogged(java.lang.String)";
    /** Name of the generated helper method that emits a message once. */
    public static final String LOG = "log";
    /** Jimple sub-signature of the helper entry point; arguments are message then tag. */
    public static final String SUB_SIG_LOG = "void log(java.lang.String,java.lang.String)";

    /** Component label returned for broadcast receiver classes. */
    public static final String BROADCAST_RECEIVER = "BroadcastReceiver";
    /** Component label returned for activity classes. */
    public static final String ACTIVITY = "Activity";
    /** Component label returned for content provider classes. */
    public static final String CONTENT_PROVIDER = "ContentProvider";
    /** Component label returned for service classes. */
    public static final String SERVICE = "Service";
    /** Fallback component label for classes with no recognized Android component type. */
    public static final String NON_COMPONENT = "non-component";
    /** Record type used for source definitions in source/sink files. */
    public static final String SOURCE = "SOURCE";
    /** Record type used for sink definitions in source/sink files. */
    public static final String SINK = "SINK";
    /** Name of the generated helper field holding previously emitted messages. */
    public static final String LOGS = "LOGS";
}
