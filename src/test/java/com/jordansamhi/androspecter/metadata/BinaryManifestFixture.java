package com.jordansamhi.androspecter.metadata;

import pxb.android.axml.AxmlWriter;
import pxb.android.axml.NodeVisitor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Generates reproducible manifest-only APKs without Android SDK tools or host class parsing. */
final class BinaryManifestFixture {
    static final String ANDROID = "http://schemas.android.com/apk/res/android";

    private BinaryManifestFixture() {}

    static Path apk(Path directory, String filename, Consumer<NodeVisitor> populate) throws IOException {
        AxmlWriter writer = new AxmlWriter();
        writer.ns("android", ANDROID, 0);
        NodeVisitor manifest = writer.child(null, "manifest");
        manifest.attr(null, "package", -1, NodeVisitor.TYPE_STRING, "example.metadata");
        populate.accept(manifest);
        manifest.end();
        writer.end();
        return zip(directory.resolve(filename), writer.toByteArray());
    }

    static Path zip(Path path, byte[] manifest) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zip.write(manifest);
            zip.closeEntry();
        }
        return path;
    }

    static void text(NodeVisitor node, String name, String value) {
        node.attr(ANDROID, name, -1, NodeVisitor.TYPE_STRING, value);
    }

    static void number(NodeVisitor node, String name, int value) {
        node.attr(ANDROID, name, -1, NodeVisitor.TYPE_FIRST_INT, value);
    }

    static void bool(NodeVisitor node, String name, boolean value) {
        node.attr(ANDROID, name, -1, NodeVisitor.TYPE_INT_BOOLEAN, value);
    }

    static void reference(NodeVisitor node, String name, int resource) {
        node.attr(ANDROID, name, -1, NodeVisitor.TYPE_REFERENCE, resource);
    }

    static NodeVisitor child(NodeVisitor parent, String tag, String name) {
        NodeVisitor child = parent.child(null, tag);
        text(child, "name", name);
        return child;
    }
}
