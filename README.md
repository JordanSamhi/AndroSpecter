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
