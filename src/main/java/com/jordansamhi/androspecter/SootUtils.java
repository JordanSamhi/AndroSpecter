package com.jordansamhi.androspecter;

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

import com.jordansamhi.androspecter.files.LibrariesManager;
import com.jordansamhi.androspecter.utils.Constants;
import soot.*;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;
import soot.options.Options;
import soot.util.Chain;

import java.util.*;

/**
 * Utility class for working with the Soot framework. Provides various helper methods for working with Soot classes,
 * methods, and call graphs.
 *
 * @author <a href="https://jordansamhi.com">Jordan Samhi</a>
 */
public class SootUtils {

    private final Set<SootClass> nonLibraryClasses;
    private final Set<SootClass> classes;
    private final Set<SootMethod> srcInCG;
    private final Set<SootMethod> tgtInCG;
    private final Set<SootMethod> allMethods;
    private final Set<SootMethod> allMethodsExceptLibraries;
    private final Set<SootMethod> allMethodsInCallGraph;
    private final Set<SootMethod> allMethodsInCallGraphExceptLibraries;

    public SootUtils() {
        this.nonLibraryClasses = new HashSet<>();
        this.classes = new HashSet<>();
        this.srcInCG = new HashSet<>();
        this.tgtInCG = new HashSet<>();
        this.allMethods = new HashSet<>();
        this.allMethodsExceptLibraries = new HashSet<>();
        this.allMethodsInCallGraph = new HashSet<>();
        this.allMethodsInCallGraphExceptLibraries = new HashSet<>();
    }

