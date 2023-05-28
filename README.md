# Utils



# AndroZooUtils Class

## Overview

The `AndroZooUtils` class provides utilities for downloading APK files from AndroZoo using a provided API key and path. 

## Usage

### Initialization

First, you'll need to instantiate the `AndroZooUtils` class. You can do this in one of two ways:

1. **With a specified path**:
```java
String apiKey = "<YOUR-API-KEY>";
String path = "<PATH-TO-STORE-APKs>";
AndroZooUtils androZooUtils = new AndroZooUtils(apiKey, path);
```
2. **Without a specified path**: If no path is specified, APK files will be stored in the system's temporary folder.
```java
String apiKey = "<YOUR-API-KEY>";
AndroZooUtils androZooUtils = new AndroZooUtils(apiKey);
```

### Downloading an APK

After initializing the `AndroZooUtils` object, you can download an APK using the `getApk(String sha256)` method. 

```java
String sha256 = "<APK-SHA256-HASH>";
String apkPath = androZooUtils.getApk(sha256);
```

This method will attempt to download an APK file with the provided SHA-256 hash from AndroZoo. It will then store the file in the path that was specified when the `AndroZooUtils` object was created. If the download is successful, it will return the path of the downloaded APK file. If unsuccessful, or if the specified path is not writable, it will return null.

## Prerequisites

To use the `AndroZooUtils` class, you need the following:

1. A valid AndroZoo API key, which you can obtain by registering on the AndroZoo website.
2. (Optional) A path on your system where you want to store the downloaded APK files. If not specified, the system's temporary folder will be used.

Please replace `<YOUR-API-KEY>`, `<PATH-TO-STORE-APKs>`, and `<APK-SHA256-HASH>` with your own details.

**Note:** This class assumes you have the appropriate permissions to read from and write to the specified path. Please ensure the path is valid and you have the necessary permissions to avoid any issues.


# FlowdroidUtils Class

## Overview

The `FlowdroidUtils` class is a utility for initializing and executing FlowDroid analyses on Android apps. 

## Usage

### Initialization

You can initialize the `FlowdroidUtils` class by providing the path to an Android APK file:

```java
String apkPath = "<PATH-TO-APK>";
FlowdroidUtils flowdroidUtils = new FlowdroidUtils(apkPath);
```

### Running a FlowDroid Analysis

Once you've instantiated the `FlowdroidUtils` object, you can initiate a FlowDroid analysis using the `initializeFlowdroid` method:

```java
String platformPath = "<ANDROID-PLATFORM-PATH>";
IInfoflowConfig config = <CONFIG-OBJECT>;  // or null to use default configuration
String callGraphAlgo = "<CALL-GRAPH-ALGORITHM>";  // CHA, RTA, VTA, or SPARK
boolean useExistingInstance = <BOOLEAN-VALUE>;  

SetupApplication sa = flowdroidUtils.initializeFlowdroid(platformPath, config, callGraphAlgo, useExistingInstance);
```

This method will setup the FlowDroid analysis and return a `SetupApplication` object. If you choose to use an existing Soot instance, be sure to set `useExistingInstance` to true.

### Retrieving Package Name

You can retrieve the package name of the APK using the `getPackageName` method:

```java
String packageName = flowdroidUtils.getPackageName();
```

## Prerequisites

In order to use the `FlowdroidUtils` class, you will need the following:

1. The path to the Android APK that you want to analyze.
2. The path to the directory containing the Android platform.
3. The configuration to be used for the analysis, or null to use default configuration.
4. The algorithm to be used for constructing the call graph (CHA, RTA, VTA, or SPARK).
5. A boolean value indicating whether to use an existing Soot instance.

Please replace `<PATH-TO-APK>`, `<ANDROID-PLATFORM-PATH>`, `<CONFIG-OBJECT>`, `<CALL-GRAPH-ALGORITHM>`, and `<BOOLEAN-VALUE>` with your own details.

