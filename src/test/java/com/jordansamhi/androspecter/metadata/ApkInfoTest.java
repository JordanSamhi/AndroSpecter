package com.jordansamhi.androspecter.metadata;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pxb.android.axml.AxmlWriter;
import pxb.android.axml.NodeVisitor;
import soot.G;
import soot.Scene;
import soot.Singletons;
import soot.SootClass;
import soot.options.Options;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipOutputStream;

import static com.jordansamhi.androspecter.metadata.BinaryManifestFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class ApkInfoTest {
    @TempDir Path directory;

    @BeforeEach
    void resetBefore() { G.reset(); }

    @AfterEach
    void resetAfter() { G.reset(); }

    @Test
    void readsVersionsPermissionsAllComponentKindsAliasesAndIntentFilters() throws Exception {
        Path apk = apk(directory, "metadata.apk", manifest -> {
            number(manifest, "versionCode", 42);
            number(manifest, "versionCodeMajor", 2);
            text(manifest, "versionName", "2.4.2");
            NodeVisitor sdk = manifest.child(null, "uses-sdk");
            number(sdk, "minSdkVersion", 23);
            number(sdk, "targetSdkVersion", 35);
            child(manifest, "uses-permission", "android.permission.INTERNET");
            NodeVisitor camera = child(manifest, "uses-permission-sdk-23", "android.permission.CAMERA");
            number(camera, "maxSdkVersion", 32);
            number(camera, "usesPermissionFlags", 0x10000);
            child(manifest, "uses-permission", "android.permission.INTERNET");
            NodeVisitor app = manifest.child(null, "application");
            text(app, "permission", "example.APP_PERMISSION");
            NodeVisitor activity = child(app, "activity", ".MainActivity");
            bool(activity, "exported", false);
            bool(activity, "enabled", true);
            NodeVisitor filter = activity.child(null, "intent-filter");
            number(filter, "priority", 17);
            bool(filter, "autoVerify", true);
            child(filter, "action", "android.intent.action.VIEW");
            child(filter, "category", "android.intent.category.DEFAULT");
            child(filter, "category", "android.intent.category.BROWSABLE");
            NodeVisitor data = filter.child(null, "data");
            text(data, "scheme", "https");
            text(data, "host", "example.org");
            text(data, "pathPrefix", "/docs");
            NodeVisitor mime = filter.child(null, "data");
            text(mime, "mimeType", "text/plain");
            NodeVisitor alias = child(app, "activity-alias", "Launcher");
            text(alias, "targetActivity", ".MainActivity");
            bool(alias, "exported", true);
            NodeVisitor launcher = alias.child(null, "intent-filter");
            child(launcher, "action", "android.intent.action.MAIN");
            child(launcher, "category", "android.intent.category.LAUNCHER");
            NodeVisitor service = child(app, "service", "WorkService");
            text(service, "permission", "example.WORK");
            text(service, "process", ":worker");
            child(app, "receiver", "other.namespace.Receiver");
            NodeVisitor provider = child(app, "provider", ".DataProvider");
            text(provider, "authorities", "example.data;example.backup");
            text(provider, "readPermission", "example.READ");
            text(provider, "writePermission", "example.WRITE");
            bool(provider, "grantUriPermissions", true);
        });

        ApkInfo info = ApkInfo.read(apk);
        assertEquals("example.metadata", info.packageName());
        assertEquals((2L << 32) | 42L, info.versionCode());
        assertEquals("2.4.2", info.versionName());
        assertEquals(23, info.minSdkVersion());
        assertEquals(35, info.targetSdkVersion());
        assertEquals(Set.of("android.permission.INTERNET", "android.permission.CAMERA"), info.permissions());
        assertEquals(3, info.usesPermissions().size());
        assertEquals("uses-permission-sdk-23", info.usesPermissions().get(1).tag());
        assertEquals(32, info.usesPermissions().get(1).maxSdkVersion());
        assertEquals("65536", info.usesPermissions().get(1).attributes().get("android:usesPermissionFlags").value());
        assertEquals(List.of(ApkInfo.ComponentKind.ACTIVITY, ApkInfo.ComponentKind.ACTIVITY_ALIAS,
                ApkInfo.ComponentKind.SERVICE, ApkInfo.ComponentKind.RECEIVER, ApkInfo.ComponentKind.PROVIDER),
                info.components().stream().map(ApkInfo.Component::kind).toList());
        ApkInfo.Component activity = info.components().get(0);
        assertEquals("example.metadata.MainActivity", activity.name());
        assertEquals(".MainActivity", activity.declaredName());
        assertEquals(false, activity.declaredExported());
        assertEquals(true, activity.declaredEnabled());
        assertNull(activity.permission(), "Application-level permissions are not inherited");
        ApkInfo.IntentFilter filter = activity.intentFilters().get(0);
        assertEquals(List.of("android.intent.action.VIEW"), filter.actions());
        assertEquals(List.of("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"), filter.categories());
        assertEquals(2, filter.data().size());
        assertEquals("example.org", filter.data().get(0).get("android:host").value());
        assertEquals("/docs", filter.data().get(0).get("android:pathPrefix").value());
        assertEquals("text/plain", filter.data().get(1).get("android:mimeType").value());
        assertEquals("17", filter.attributes().get("android:priority").value());
        assertEquals("true", filter.attributes().get("android:autoVerify").value());
        ApkInfo.Component alias = info.components().get(1);
        assertEquals("example.metadata.Launcher", alias.name());
        assertEquals("example.metadata.MainActivity", alias.targetActivity());
        assertEquals(true, alias.declaredExported());
        assertEquals("example.WORK", info.components().get(2).permission());
        assertEquals(":worker", info.components().get(2).process());
        assertEquals("other.namespace.Receiver", info.components().get(3).name());
        assertNull(info.components().get(3).declaredExported());
        ApkInfo.Component provider = info.components().get(4);
        assertEquals("example.data;example.backup", provider.authorities());
        assertEquals("example.READ", provider.readPermission());
        assertEquals("example.WRITE", provider.writePermission());
        assertEquals(true, provider.grantUriPermissions());
    }

    @Test
    void preservesUnknownValuesAndDoesNotInferDefaultsOrExposureFromFilters() throws Exception {
        Path apk = apk(directory, "unknown.apk", manifest -> {
            reference(manifest, "versionName", 0x7f010001);
            NodeVisitor sdk = manifest.child(null, "uses-sdk");
            text(sdk, "minSdkVersion", "VanillaIceCream");
            reference(sdk, "targetSdkVersion", 0x7f010002);
            NodeVisitor permission = manifest.child(null, "uses-permission");
            reference(permission, "name", 0x7f010003);
            NodeVisitor app = manifest.child(null, "application");
            bool(app, "enabled", false);
            NodeVisitor activity = child(app, "activity", ".Main");
            NodeVisitor filter = activity.child(null, "intent-filter");
            child(filter, "action", "android.intent.action.MAIN");
            child(filter, "category", "android.intent.category.LAUNCHER");
            NodeVisitor service = child(app, "service", "Service");
            reference(service, "exported", 0x7f010004);
            service.attr("urn:custom", "exported", -1, NodeVisitor.TYPE_INT_BOOLEAN, true);
            NodeVisitor receiver = child(app, "receiver", ".Receiver");
            text(receiver, "exported", "not-a-boolean");
        });
        ApkInfo info = ApkInfo.read(apk);
        assertNull(info.versionCode());
        assertNull(info.versionName());
        assertEquals(new ApkInfo.AttributeValue(NodeVisitor.TYPE_REFERENCE, "@0x7f010001"),
                info.manifestAttributes().get("android:versionName"));
        assertNull(info.minSdkVersion());
        assertNull(info.targetSdkVersion());
        assertEquals("VanillaIceCream", info.sdkAttributes().get("android:minSdkVersion").value());
        assertTrue(info.permissions().isEmpty());
        assertNull(info.usesPermissions().get(0).name());
        assertNull(info.components().get(0).declaredExported(), "Intent filters do not imply an exported decision");
        assertNull(info.components().get(0).declaredEnabled(), "Application enabled is not inherited");
        assertFalse(info.components().get(0).attributes().containsKey("android:exported"));
        assertNull(info.components().get(1).declaredExported());
        assertEquals("@0x7f010004", info.components().get(1).attributes().get("android:exported").value());
        assertEquals("true", info.components().get(1).attributes().get("{urn:custom}exported").value());
        assertNull(info.components().get(2).declaredExported());
    }

    @Test
    void doesNotInitializeSootEvenForUnnamedManifestAttributes() throws Exception {
        Path apk = apk(directory, "unnamed.apk", manifest -> {
            NodeVisitor app = manifest.child(null, "application");
            NodeVisitor activity = child(app, "activity", ".Main");
            activity.attr(ANDROID, "", 0x01010010, NodeVisitor.TYPE_INT_BOOLEAN, true);
        });
        G globals = G.v();
        Field scene = Singletons.class.getDeclaredField("instance_soot_Scene");
        scene.setAccessible(true);
        Field options = Singletons.class.getDeclaredField("instance_soot_options_Options");
        options.setAccessible(true);
        assertNull(scene.get(globals));
        assertNull(options.get(globals));
        ApkInfo info = ApkInfo.read(apk);
        assertSame(globals, G.v());
        assertNull(scene.get(globals));
        assertNull(options.get(globals));
        assertEquals("true", info.components().get(0).attributes().get("android:#0x01010010").value());
        assertNull(info.components().get(0).declaredExported());
    }

    @Test
    void preservesAnExistingSceneAndProducesValuesDetachedFromItsLifetime() throws Exception {
        Scene scene = Scene.v();
        SootClass marker = new SootClass("existing.Marker");
        scene.addClass(marker);
        Options options = Options.v();
        options.set_output_dir("do-not-change");
        G globals = G.v();
        Path apk = apk(directory, "existing.apk", manifest -> manifest.child(null, "application"));
        ApkInfo info = ApkInfo.read(apk);
        assertSame(globals, G.v());
        assertSame(scene, Scene.v());
        assertEquals(List.of(marker), new ArrayList<>(scene.getClasses()));
        assertSame(options, Options.v());
        assertEquals("do-not-change", Options.v().output_dir());
        G.reset();
        Files.delete(apk);
        assertEquals("example.metadata", info.packageName());
        assertTrue(info.components().isEmpty());
    }

    @Test
    void copiesCollectionsIncludingNestedFilterData() {
        Map<String, ApkInfo.AttributeValue> attributes = new HashMap<>();
        attributes.put("android:scheme", new ApkInfo.AttributeValue(NodeVisitor.TYPE_STRING, "https"));
        List<String> actions = new ArrayList<>(List.of("example.ACTION"));
        List<Map<String, ApkInfo.AttributeValue>> data = new ArrayList<>(List.of(attributes));
        ApkInfo.IntentFilter filter = new ApkInfo.IntentFilter(actions, List.of(), data, attributes);
        List<ApkInfo.IntentFilter> filters = new ArrayList<>(List.of(filter));
        ApkInfo.Component component = new ApkInfo.Component(ApkInfo.ComponentKind.ACTIVITY,
                "example.Main", ".Main", null, null, attributes, filters);
        List<ApkInfo.Component> components = new ArrayList<>(List.of(component));
        ApkInfo info = new ApkInfo("example", null, null, null, null, List.of(), components,
                attributes, attributes, attributes);
        attributes.clear();
        actions.clear();
        data.clear();
        filters.clear();
        components.clear();
        assertEquals(List.of("example.ACTION"), info.components().get(0).intentFilters().get(0).actions());
        assertEquals("https", filter.data().get(0).get("android:scheme").value());
        assertThrows(UnsupportedOperationException.class, () -> info.components().clear());
        assertThrows(UnsupportedOperationException.class, () -> component.attributes().clear());
        assertThrows(UnsupportedOperationException.class, () -> filter.data().get(0).clear());
        assertThrows(UnsupportedOperationException.class, () -> filter.actions().clear());
    }

    @Test
    void handlesMinimalManifestsWithoutInventingSdkOrVersionValues() throws Exception {
        ApkInfo info = ApkInfo.read(apk(directory, "minimal.apk", manifest -> {}));
        assertNull(info.versionCode());
        assertNull(info.versionName());
        assertNull(info.minSdkVersion());
        assertNull(info.targetSdkVersion());
        assertTrue(info.applicationAttributes().isEmpty());
        assertTrue(info.sdkAttributes().isEmpty());
        assertTrue(info.permissions().isEmpty());
        assertTrue(info.components().isEmpty());
    }

    @Test
    void readsTheBundledRealApkWithoutAnAndroidSdk() throws Exception {
        Path apk = Path.of(getClass().getResource("/apks/managed-analysis.apk").toURI());
        ApkInfo info = ApkInfo.read(apk);
        assertFalse(info.packageName().isBlank());
        assertFalse(info.components().isEmpty());
        assertTrue(info.components().stream().anyMatch(component -> component.kind() == ApkInfo.ComponentKind.ACTIVITY));
    }

    @Test
    void reportsMissingAndMalformedManifestsAsIoFailures() throws Exception {
        assertThrows(NullPointerException.class, () -> ApkInfo.read(null));
        assertThrows(IOException.class, () -> ApkInfo.read(directory.resolve("missing.apk")));
        Path empty = directory.resolve("empty.apk");
        try (ZipOutputStream ignored = new ZipOutputStream(Files.newOutputStream(empty))) {
            // An otherwise valid ZIP without a manifest.
        }
        assertTrue(assertThrows(IOException.class, () -> ApkInfo.read(empty)).getMessage().contains("AndroidManifest.xml"));
        Path malformed = zip(directory.resolve("bad.apk"), new byte[]{1, 2, 3, 4});
        assertThrows(IOException.class, () -> ApkInfo.read(malformed));
        AxmlWriter wrongRoot = new AxmlWriter();
        wrongRoot.child(null, "not-manifest");
        assertThrows(IOException.class, () -> ApkInfo.read(zip(directory.resolve("root.apk"), wrongRoot.toByteArray())));
        AxmlWriter noPackage = new AxmlWriter();
        noPackage.child(null, "manifest");
        assertTrue(assertThrows(IOException.class,
                () -> ApkInfo.read(zip(directory.resolve("package.apk"), noPackage.toByteArray())))
                .getMessage().contains("package"));
    }
}
