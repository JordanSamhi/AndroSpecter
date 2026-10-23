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

    public SootUtils() {
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
            org.slf4j.LoggerFactory.getLogger(SootUtils.class)
                    .warn("Cannot resolve method {} in {}", methodName, className, e);
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
     * Returns the total number of statements in the current Scene, excluding the dummy main class.
     * This includes loaded library and framework classes, not only application classes.
     *
     * @return the total number of statements in the current Scene, excluding the dummy main class
     */
    public int getNumberOfStmtInApp() {
        int total = 0;
        for (SootMethod sm : getAllMethods()) {
            total += getNumberOfStmt(sm);
        }
        return total;
    }

    /**
     * Returns the total number of statements in the current Scene after excluding known library packages
     * and the dummy main class. Library membership uses LibrariesManager, not Soot application flags.
     *
     * @return statements outside the dummy main class and known library packages
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
     * based on its method signature. Both source and target nodes are considered, excluding dummy main methods.
     *
     * @param cg CallGraph object for which the unique node count is to be calculated.
     * @return The number of unique nodes in the provided call graph.
     */
    public int getCountOfNodes(CallGraph cg) {
        return getMethodsInCallGraph(cg).size();
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
        return getMethodsInCallGraph(cg).contains(method);
    }

    /**
     * Checks whether the given SootMethod is called in the given CallGraph.
     * <p>
     * Examines the current graph on every call and excludes dummy main methods.
     *
     * @param method The SootMethod to check for in the CallGraph
     * @param cg     The CallGraph to search for calls to the SootMethod
     * @return True if the SootMethod is called in the CallGraph, false otherwise
     */
    public boolean isCalledInCallGraph(SootMethod method, CallGraph cg) {
        for (Edge edge : cg) {
            SootMethod target = edge.tgt();
            if (target != null && !isDummyMainClass(target.getDeclaringClass()) && target.equals(method)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns concrete methods of concrete classes in the current Scene matching the package prefix.
     *
     * @param packageName the prefix used to match class names in the current Scene
     * @return concrete methods in matching concrete classes
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
     * Returns a fresh set of all methods in the current Scene, excluding the dummy main class.
     * Includes loaded library and framework classes, not only application classes.
     *
     * @return a set of all Soot methods in the Scene.
     */
    public Set<SootMethod> getAllMethods() {
        Set<SootMethod> methods = new HashSet<>();
        for (SootClass sc : getClasses()) {
            methods.addAll(sc.getMethods());
        }
        return methods;
    }

    /**
     * Returns a set of SootClass objects representing all classes in the Soot Scene except the Dummy Main Class
     *
     * @return a Set of SootClass objects representing all classes in the Soot Scene
     */
    public Set<SootClass> getClasses() {
        Set<SootClass> classes = new HashSet<>();
        for (SootClass sc : Scene.v().getClasses()) {
            if (!isDummyMainClass(sc)) {
                classes.add(sc);
            }
        }
        return classes;
    }

    /**
     * Returns a fresh set of all classes in the Scene, including libraries but excluding dummy main.
     *
     * @return all current Scene classes except dummy main
     */
    public Set<SootClass> getAllClasses() {
        return new HashSet<>(this.getClasses());
    }

    /**
     * Returns a set of all Soot methods except those for which their class is part of the AndroLibZoo library whitelist.
     * <p>
     * Returns a fresh set so Scene changes and Soot resets are reflected immediately.
     *
     * @return A set of all non-library Soot methods in the Scene.
     */
    public Set<SootMethod> getAllMethodsExceptLibraries() {
        Set<SootMethod> methods = new HashSet<>();
        for (SootClass sc : getNonLibraryClasses()) {
            methods.addAll(sc.getMethods());
        }
        return methods;
    }

    /**
     * Returns a set of all the methods in the given call graph.
     *
     * @param cg the call graph to extract methods from
     * @return a set of all the methods in the given call graph
     */
    public Set<SootMethod> getMethodsInCallGraph(CallGraph cg) {
        Set<SootMethod> methods = new HashSet<>();
        for (Edge edge : cg) {
            SootMethod source = edge.src();
            SootMethod target = edge.tgt();
            if (source != null && !isDummyMainClass(source.getDeclaringClass())) {
                methods.add(source);
            }
            if (target != null && !isDummyMainClass(target.getDeclaringClass())) {
                methods.add(target);
            }
        }
        return methods;
    }


    /**
     * Returns a set of all the methods in the given call graph that are not from libraries.
     * The returned set reflects the current graph and Scene, excluding dummy main methods.
     *
     * @param cg the call graph to extract methods from
     * @return a set of all the methods in the given call graph that are not from libraries
     */
    public Set<SootMethod> getMethodsInCallGraphExceptLibraries(CallGraph cg) {
        Set<SootClass> nonLibraryClasses = getNonLibraryClasses();
        Set<SootMethod> methods = getMethodsInCallGraph(cg);
        methods.removeIf(method -> !nonLibraryClasses.contains(method.getDeclaringClass()));
        return methods;
    }


    /**
     * Returns a set of all non-library classes in the Soot Scene.
     * A non-library class is defined as any class that is not part of a library.
     *
     * @return a Set of SootClass objects representing non-library classes in the Soot Scene.
     */
    public Set<SootClass> getNonLibraryClasses() {
        Set<SootClass> classes = getClasses();
        classes.removeIf(sc -> LibrariesManager.v().isLibrary(sc));
        return classes;
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
