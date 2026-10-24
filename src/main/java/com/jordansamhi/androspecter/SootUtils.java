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
 * Low-level helpers over the caller's current Soot scene and call graphs. Initialize an APK first with
 * {@link #setupSoot(String, String, boolean)}, FlowDroid, or a managed
 * {@link com.jordansamhi.androspecter.analysis.AndroidAnalysis}. Query helpers do not construct a call graph.
 * <p>Soot objects returned by this class are borrowed, mutable references. Returned collections are new,
 * caller-owned containers, but their Soot elements remain tied to the originating scene and must not be
 * reused after a reset. String and numeric results are detached. Use these helpers only while the caller
 * exclusively controls the scene; they do not check thread ownership, cancellation, or external resets.
 * Methods that retrieve bodies can load and mutate Soot's cached representation and propagate resolution
 * failures. Unless stated otherwise, class queries include loaded framework, library, abstract, and phantom
 * classes and exclude only the class named {@code dummyMainClass}.
 *
 * @author <a href="https://jordansamhi.com">Jordan Samhi</a>
 */
public class SootUtils {

    /**
     * Creates a utility that operates on the current global Soot scene. It does
     * not initialize, own, or protect that scene from resets by other code.
     */
    public SootUtils() {
    }

    /**
     * Looks up a declared method in the current scene by its full sub-signature.
     * For example, use {@code "void <init>()"} for a no-argument constructor.
     * Resolution failures are logged through SLF4J and return null.
     *
     * Inherited methods are not searched. This does not load a missing class from the classpath.
     *
     * @param className name of a class already loaded in the scene; null is treated as a failed lookup
     * @param methodName declared method sub-signature, including return and parameter types;
     *                   null is treated as a failed lookup
     * @return a live method reference, or null if the class or method cannot be resolved;
     *         a successful reference must not be reused after Soot is reset
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
     * Follows the superclass chain, excluding the supplied class itself. Interfaces are not visited.
     * The hierarchy must already be resolved and acyclic; this does not load missing hierarchy information.
     *
     * @param sootClass non-null class from the current scene
     * @return mutable, unordered set of borrowed superclass references; empty if there is no superclass
     * @throws NullPointerException if {@code sootClass} is null
     * @throws RuntimeException if Soot cannot access the loaded superclass hierarchy
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
     * Collects the class's directly declared interfaces and their transitive superinterfaces.
     * This does not traverse the class's superclasses, so interfaces inherited only through a superclass
     * are omitted. The supplied class itself is not included, even when it is an interface.
     *
     * @param sootClass non-null class or interface with a resolved, acyclic interface hierarchy
     * @return mutable, unordered set of borrowed interface references
     * @throws NullPointerException if {@code sootClass} is null
     * @throws RuntimeException if Soot cannot access the loaded interface hierarchy
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
     * Copies class names from the supplied collection, collapsing duplicates.
     *
     * @param classes non-null collection of non-null classes from a valid scene
     * @return mutable, unordered set of detached names, usable after a Soot reset
     * @throws NullPointerException if the collection or an element is null
     */
    public Set<String> getClassNames(Collection<SootClass> classes) {
        Set<String> names = new HashSet<>();
        for (SootClass sc : classes) {
            names.add(sc.getName());
        }
        return names;
    }

    /**
     * Counts Jimple/body units in a concrete method, retrieving its active body if needed.
     * Counts include identity, return, monitor, and inserted instrumentation units; they are not source
     * statement, bytecode instruction, or executed-statement counts.
     *
     * @param sm non-null method from the current scene
     * @return number of units in its active body, or zero for a non-concrete method
     * @throws NullPointerException if {@code sm} is null
     * @throws RuntimeException if a concrete method's body cannot be loaded
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
     * Concrete bodies are retrieved as needed; all body units count as described by
     * {@link #getNumberOfStmt(SootMethod)}. Classes or methods not loaded in the scene are not discovered.
     *
     * @return the total number of statements in the current Scene, excluding the dummy main class
     * @throws RuntimeException if method enumeration or body loading fails
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
     * Concrete bodies are retrieved as needed, and every unit in an accepted body is counted.
     *
     * @return statements outside the dummy main class and known library packages
     * @throws RuntimeException if library-policy loading, method enumeration, or body loading fails
     */
    public int getNumberOfStmtInAppWithoutLibraries() {
        int total = 0;
        for (SootMethod sm : getAllMethodsExceptLibraries()) {
            total += getNumberOfStmt(sm);
        }
        return total;
    }

    /**
     * Sums body-unit counts using {@link #getNumberOfStmt(SootMethod)}, loading concrete bodies if needed.
     * A repeated method in the collection contributes its count repeatedly.
     *
     * @param methods non-null collection of non-null methods from the current scene
     * @return sum of unit counts, with non-concrete methods contributing zero
     * @throws NullPointerException if the collection or an element is null
     * @throws RuntimeException if a concrete method's body cannot be loaded
     */
    public int getNumberOfStmt(Collection<SootMethod> methods) {
        int total = 0;
        for (SootMethod sm : methods) {
            total += getNumberOfStmt(sm);
        }
        return total;
    }

    /**
     * Returns the graph's current edge count without filtering dummy-main or library edges.
     * Distinct edge occurrences count separately, even when their endpoint methods are equal.
     *
     * @param cg non-null live call graph, which must not be modified concurrently
     * @return the number of edges in the given call graph
     * @throws NullPointerException if {@code cg} is null
     */
    public int countEdgesInCallGraph(CallGraph cg) {
        return cg.size();
    }

    /**
     * Counts the number of edges in the given call graph where the target of the edge
     * is declared by a current-scene class accepted by {@link #getNonLibraryClasses()}.
     * The source is unrestricted, so edges from dummy main or library methods can contribute.
     * Parallel edge occurrences count separately; this does not construct or refresh the graph.
     *
     * @param cg non-null live graph belonging to the current scene
     * @return the number of edges in the call graph where the target of the edge is
     * a method declared in a non-library class
     * @throws NullPointerException if {@code cg} is null
     * @throws RuntimeException if loading or applying the library policy fails
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
     * Counts the distinct method objects incident to an edge, excluding endpoints declared by dummy main.
     * Isolated scene methods are not graph nodes for this operation. Parallel edges do not increase the
     * count when they have the same endpoint methods.
     *
     * @param cg non-null live graph from a valid scene
     * @return number of methods returned by {@link #getMethodsInCallGraph(CallGraph)}
     * @throws NullPointerException if {@code cg} is null
     */
    public int getCountOfNodes(CallGraph cg) {
        return getMethodsInCallGraph(cg).size();
    }


    /**
     * Classifies a class by its loaded superclass chain as Activity, Service, BroadcastReceiver,
     * ContentProvider, or non-component. This does not read the manifest or imply that the class is
     * registered, instantiated, or externally reachable. Only ancestors are inspected: passing one of
     * the Android base component classes itself does not classify it by its own name.
     *
     * @param sc non-null class with a resolved, acyclic superclass chain
     * @return the matching {@link Constants} component string, or {@link Constants#NON_COMPONENT}
     * @throws NullPointerException if {@code sc} is null
     * @throws RuntimeException if Soot cannot access the loaded superclass hierarchy
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
     * Checks whether the method is a source or target endpoint of an edge in the supplied graph.
     * Methods declared by dummy main and isolated methods are excluded; no results are cached.
     *
     * @param method borrowed method from the graph's scene, or null for a false result
     * @param cg non-null live graph to inspect
     * @return whether the method occurs among the graph's non-dummy-main endpoints
     * @throws NullPointerException if {@code cg} is null
     */
    public boolean isInCallGraph(SootMethod method, CallGraph cg) {
        return getMethodsInCallGraph(cg).contains(method);
    }

    /**
     * Checks whether the given SootMethod is called in the given CallGraph.
     * <p>
     * Examines the current graph on every call and excludes dummy main methods.
     *
     * @param method borrowed method from the graph's scene, or null for a false result
     * @param cg non-null live graph to inspect
     * @return whether at least one edge targets the method, excluding dummy-main targets
     * @throws NullPointerException if {@code cg} is null
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
     * Matching uses {@code className.startsWith(packageName)} without a package-boundary check:
     * {@code com.example} also matches {@code com.exampleother}. Include a trailing dot when a package
     * boundary is required. Method bodies are not loaded. Concrete methods in abstract classes are omitted.
     *
     * @param packageName non-null raw class-name prefix; empty matches every class considered
     * @return mutable, unordered set of borrowed concrete methods, excluding dummy main
     * @throws NullPointerException if a class is examined with a null prefix
     * @throws RuntimeException if Soot cannot enumerate the loaded method signatures
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
     * Abstract and native methods are included; bodies are not loaded.
     *
     * @return mutable, unordered set of borrowed method references
     * @throws RuntimeException if Soot cannot enumerate a loaded class's method signatures
     */
    public Set<SootMethod> getAllMethods() {
        Set<SootMethod> methods = new HashSet<>();
        for (SootClass sc : getClasses()) {
            methods.addAll(sc.getMethods());
        }
        return methods;
    }

    /**
     * Copies the current scene's class references, excluding the class named {@code dummyMainClass}.
     * No application-class or library filter is applied, and no method bodies are loaded.
     *
     * @return mutable, unordered set of borrowed class references
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
     * @return mutable, unordered set of borrowed class references, equivalent to {@link #getClasses()}
     */
    public Set<SootClass> getAllClasses() {
        return new HashSet<>(this.getClasses());
    }

    /**
     * Returns declared methods from {@link #getNonLibraryClasses()}, including abstract and native methods.
     * <p>
     * Returns a fresh set so subsequent calls reflect scene changes. Earlier results still contain borrowed
     * objects from their original scene. Bodies are not loaded, and Soot application flags are not consulted.
     *
     * @return mutable, unordered set of borrowed methods, excluding dummy main and known library classes
     * @throws RuntimeException if library-policy loading or method enumeration fails
     */
    public Set<SootMethod> getAllMethodsExceptLibraries() {
        Set<SootMethod> methods = new HashSet<>();
        for (SootClass sc : getNonLibraryClasses()) {
            methods.addAll(sc.getMethods());
        }
        return methods;
    }

    /**
     * Collects both non-null endpoints of each graph edge, excluding endpoints declared by dummy main.
     * Other endpoints of edges involving dummy main remain included. Isolated methods are not added.
     *
     * @param cg non-null live graph from a valid scene
     * @return mutable, unordered set of borrowed methods, deduplicated across edge occurrences
     * @throws NullPointerException if {@code cg} is null
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
     * Membership is determined by {@link #getNonLibraryClasses()}, independently of Soot application flags.
     *
     * @param cg non-null live graph belonging to the current scene
     * @return mutable, unordered set of borrowed endpoint methods in accepted scene classes
     * @throws NullPointerException if {@code cg} is null
     * @throws RuntimeException if loading or applying the library policy fails
     */
    public Set<SootMethod> getMethodsInCallGraphExceptLibraries(CallGraph cg) {
        Set<SootClass> nonLibraryClasses = getNonLibraryClasses();
        Set<SootMethod> methods = getMethodsInCallGraph(cg);
        methods.removeIf(method -> !nonLibraryClasses.contains(method.getDeclaringClass()));
        return methods;
    }


    /**
     * Filters {@link #getClasses()} using the shared {@link LibrariesManager} package-prefix policy.
     * This is a known-library classification, not a test of Soot application/library flags; loaded
     * framework classes can remain when their names do not match that policy.
     *
     * @return mutable, unordered set of borrowed classes outside dummy main and the known-library policy
     * @throws IllegalStateException if the bundled library-prefix resource is unavailable
     * @throws java.io.UncheckedIOException if the library-prefix resource cannot be read
     */
    public Set<SootClass> getNonLibraryClasses() {
        Set<SootClass> classes = getClasses();
        classes.removeIf(sc -> LibrariesManager.v().isLibrary(sc));
        return classes;
    }

    /**
     * Checks whether the exact class name equals {@link Constants#DUMMYMAINCLASS}.
     * No hierarchy, method, or entry-point analysis is performed.
     *
     * @param sc non-null borrowed class
     * @return true if the SootClass is a dummy main class, false otherwise
     * @throws NullPointerException if {@code sc} is null
     */
    public boolean isDummyMainClass(SootClass sc) {
        return sc.getName().equals(Constants.DUMMYMAINCLASS);
    }

    /**
     * Resets all global Soot state, configures APK input, and loads the necessary classes.
     * Uses the supplied SDK platforms directory, allows phantom references, includes all classes, and
     * disables output. Android logging and collection helper classes are also requested from the classpath.
     * This does not build Android callbacks, construct a call graph, run transformation packs, or write files.
     * The whole-program flag alone does not perform those steps.
     * <p>All earlier Soot references and managed contexts become stale. This method acquires no managed
     * analysis lease; do not call it while another analysis owns Soot. A loading failure can leave a partially
     * initialized scene and does not restore the previous state.
     *
     * @param platformPath non-null SDK platforms directory containing Android platform installations
     * @param apkPath non-null readable APK path
     * @param wholeAnalysis value to assign to Soot's whole-program option
     * @throws RuntimeException if path resolution or class loading fails; paths are not prevalidated here
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
     * Copies outgoing graph edges into an adjacency map without library or dummy-main filtering.
     * Every edge contributes a target entry, so parallel occurrences remain duplicated. Target lists follow
     * graph iteration order; map iteration order is unspecified. Target-only and isolated methods have no key.
     * Null endpoints, if present in the input graph, are retained. The graph itself is not modified.
     *
     * @param cg non-null live call graph from a valid scene
     * @return mutable map of mutable target lists containing borrowed Soot method references
     * @throws NullPointerException if {@code cg} is null
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
     * Resets and loads Soot as in {@link #setupSoot(String, String, boolean)}, but configures DEX output
     * in the requested directory and enables forced overwrite of existing Soot output files.
     * This only prepares output options: call the appropriate Soot pack/output operations separately to
     * transform and write an APK or DEX files. It does not sign or install an APK.
     * Previous Soot references and contexts become stale, including when loading subsequently fails.
     *
     * @param platformPath non-null SDK platforms directory containing Android platform installations
     * @param apkPath non-null readable APK path
     * @param outputPath non-null output directory, not a destination APK filename
     * @param wholeAnalysis value to assign to Soot's whole-program option
     * @throws RuntimeException if path resolution or class loading fails; paths are not prevalidated here
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