**Note:** The FlowDroid analysis might be time-consuming depending on the size and complexity of the APK file. Be patient during the process.



# Instrumenter

The Instrumenter is a singleton class. Its primary purpose is to facilitate the process of adding log statements to specific parts of your Android application's bytecode during static analysis, with the help of the Soot framework.

## Basic Usage

```java
// Acquiring the singleton instance
Instrumenter inst = Instrumenter.v();

// Adding log to all methods
Transform t1 = inst.addLogToAllMethods("MyTag", "jtp");

// Adding log to all method calls
Transform t2 = inst.addLogToAllMethodCalls("MyTag", "jtp");

PackManager.v().getPack("jtp").add(t1);
PackManager.v().getPack("jtp").add(t2);
```

## Key Features

### 1. Log Statement Insertion
The Instrumenter provides methods for adding log statements to specific locations in a method's bytecode. You can add log statements to all methods in your application, to all method calls within a method, or to a specific location within a method.

### 2. Flexibility in Logging
The class allows you to specify the tag and message for your log statements, providing you with the flexibility to customize your logging to suit your needs.

### 3. Singleton Structure
The class is designed as a singleton, meaning that there is only one instance of the class throughout your application. This helps maintain consistency when instrumenting your code.

## Method Descriptions

### 1. `addLogToAllMethods(String tagToLog, String phaseName)`
This method inserts a log statement at the entry point of all methods in your application classes. The log statement uses the provided tag and logs the method's signature.

### 2. `addLogStatement(Chain<Unit> units, Unit insertionPoint, String tagToLog, String messageToLog, Body b)`
This method allows you to add a log statement to a specific location within a method. It inserts the log statement before the provided `insertionPoint` in the method's `units`.

### 3. `addLogToAllMethodCalls(String tagToLog, String phaseName)`
This method modifies all method calls within a body to include a log statement before them. It logs the signature of the calling and called method separated with "-->".

### 4. `addLogToMethod(SootMethod sm, String tagToLog, String messageToLog)`
This method inserts a log statement right after all identity statements in the given method. The log message is passed as a parameter.

## Notes
Before using the Instrumenter class, ensure you have a good understanding of how the Soot framework works. Also, remember that the "jtp" (Jimple Transformation Pack) phase is the phase where transformations on Jimple representation of methods take place, and this is the phase used for instrumentation in these methods.


# LibrariesManager and SystemManager Classes

## Overview

The `LibrariesManager` and `SystemManager` classes provide utilities for managing libraries and system classes in Android apps.

A point to note is that these classes rely on two specific files located in the resource folder of your application. These files contain the reference data used to determine whether a class is a system class or a library.

## LibrariesManager Class

`LibrariesManager` is used to load and manage a list of known library packages. It uses the singleton pattern to ensure that only one instance of this class exists throughout your application. 

The class loads data from a file defined by `Constants.LIBRARIES_FILE`. This file should contain the names of known library packages, one per line. 

Here is how you can use the `LibrariesManager` class:

```java
// Retrieve the singleton instance of LibrariesManager
LibrariesManager librariesManager = LibrariesManager.v();

// Check whether a SootClass belongs to a library
SootClass sc = ... // The SootClass to check
boolean isLibraryClass = librariesManager.isLibrary(sc);
```

## SystemManager Class

`SystemManager` manages system classes by checking if a given `SootClass` is a system class. Like `LibrariesManager`, it uses the singleton pattern.

The class loads data from a file defined by `Constants.SYSTEMS_FILE`. This file should contain the names of system classes, one per line.

Here's how you can use the `SystemManager` class:

```java
// Retrieve the singleton instance of SystemManager
SystemManager systemManager = SystemManager.v();

// Check whether a SootClass is a system class
SootClass sc = ... // The SootClass to check
boolean isSystemClass = systemManager.isSystemClass(sc);
