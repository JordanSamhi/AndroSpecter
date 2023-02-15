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

import com.jordansamhi.utils.utils.Constants;
import soot.*;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;
import soot.util.Chain;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class SootUtils {

    private static SootUtils instance;

    public SootUtils() {
    }

    public static SootUtils v() {
        if (instance == null) {
            instance = new SootUtils();
        }
        return instance;
    }

    /**
     * Returns a SootMethodRef with the given class name and method name.
     *
     * @param className the name of the class that contains the method
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
     * Returns a list of all superclasses of the given SootClass.
     *
     * @param sootClass the SootClass for which to retrieve all superclasses
     * @return a list of all superclasses of the given SootClass, including the immediate superclass
     */
    public List<SootClass> getAllSuperClasses(SootClass sootClass) {
        List<SootClass> classes = new ArrayList<>();
        SootClass superClass;
        if (sootClass.hasSuperclass()) {
            superClass = sootClass.getSuperclass();
            classes.add(superClass);
            classes.addAll(getAllSuperClasses(superClass));
        }
        return classes;
    }

    /**
     * Returns a list of all interfaces implemented by the given SootClass.
     *
     * @param sootClass the SootClass for which to retrieve all interfaces
     * @return a list of all interfaces implemented by the given SootClass
     */
    public List<SootClass> getAllInterfaces(SootClass sootClass) {
        List<SootClass> interfaces = new ArrayList<>();
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
     * Returns a list of the class names of the given list of SootClasses.
     *
     * @param classes the list of SootClasses for which to retrieve the class names
     * @return a list of the class names of the given SootClasses
     */
    public List<String> getClassNames(List<SootClass> classes) {
        List<String> names = new ArrayList<>();
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
        for (SootClass sc : Scene.v().getApplicationClasses()) {
            for (SootMethod sm : sc.getMethods()) {
                if (sm.isConcrete() && sm.hasActiveBody()) {
                    Body b = sm.getActiveBody();
                    if (b != null) {
                        total += b.getUnits().size();
                    }
                }
            }
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
        List<SootClass> classes = getAllSuperClasses(sc);
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
     * @param cg the CallGraph to search for the SootMethod
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
     * @param cg the CallGraph to search for calls to the SootMethod
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
     * Returns a list of all application methods that belong to the given package.
     *
     * @param packageName the name of the package for which to retrieve all application methods
     * @return a list of all application methods that belong to the given package
     */
    public List<SootMethod> getApplicationMethods(String packageName) {
        List<SootMethod> methods = new ArrayList<>();
        for (SootClass sc : Scene.v().getApplicationClasses()) {
            if (sc.getName().startsWith(packageName)) {
                if (sc.isConcrete()) {
                    for (SootMethod sm : sc.getMethods()) {
                        sm.retrieveActiveBody();
                        if (sm.isConcrete() && sm.hasActiveBody()) {
                            methods.add(sm);
                        }
                    }
                }
            }
        }
        return methods;
    }
}