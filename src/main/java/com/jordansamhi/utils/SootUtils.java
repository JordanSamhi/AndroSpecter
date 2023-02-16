package com.jordansamhi.utils;

/*-
 * #%L
 * Utils
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

import com.jordansamhi.utils.files.LibrariesManager;
import com.jordansamhi.utils.utils.Constants;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.SootMethodRef;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;
import soot.util.Chain;

import java.util.*;

/**
 * Utility class for working with the Soot framework. Provides various helper methods for working with Soot classes,
 * methods, and call graphs.
 */
public class SootUtils {

    private static SootUtils instance;
    private final Set<SootClass> nonLibraryClasses;
    private final Set<SootClass> classes;

    public SootUtils() {
        this.nonLibraryClasses = new HashSet<>();
        this.classes = new HashSet<>();
    }

    /**
     * Returns the singleton instance of this class.
     *
     * @return The singleton instance of this class.
     */
    public static SootUtils v() {
        if (instance == null) {
            instance = new SootUtils();
        }
        return instance;
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
            sm = Scene.v().getSootClass(className).getMethod(methodName);
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
     * Returns the number of edges in the given CallGraph.
     *
     * @param cg the CallGraph for which to count edges
     * @return the number of edges in the given CallGraph
     */
    public int getCountOfEdges(CallGraph cg) {
        return cg.size();
    }

    /**
     * Returns the number of nodes in the given CallGraph.
     *
     * @param cg the CallGraph for which to count nodes
     * @return the number of nodes in the given CallGraph
     */
    public int getCountOfNodes(CallGraph cg) {
        Iterator<Edge> it = cg.iterator();
        Edge next;
        SootMethod src, tgt;
        List<SootMethod> nodes = new ArrayList<>();
        while (it.hasNext()) {
            next = it.next();
            src = next.src();
            tgt = next.tgt();
            if (!nodes.contains(src)) {
                nodes.add(src);
            }
            if (!nodes.contains(tgt)) {
                nodes.add(tgt);
            }
        }
        return nodes.size();
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
        for (Edge edge : cg) {
            if (edge != null) {
                if (edge.src().equals(method) || edge.tgt().equals(method)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks whether the given SootMethod is called in the given CallGraph.
     *
     * @param method the SootMethod to check for in the CallGraph
     * @param cg     the CallGraph to search for calls to the SootMethod
     * @return true if the SootMethod is called in the CallGraph, false otherwise
     */
    public boolean isCalledInCallGraph(SootMethod method, CallGraph cg) {
        for (Edge edge : cg) {
            if (edge != null) {
                if (edge.tgt().equals(method)) {
                    return true;
                }
            }
        }
        return false;
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
     * Returns a set of all Soot methods in the Scene.
     *
     * @return a set of all Soot methods in the Scene.
     */
    public Set<SootMethod> getAllMethods() {
        Set<SootMethod> methods = new HashSet<>();
        for (SootClass sc : this.getClasses()) {
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
     *
     * @return A set of all non-library Soot methods in the Scene.
     */
    public Set<SootMethod> getAllMethodsExceptLibraries() {
        Set<SootMethod> methods = new HashSet<>();
        for (SootClass sc : this.getNonLibraryClasses()) {
            methods.addAll(sc.getMethods());
        }
        return methods;
    }

    /**
     * Returns a set of all Soot classes except those that are part of the AndroLibZoo library whitelist.
     *
     * @return A set of all non-library Soot classes in the Scene.
     */
    public Set<SootClass> getAllClassesExceptLibraries() {
        return this.getNonLibraryClasses();
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
            if (edge != null) {
                SootMethod src = edge.src();
                SootMethod tgt = edge.tgt();
                if (!isDummyMainClass(src.getDeclaringClass())) {
                    methods.add(src);
                }
                if (!isDummyMainClass(tgt.getDeclaringClass())) {
                    methods.add(tgt);
                }
            }
        }
        return methods;
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
}