    /**
     * Returns a SootMethodRef with the given class name and method name.
     *
     * @param className  the name of the class that contains the method
     * @param methodName the name of the method to return a reference to
     * @return a SootMethodRef to the SootMethod with the given class and method names
     * @throws RuntimeException if the method could not be found
     */
    public SootMethodRef getMethodRef(String className, String methodName) {
        SootMethod sm;
        try {
            SootClass sc = Scene.v().getSootClass(className);
            sm = sc.getMethod(methodName);
            return sm.makeRef();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Returns a set of all superclasses of the given SootClass.
     *
     * @param sootClass the SootClass for which to retrieve all superclasses
     * @return a set of all superclasses of the given SootClass, including the immediate superclass
     */
    public Set<SootClass> getAllSuperClasses(SootClass sootClass) {
        Set<SootClass> classes = new HashSet<>();
        SootClass superClass;
        if (sootClass.hasSuperclass()) {
            superClass = sootClass.getSuperclass();
            classes.add(superClass);
            classes.addAll(getAllSuperClasses(superClass));
        }
        return classes;
    }

    /**
     * Returns a set of all interfaces implemented by the given SootClass.
     *
     * @param sootClass the SootClass for which to retrieve all interfaces
     * @return a set of all interfaces implemented by the given SootClass
     */
    public Set<SootClass> getAllInterfaces(SootClass sootClass) {
        Set<SootClass> interfaces = new HashSet<>();
        Chain<SootClass> interfacesImplemented;
        if (sootClass.getInterfaceCount() > 0) {
            interfacesImplemented = sootClass.getInterfaces();
            interfaces.addAll(interfacesImplemented);
            for (SootClass i : interfacesImplemented) {
                interfaces.addAll(getAllInterfaces(i));
            }
        }
        return interfaces;
    }

    /**
     * Returns a set of the class names of the given list of SootClasses.
     *
     * @param classes the list of SootClasses for which to retrieve the class names
     * @return a set of the class names of the given SootClasses
     */
    public Set<String> getClassNames(Collection<SootClass> classes) {
        Set<String> names = new HashSet<>();
        for (SootClass sc : classes) {
            names.add(sc.getName());
        }
        return names;
    }

    /**
     * Returns the number of statements in the given SootMethod.
     *
     * @param sm the SootMethod for which to count statements
     * @return the number of statements in the given SootMethod, or 0 if the method is not concrete
     */
    public int getNumberOfStmt(SootMethod sm) {
        if (sm.isConcrete()) {
            return sm.retrieveActiveBody().getUnits().size();
        }
        return 0;
    }

    /**
     * Returns the total number of statements in all application classes and methods.
     *
     * @return the total number of statements in all application classes and methods
     */
    public int getNumberOfStmtInApp() {
        int total = 0;
        for (SootMethod sm : getAllMethods()) {
            total += getNumberOfStmt(sm);
        }
        return total;
    }

    /**
     * Returns the total number of statements in all application classes and methods except libraries
     *
     * @return the total number of statements in all application classes and methods except libraries
     */
    public int getNumberOfStmtInAppWithoutLibraries() {
        int total = 0;
        for (SootMethod sm : getAllMethodsExceptLibraries()) {
            total += getNumberOfStmt(sm);
        }
        return total;
    }

    /**
     * Returns the total number of statements in a collection of SootMethod objects.
     *
     * @param methods a collection of SootMethod objects
     * @return the total number of statements in the given collection of SootMethod objects
     */
    public int getNumberOfStmt(Collection<SootMethod> methods) {
        int total = 0;
        for (SootMethod sm : methods) {
            total += getNumberOfStmt(sm);
        }
        return total;
    }

    /**
     * Counts the number of edges in the given call graph.
     *
     * @param cg the CallGraph object for which the count is to be calculated
     * @return the number of edges in the given call graph
     */
    public int countEdgesInCallGraph(CallGraph cg) {
        return cg.size();
    }

    /**
     * Counts the number of edges in the given call graph where the target of the edge
     * is a method declared in a non-library class. A non-library class is defined as
     * any class that is not part of a library.
     *
     * @param cg the CallGraph object for which the count is to be calculated
     * @return the number of edges in the call graph where the target of the edge is
     * a method declared in a non-library class
     */
    public int countEdgesWithNonLibraryTargets(CallGraph cg) {
        int count = 0;
        Set<SootClass> nonLibraryClasses = this.getNonLibraryClasses();
        SootMethod tgt;
        SootClass parentClass;
        for (Edge e : cg) {
            tgt = e.tgt();
            if (tgt != null) {
                parentClass = tgt.getDeclaringClass();
                if (nonLibraryClasses.contains(parentClass)) {
                    count++;
                }
            }
        }
        return count;
    }


    /**
     * This method counts the number of unique nodes in the provided call graph. A node is considered unique
     * based on its method signature. Both source and target nodes of all edges in the call graph are considered.
     *
     * @param cg CallGraph object for which the unique node count is to be calculated.
     * @return The number of unique nodes in the provided call graph.
     */
    public int getCountOfNodes(CallGraph cg) {
        if (this.allMethodsInCallGraph.isEmpty()) {
            this.populateCallGraphSets(cg);
        }
        return this.allMethodsInCallGraph.size();
    }


    /**
     * Returns the type of Android component for the given SootClass.
     *
     * @param sc the SootClass for which to determine the Android component type
     * @return the type of Android component for the given SootClass, or NON_COMPONENT if the SootClass does not correspond to an Android component
     */
    public String getComponentType(SootClass sc) {
        Set<SootClass> classes = getAllSuperClasses(sc);
        for (SootClass c : classes) {
            switch (c.getName()) {
                case Constants.ANDROID_APP_ACTIVITY:
                    return Constants.ACTIVITY;
                case Constants.ANDROID_CONTENT_BROADCASTRECEIVER:
                    return Constants.BROADCAST_RECEIVER;
                case Constants.ANDROID_CONTENT_CONTENTPROVIDER:
                    return Constants.CONTENT_PROVIDER;
                case Constants.ANDROID_APP_SERVICE:
                    return Constants.SERVICE;
            }
        }
        return Constants.NON_COMPONENT;
    }

    /**
     * Checks whether the given SootMethod is in the given CallGraph.
     *
     * @param method the SootMethod to check for in the CallGraph
     * @param cg     the CallGraph to search for the SootMethod
     * @return true if the SootMethod is in the CallGraph, false otherwise
     */
    public boolean isInCallGraph(SootMethod method, CallGraph cg) {
        if (this.srcInCG.isEmpty() || this.tgtInCG.isEmpty()) {
            this.populateCallGraphSets(cg);
        }
        return this.srcInCG.contains(method) || this.tgtInCG.contains(method);
    }

    /**
     * Populates the sets containing the methods present in the call graph. The sets include source methods, target methods,
     * and all methods. It also populates a set containing all methods excluding those from library classes.
     * This method should be used for pre-calculation to optimize repeated calls for these method sets in the call graph.
     *
     * @param cg the call graph used to populate the method sets
     */
    private void populateCallGraphSets(CallGraph cg) {
        Set<SootClass> nonLibraryClasses = this.getNonLibraryClasses();

        for (Edge edge : cg) {
            if (edge != null) {
                SootMethod src = edge.src();
                SootMethod tgt = edge.tgt();

                if (src != null) {
                    SootClass srcClass = src.getDeclaringClass();
                    if (!isDummyMainClass(srcClass)) {
                        this.srcInCG.add(src);
                    }
                    if (nonLibraryClasses.contains(srcClass)) {
                        this.allMethodsInCallGraphExceptLibraries.add(src);
                    }
                }

                if (tgt != null) {
                    SootClass tgtClass = tgt.getDeclaringClass();
                    if (!isDummyMainClass(tgtClass)) {
                        this.tgtInCG.add(tgt);
                    }
                    if (nonLibraryClasses.contains(tgtClass)) {
                        this.allMethodsInCallGraphExceptLibraries.add(tgt);
                    }
                }
            }
        }

        this.allMethodsInCallGraph.addAll(this.srcInCG);
        this.allMethodsInCallGraph.addAll(this.tgtInCG);
    }


    /**
     * Checks whether the given SootMethod is called in the given CallGraph.
     * <p>
     * This method uses a set to cache the target methods in the CallGraph. If the cache is empty,
     * it iterates through the edges in the CallGraph, and adds the target method of each edge to the cache.
     * <p>
     * Finally, it checks if the given method is in the cache, and returns the result.
     *
     * @param method The SootMethod to check for in the CallGraph
     * @param cg     The CallGraph to search for calls to the SootMethod
     * @return True if the SootMethod is called in the CallGraph, false otherwise
     */
    public boolean isCalledInCallGraph(SootMethod method, CallGraph cg) {
        if (this.tgtInCG.isEmpty()) {
            this.populateCallGraphSets(cg);
        }
        return this.tgtInCG.contains(method);
    }

    /**
     * Returns a set of all application methods that belong to the given package.
     *
     * @param packageName the name of the package for which to retrieve all application methods
     * @return a set of all application methods that belong to the given package
     */
    public Set<SootMethod> getMethodsFromPackage(String packageName) {
        Set<SootMethod> methods = new HashSet<>();
        for (SootClass sc : this.getClasses()) {
            if (sc.getName().startsWith(packageName)) {
                if (sc.isConcrete()) {
                    for (SootMethod sm : sc.getMethods()) {
                        if (sm.isConcrete()) {
                            methods.add(sm);
                        }
                    }
                }
            }
        }
        return methods;
    }

    /**
     * Returns a set of all Soot methods in the Scene. The methods are computed and stored
     * in a class field the first time this method is called, and the stored value is used for
     * subsequent calls.
     *
     * @return a set of all Soot methods in the Scene.
     */
    public Set<SootMethod> getAllMethods() {
        if (this.allMethods.isEmpty()) {
            for (SootClass sc : this.getClasses()) {
                this.allMethods.addAll(sc.getMethods());
            }
        }
        return this.allMethods;
    }

    /**
     * Returns a set of SootClass objects representing all classes in the Soot Scene except the Dummy Main Class
     *
     * @return a Set of SootClass objects representing all classes in the Soot Scene
     */
    public Set<SootClass> getClasses() {
        if (this.classes.isEmpty()) {
            for (SootClass sc : Scene.v().getClasses()) {
                if (!isDummyMainClass(sc)) {
                    this.classes.add(sc);
                }
            }
        }
        return this.classes;
    }

    /**
     * Returns a set of all the classes in the scene, including library classes.
     *
     * @return a set of all the classes in the scene, including library classes.
     */
    public Set<SootClass> getAllClasses() {
        return new HashSet<>(this.getClasses());
    }

    /**
     * Returns a set of all Soot methods except those for which their class is part of the AndroLibZoo library whitelist.
     * <p>
     * This method caches the results to prevent unnecessary computation in repeated calls. If the cache is null, it calculates
     * the set of all non-library Soot methods and stores it in the cache. Otherwise, it returns the cached result.
     *
     * @return A set of all non-library Soot methods in the Scene.
     */
    public Set<SootMethod> getAllMethodsExceptLibraries() {
        if (this.allMethodsExceptLibraries.isEmpty()) {
            for (SootClass sc : this.getNonLibraryClasses()) {
                allMethodsExceptLibraries.addAll(sc.getMethods());
            }
        }
        return this.allMethodsExceptLibraries;
    }

    /**
     * Returns a set of all the methods in the given call graph.
     *
     * @param cg the call graph to extract methods from
     * @return a set of all the methods in the given call graph
     */
    public Set<SootMethod> getMethodsInCallGraph(CallGraph cg) {
        if (this.allMethodsInCallGraph.isEmpty()) {
            populateCallGraphSets(cg);
        }
        return this.allMethodsInCallGraph;
    }


    /**
     * Returns a set of all the methods in the given call graph that are not from libraries.
     * The method uses a cached set of methods if available to improve performance.
     *
     * @param cg the call graph to extract methods from
     * @return a set of all the methods in the given call graph that are not from libraries
     */
    public Set<SootMethod> getMethodsInCallGraphExceptLibraries(CallGraph cg) {
        if (this.allMethodsInCallGraphExceptLibraries.isEmpty()) {
            populateCallGraphSets(cg);
        }
        return this.allMethodsInCallGraphExceptLibraries;
    }


    /**
     * Returns a set of all non-library classes in the Soot Scene.
     * A non-library class is defined as any class that is not part of a library.
     *
     * @return a Set of SootClass objects representing non-library classes in the Soot Scene.
     */
    public Set<SootClass> getNonLibraryClasses() {
        if (this.nonLibraryClasses.isEmpty()) {
            for (SootClass sc : this.getClasses()) {
                if (!LibrariesManager.v().isLibrary(sc)) {
                    this.nonLibraryClasses.add(sc);
                }
            }
        }
        return this.nonLibraryClasses;
    }

    /**
     * Checks whether the given SootClass is a dummy main class.
     *
     * @param sc the SootClass to check for dummy main class membership
     * @return true if the SootClass is a dummy main class, false otherwise
     */
    public boolean isDummyMainClass(SootClass sc) {
        return sc.getName().equals(Constants.DUMMYMAINCLASS);
    }

    /**
     * Sets up Soot
     *
     * @param platformPath  The path to the Android JARs.
     * @param apkPath       The path to the APK file to be processed.
     * @param wholeAnalysis Whether we want a whole program analysis or not
     */
    public void setupSoot(String platformPath, String apkPath, boolean wholeAnalysis) {
        G.reset();
        Options.v().set_process_multiple_dex(true);
        Options.v().set_allow_phantom_refs(true);
        Options.v().set_output_format(Options.output_format_none);
        Options.v().set_whole_program(wholeAnalysis);
        Options.v().set_prepend_classpath(true);
        Options.v().set_android_jars(platformPath);
        Options.v().set_src_prec(Options.src_prec_apk);
        Options.v().set_process_dir(Collections.singletonList(apkPath));
        Options.v().set_include_all(true);
        this.addClasses();
        Scene.v().loadNecessaryClasses();
    }

    /**
     * Gets the adjacency list representation of a given call graph.
     *
     * <p>This method iterates through the provided call graph, treating each edge as a directed connection
     * from a source method to a target method. For each edge, the source method is used as a key in the
     * adjacency list, with the corresponding value being a list of methods that the source method directly calls.
     * If the source method is encountered for the first time, a new list is created and added to the adjacency
     * list with the source method as the key. The target method is then added to the source method's list of called methods.</p>
     *
     * @param cg the CallGraph object to transform into an adjacency list
     * @return a Map where each key is a SootMethod object representing a source method and each value is a List of SootMethod
     * objects representing the methods directly called by the source method
     */
    public Map<SootMethod, List<SootMethod>> getCallGraphAdjacencyList(CallGraph cg) {
        Map<SootMethod, List<SootMethod>> adjacencyList = new HashMap<>();
        for (Edge e : cg) {
            SootMethod srcMethod = e.src();
            SootMethod tgtMethod = e.tgt();
            adjacencyList.putIfAbsent(srcMethod, new ArrayList<>());
            adjacencyList.get(srcMethod).add(tgtMethod);
        }
        return adjacencyList;
    }


    /**
     * Sets up Soot with the specified output directory.
     *
     * @param platformPath  The path to the Android JARs.
     * @param apkPath       The path to the APK file to be processed.
     * @param outputPath    The directory to output the results.
     * @param wholeAnalysis Whether we want a whole program analysis or not
     */
    public void setupSootWithOutput(String platformPath, String apkPath, String outputPath, boolean wholeAnalysis) {
        G.reset();
        Options.v().set_process_multiple_dex(true);
        Options.v().set_allow_phantom_refs(true);
        Options.v().set_output_format(Options.output_format_dex);
        Options.v().set_output_dir(outputPath);
        Options.v().set_force_overwrite(true);
        Options.v().set_whole_program(wholeAnalysis);
        Options.v().set_prepend_classpath(true);
        Options.v().set_android_jars(platformPath);
        Options.v().set_src_prec(Options.src_prec_apk);
        Options.v().set_process_dir(Collections.singletonList(apkPath));
        Options.v().set_include_all(true);
        this.addClasses();
        Scene.v().loadNecessaryClasses();
    }

    private void addClasses() {
        Scene.v().addBasicClass(Constants.ANDROID_UTIL_LOG, SootClass.SIGNATURES);
        Scene.v().loadClassAndSupport(Constants.ANDROID_UTIL_LOG);
        Scene.v().addBasicClass(Constants.JAVA_UTIL_HASHSET, SootClass.SIGNATURES);
        Scene.v().loadClassAndSupport(Constants.JAVA_UTIL_HASHSET);
        Scene.v().addBasicClass(Constants.JAVA_LANG_OBJECT, SootClass.SIGNATURES);
        Scene.v().loadClassAndSupport(Constants.JAVA_LANG_OBJECT);
        Scene.v().addBasicClass(Constants.JAVA_UTIL_SET, SootClass.SIGNATURES);
        Scene.v().loadClassAndSupport(Constants.JAVA_UTIL_SET);
        Scene.v().addBasicClass(Constants.JAVA_LANG_OBJECT, SootClass.SIGNATURES);
        Scene.v().loadClassAndSupport(Constants.JAVA_LANG_OBJECT);
    }

}