package com.jordansamhi.androspecter;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soot.G;
import soot.dexpler.DexFileProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MultidexCompatibilityTest {
    @BeforeEach
    void setUp() {
        G.reset();
    }

    @AfterEach
    void tearDown() {
        G.reset();
    }

    @Test
    void sootDiscoversClassesFromEveryDexWithoutAnOptInFlag(@TempDir Path directory) throws Exception {
        Map<String, Set<String>> expected = new LinkedHashMap<>();
        expected.put("classes.dex", Collections.singleton("Lexample/Primary;"));
        expected.put("classes2.dex", Collections.singleton("Lexample/Secondary;"));
        Path apk = directory.resolve("multidex.apk");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(apk))) {
            for (Map.Entry<String, Set<String>> entry : expected.entrySet()) {
                ClassDef clazz = new ImmutableClassDef(entry.getValue().iterator().next(), AccessFlags.PUBLIC.getValue(),
                        "Ljava/lang/Object;", Collections.emptyList(), null, Collections.emptySet(),
                        Collections.emptyList(), Collections.emptyList());
                Path dex = directory.resolve(entry.getKey());
                DexFileFactory.writeDexFile(dex.toString(), new ImmutableDexFile(Opcodes.getDefault(), Collections.singleton(clazz)));
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                Files.copy(dex, zip);
                zip.closeEntry();
            }
        }

        // Soot 4.7 discovers all DEX entries by default; no process_multiple_dex option is needed.
        List<DexFileProvider.DexContainer<? extends DexFile>> containers = DexFileProvider.v().getDexFromSource(apk.toFile());
        Map<String, Set<String>> discovered = new HashMap<>();
        for (DexFileProvider.DexContainer<? extends DexFile> container : containers) {
            Set<String> classes = new HashSet<>();
            for (ClassDef clazz : container.getBase().getDexFile().getClasses()) {
                classes.add(clazz.getType());
            }
            discovered.put(container.getDexName(), classes);
        }
        assertEquals(2, containers.size());
        assertEquals(expected, discovered);
    }
}
