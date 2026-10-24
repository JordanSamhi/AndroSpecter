package com.jordansamhi.androspecter.metadata;

import pxb.android.axml.AxmlReader;
import pxb.android.axml.AxmlVisitor;
import pxb.android.axml.NodeVisitor;
import pxb.android.axml.ValueWrapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Immutable metadata read directly from an APK's binary AndroidManifest.xml, without an SDK,
 * resource-table resolution, or any Soot initialization or reset. Values remain usable across
 * threads and after the file or an unrelated analysis session closes. This describes declarations,
 * not Android's effective defaults, permission grants, component reachability, or exposure.
 * <p>Nullable convenience values mean absent, unresolved, or of an unsupported type. Raw attribute
 * maps distinguish absence from a declaration that could not be interpreted. Attribute keys are
 * {@code android:name} for the Android namespace, plain names without a namespace, and
 * {@code {namespace-uri}name} otherwise. Unnamed attributes use {@code #0xRESOURCEID} as their name.
 * Lists preserve declaration order and duplicate declarations; raw attribute-map iteration
 * order is unspecified. These parsing conventions describe {@link #read(Path)}. Public
 * record constructors also allow caller-supplied data and do not validate manifest semantics
 * or consistency between convenience fields and raw attributes.
 *
 * @param packageName non-null manifest package name
 * @param versionCode combined versionCodeMajor/versionCode, or null if absent or unresolved
 * @param versionName literal version name, or null if absent or resource-backed
 * @param minSdkVersion declared numeric minimum SDK, or null; no platform default is inferred
 * @param targetSdkVersion declared numeric target SDK, or null; codenames remain in sdkAttributes
 * @param usesPermissions non-null permission declarations without null elements; copied to an immutable list
 *                        including SDK-qualified declarations when read from an APK
 * @param components non-null component declarations without null elements; copied to an immutable list,
 *                   in manifest order including activity aliases when read from an APK
 * @param manifestAttributes non-null raw manifest attributes; copied to an immutable map without null entries
 * @param applicationAttributes non-null raw application attributes; copied to an immutable map without null
 *                              entries, empty when no application is declared in an APK
 * @param sdkAttributes non-null raw SDK attributes; copied to an immutable map without null entries,
 *                      representing the first uses-sdk element, or an empty map, when read from an APK
 */
public record ApkInfo(String packageName, Long versionCode, String versionName,
                      Integer minSdkVersion, Integer targetSdkVersion,
                      List<UsesPermission> usesPermissions, List<Component> components,
                      Map<String, AttributeValue> manifestAttributes,
                      Map<String, AttributeValue> applicationAttributes,
                      Map<String, AttributeValue> sdkAttributes) {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final int TYPE_INT_DEC = 0x10;
    private static final int TYPE_ATTRIBUTE = 0x02;

    /**
     * Creates detached metadata, defensively copying all supplied collections. Lists retain
     * their order and duplicates; map iteration order is unspecified. Inputs must remain stable
     * during copying. No package syntax, version range, SDK range, or cross-field consistency
     * checks are performed beyond the documented null checks.
     *
     * @param packageName non-null package name
     * @param versionCode combined version code, or null
     * @param versionName literal version name, or null
     * @param minSdkVersion declared numeric minimum SDK, or null
     * @param targetSdkVersion declared numeric target SDK, or null
     * @param usesPermissions non-null permission declarations without null elements
     * @param components non-null component declarations without null elements
     * @param manifestAttributes non-null raw attributes without null keys or values
     * @param applicationAttributes non-null raw attributes without null keys or values
     * @param sdkAttributes non-null raw attributes without null keys or values
     * @throws NullPointerException if a required value, collection, element, key, or value is null
     */
    public ApkInfo {
        Objects.requireNonNull(packageName, "packageName");
        usesPermissions = List.copyOf(usesPermissions);
        components = List.copyOf(components);
        manifestAttributes = Map.copyOf(manifestAttributes);
        applicationAttributes = Map.copyOf(applicationAttributes);
        sdkAttributes = Map.copyOf(sdkAttributes);
    }

    /**
     * Reads only the APK's binary AndroidManifest.xml and closes all file resources before returning.
     * No Soot globals are accessed, so this operation does not acquire or invalidate an analysis session.
     * Resource references are preserved as typed raw attributes rather than resolved through resources.arsc.
     * Relative and bare component names and alias targets are expanded against the manifest package.
     * Only direct manifest/application children represented by this record are extracted;
     * arbitrary nested elements are not exposed. APK installation validity, signatures, and
     * split-APK merging are not checked. The manifest is read fully into memory.
     * <pre>{@code
     * ApkInfo info = ApkInfo.read(Path.of("app.apk"));
     * System.out.println(info.packageName());
     * info.components().forEach(component -> System.out.println(component.name()));
     * }</pre>
     *
     * @param apk non-null readable APK ZIP file; no Android SDK directory is needed
     * @return immutable detached metadata
     * @throws NullPointerException if apk is null
     * @throws IOException if the ZIP or manifest cannot be read, the binary XML is malformed,
     *         or the manifest has no literal package name or has multiple application elements
     */
    public static ApkInfo read(Path apk) throws IOException {
        Objects.requireNonNull(apk, "apk");
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            if (entry == null || entry.isDirectory()) {
                throw new IOException("APK contains no AndroidManifest.xml: " + apk);
            }
            try (InputStream stream = zip.getInputStream(entry)) {
                return parse(stream.readAllBytes());
            }
        } catch (RuntimeException ex) {
            throw new IOException("Cannot parse APK manifest: " + apk, ex);
        }
    }

    /**
     * Returns the unique literal names from all uses-permission variants, preserving declaration order.
     * Unresolved names are omitted here but remain represented in {@link #usesPermissions()}.
     * SDK limits and flags are not evaluated; these are requests, not granted permissions.
     *
     * @return immutable set of declared literal permission names in first-declaration order,
     *         empty when none are available
     */
    public Set<String> permissions() {
        Set<String> names = new LinkedHashSet<>();
        for (UsesPermission permission : usesPermissions) {
            if (permission.name() != null) names.add(permission.name());
        }
        return Collections.unmodifiableSet(names);
    }

    /** The manifest element kind; aliases remain distinct from their target activities. */
    public enum ComponentKind {
        /** An activity declaration. */
        ACTIVITY,
        /** An activity-alias declaration, not a separate implementation class. */
        ACTIVITY_ALIAS,
        /** A service declaration. */
        SERVICE,
        /** A broadcast receiver declaration. */
        RECEIVER,
        /** A content provider declaration. */
        PROVIDER
    }

    /**
     * One binary XML attribute represented without mutable parser objects.
     * The type is Android's binary XML value-type number. Strings remain literal; integers are decimal;
     * booleans are {@code true}/{@code false}; references use {@code @0x12345678} and attribute references
     * use {@code ?0x12345678}. Other numeric types retain their encoded integer bits, not a decoded unit.
     * These representations describe values produced by {@link ApkInfo#read(Path)}. The generated
     * constructor stores the supplied type and nullable text without validation or normalization.
     *
     * @param type binary XML type, for example 3 for string, 1 for resource reference, 18 for boolean
     * @param value encoded scalar text, or null for a value without scalar content
     */
    public record AttributeValue(int type, String value) {
    }

    /**
     * One permission request, without applying SDK limits or resolving resource references.
     *
     * @param tag non-null tag; APK parsing produces uses-permission, uses-permission-sdk-23, or uses-permission-sdk-m
     * @param name literal permission name, or null if absent or unresolved
     * @param maxSdkVersion declared numeric maximum SDK, or null
     * @param attributes non-null raw attributes without null keys or values, copied to an immutable map;
     *                   includes any usesPermissionFlags when read from an APK
     */
    public record UsesPermission(String tag, String name, Integer maxSdkVersion,
                                 Map<String, AttributeValue> attributes) {
        /**
         * Creates a permission declaration with copied attributes. The tag and SDK value
         * are not validated or reconciled with the raw attribute map. Map order is unspecified.
         * @param tag non-null declaration tag
         * @param name literal permission name, or null
         * @param maxSdkVersion declared numeric maximum SDK, or null
         * @param attributes non-null raw attributes without null keys or values
         * @throws NullPointerException if tag or the attribute map or any entry is null
         */
        public UsesPermission {
            Objects.requireNonNull(tag, "tag");
            attributes = Map.copyOf(attributes);
        }
    }

    /**
     * One component's own declarations; application attributes are not inherited here.
     * Exported is deliberately nullable and does not represent effective Android exposure. An alias's
     * name identifies the alias, while targetActivity identifies its declared implementation target.
     * Convenience methods read this record's raw attributes regardless of component kind. They
     * return null for absent, unresolved, or unsupported values and never consult Android defaults,
     * application-level values, or an alias target. Returned values remain detached from the APK.
     *
     * @param kind non-null component kind
     * @param name fully qualified component or alias name, or null if absent or unresolved
     * @param declaredName literal name before expansion, or null if absent or unresolved
     * @param targetActivity expanded alias target, or null if absent or unresolved
     * @param declaredExported literal android:exported, or null if absent, unresolved, or invalid
     * @param attributes non-null raw attributes without null keys or values, copied to an immutable map
     * @param intentFilters non-null intent filters without null elements, copied to an immutable list;
     *                      APK parsing includes direct children in manifest order
     */
    public record Component(ComponentKind kind, String name, String declaredName, String targetActivity,
                            Boolean declaredExported, Map<String, AttributeValue> attributes,
                            List<IntentFilter> intentFilters) {
        /**
         * Creates a component declaration with copied collections. Names, kind-specific fields,
         * and the declared-exported value are not checked against each other or the raw attributes.
         * List order and duplicates are retained; map iteration order is unspecified.
         * @param kind non-null component kind
         * @param name expanded name, or null
         * @param declaredName original literal name, or null
         * @param targetActivity expanded alias target, or null
         * @param declaredExported declared boolean, or null; never an inferred exposure decision
         * @param attributes non-null raw attributes without null keys or values
         * @param intentFilters non-null intent filters without null entries
         * @throws NullPointerException if kind, a collection, or a collection entry is null
         */
        public Component {
            Objects.requireNonNull(kind, "kind");
            attributes = Map.copyOf(attributes);
            intentFilters = List.copyOf(intentFilters);
        }

        /**
         * Reads this component's {@code android:enabled} as a literal boolean.
         * @return the declared flag, or null when absent, unresolved, or invalid; no default or application value is inherited
         */
        public Boolean declaredEnabled() { return bool(attributes, "android:enabled"); }

        /**
         * Reads this component's literal {@code android:permission} string.
         * @return the declared permission, or null when absent or unresolved; application permission is not inherited
         */
        public String permission() { return string(attributes, "android:permission"); }

        /**
         * Reads this component's literal {@code android:process} string.
         * @return the declaration, or null when absent or unresolved; relative process names remain unexpanded
         */
        public String process() { return string(attributes, "android:process"); }

        /**
         * Reads the literal {@code android:authorities} string without splitting it.
         * @return declared authorities, including semicolon separators, or null when absent or unresolved
         */
        public String authorities() { return string(attributes, "android:authorities"); }

        /**
         * Reads the literal {@code android:readPermission} string.
         * @return the declared read permission, or null when absent or unresolved; no permission fallback is applied
         */
        public String readPermission() { return string(attributes, "android:readPermission"); }

        /**
         * Reads the literal {@code android:writePermission} string.
         * @return the declared write permission, or null when absent or unresolved; no permission fallback is applied
         */
        public String writePermission() { return string(attributes, "android:writePermission"); }

        /**
         * Reads {@code android:grantUriPermissions} as a literal boolean.
         * @return the declared flag, or null when absent, unresolved, or invalid; no platform default is inferred
         */
        public Boolean grantUriPermissions() { return bool(attributes, "android:grantUriPermissions"); }
    }

    /**
     * One direct component intent-filter declaration. This is not an intent-matching evaluator.
     * Only literal action/category names are listed; unresolved names are omitted. Each data element
     * remains a separate map, preserving scheme/host/path/MIME and other attribute combinations as declared.
     * No matching combinations or Android defaults are inferred.
     *
     * @param actions non-null action names without null elements, copied in order to an immutable list
     * @param categories non-null category names without null elements, copied in order to an immutable list
     * @param data non-null raw maps for direct data children, copied in order to an immutable list
     *             of immutable maps; null maps, keys, and values are not allowed
     * @param attributes non-null raw filter attributes without null entries, copied to an immutable map;
     *                   includes priority and autoVerify when declared
     */
    public record IntentFilter(List<String> actions, List<String> categories,
                               List<Map<String, AttributeValue>> data, Map<String, AttributeValue> attributes) {
        /**
         * Creates an intent-filter declaration, copying the lists, map, and nested data maps.
         * List order and duplicates are retained; map iteration order is unspecified. Values
         * are not checked for Android intent-filter validity. Inputs must remain stable during copying.
         * @param actions non-null literal action names without null entries
         * @param categories non-null literal category names without null entries
         * @param data non-null data-element maps without null maps, keys, or values
         * @param attributes non-null raw filter attributes without null keys or values
         * @throws NullPointerException if any collection, element, key, or value is null
         */
        public IntentFilter {
            actions = List.copyOf(actions);
            categories = List.copyOf(categories);
            data = data.stream().map(Map::copyOf).toList();
            attributes = Map.copyOf(attributes);
        }
    }

    private static ApkInfo parse(byte[] bytes) throws IOException {
        List<Node> roots = new ArrayList<>();
        new AxmlReader(bytes).accept(new AxmlVisitor() {
            @Override
            public NodeVisitor child(String namespace, String name) {
                Node root = new Node(name);
                roots.add(root);
                return visitor(root);
            }
        });
        if (roots.size() != 1 || !"manifest".equals(roots.get(0).tag)) {
            throw new IOException("Expected one manifest root element");
        }
        Node manifest = roots.get(0);
        String packageName = string(manifest.attributes, "package");
        if (packageName == null || packageName.isBlank()) {
            throw new IOException("Manifest has no literal package name");
        }
        List<Node> applications = children(manifest, "application");
        if (applications.size() > 1) throw new IOException("Manifest has multiple application elements");
        Node application = applications.isEmpty() ? new Node("application") : applications.get(0);
        List<Node> sdkNodes = children(manifest, "uses-sdk");
        Map<String, AttributeValue> sdk = sdkNodes.isEmpty() ? Map.of() : sdkNodes.get(0).attributes;
        List<UsesPermission> permissions = new ArrayList<>();
        for (Node child : manifest.children) {
            if (Set.of("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m").contains(child.tag)) {
                permissions.add(new UsesPermission(child.tag, string(child.attributes, "android:name"),
                        integer(child.attributes, "android:maxSdkVersion"), child.attributes));
            }
        }
        List<Component> components = new ArrayList<>();
        for (Node child : application.children) {
            ComponentKind kind = kind(child.tag);
            if (kind == null) continue;
            String declared = string(child.attributes, "android:name");
            List<IntentFilter> filters = new ArrayList<>();
            for (Node filter : children(child, "intent-filter")) filters.add(intentFilter(filter));
            components.add(new Component(kind, expand(packageName, declared), declared,
                    kind == ComponentKind.ACTIVITY_ALIAS
                            ? expand(packageName, string(child.attributes, "android:targetActivity")) : null,
                    bool(child.attributes, "android:exported"), child.attributes, filters));
        }
        Integer low = integer(manifest.attributes, "android:versionCode");
        Integer high = integer(manifest.attributes, "android:versionCodeMajor");
        Long version = low == null || (high == null && manifest.attributes.containsKey("android:versionCodeMajor"))
                ? null : ((long) (high == null ? 0 : high) << 32) | Integer.toUnsignedLong(low);
        return new ApkInfo(packageName, version, string(manifest.attributes, "android:versionName"),
                integer(sdk, "android:minSdkVersion"), integer(sdk, "android:targetSdkVersion"),
                permissions, components, manifest.attributes, application.attributes, sdk);
    }

    private static IntentFilter intentFilter(Node node) {
        List<String> actions = new ArrayList<>();
        List<String> categories = new ArrayList<>();
        List<Map<String, AttributeValue>> data = new ArrayList<>();
        for (Node child : node.children) {
            String name = string(child.attributes, "android:name");
            if ("action".equals(child.tag) && name != null) actions.add(name);
            if ("category".equals(child.tag) && name != null) categories.add(name);
            if ("data".equals(child.tag)) data.add(child.attributes);
        }
        return new IntentFilter(actions, categories, data, node.attributes);
    }

    private static NodeVisitor visitor(Node node) {
        return new NodeVisitor() {
            @Override
            public void attr(String namespace, String name, int resourceId, int type, Object value) {
                String local = name == null || name.isEmpty() ? String.format("#0x%08x", resourceId) : name;
                String key = ANDROID.equals(namespace) ? "android:" + local
                        : namespace == null || namespace.isEmpty() ? local : "{" + namespace + "}" + local;
                if (value instanceof ValueWrapper wrapper) {
                    value = type == NodeVisitor.TYPE_STRING ? wrapper.raw : wrapper.ref;
                }
                String scalar = value == null ? null : value.toString();
                if ((type == NodeVisitor.TYPE_REFERENCE || type == TYPE_ATTRIBUTE) && value instanceof Integer number) {
                    scalar = (type == TYPE_ATTRIBUTE ? "?" : "@") + String.format("0x%08x", number);
                }
                node.attributes.put(key, new AttributeValue(type, scalar));
            }

            @Override
            public NodeVisitor child(String namespace, String name) {
                Node child = new Node(name);
                node.children.add(child);
                return visitor(child);
            }
        };
    }

    private static List<Node> children(Node parent, String tag) {
        return parent.children.stream().filter(child -> tag.equals(child.tag)).toList();
    }

    private static ComponentKind kind(String tag) {
        if (tag == null) return null;
        return switch (tag) {
            case "activity" -> ComponentKind.ACTIVITY;
            case "activity-alias" -> ComponentKind.ACTIVITY_ALIAS;
            case "service" -> ComponentKind.SERVICE;
            case "receiver" -> ComponentKind.RECEIVER;
            case "provider" -> ComponentKind.PROVIDER;
            default -> null;
        };
    }

    private static String expand(String packageName, String name) {
        if (name == null || name.isBlank()) return null;
        if (name.startsWith(".")) return packageName + name;
        return name.contains(".") ? name : packageName + "." + name;
    }

    private static String string(Map<String, AttributeValue> attributes, String key) {
        AttributeValue value = attributes.get(key);
        return value != null && value.type() == NodeVisitor.TYPE_STRING ? value.value() : null;
    }

    private static Integer integer(Map<String, AttributeValue> attributes, String key) {
        AttributeValue value = attributes.get(key);
        if (value == null || (value.type() != TYPE_INT_DEC && value.type() != NodeVisitor.TYPE_INT_HEX
                && value.type() != NodeVisitor.TYPE_STRING)) return null;
        try {
            return value.value() == null ? null : Integer.decode(value.value());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Boolean bool(Map<String, AttributeValue> attributes, String key) {
        AttributeValue value = attributes.get(key);
        if (value == null || (value.type() != NodeVisitor.TYPE_INT_BOOLEAN && value.type() != NodeVisitor.TYPE_STRING)) {
            return null;
        }
        if ("true".equals(value.value())) return true;
        if ("false".equals(value.value())) return false;
        return null;
    }

    private static final class Node {
        private final String tag;
        private final Map<String, AttributeValue> attributes = new LinkedHashMap<>();
        private final List<Node> children = new ArrayList<>();
        private Node(String tag) { this.tag = tag; }
    }
}
