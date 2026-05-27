package dev.topo.transform.pass;

import com.google.gson.*;
import org.objectweb.asm.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Modifies method access flags to match Topo visibility declarations.
 * Maps: public->ACC_PUBLIC, protected->ACC_PROTECTED, private->ACC_PRIVATE,
 *       internal->package-private (remove all access flags).
 *
 * Records each rewrite in {@link #getRewrites()} so PassPipeline
 * can emit `<output>.topo-passes/VisibilityPass.json` after the transform
 * loop completes (per-Pass sidecar protocol).
 */
// @category: COVERED
public class VisibilityPass implements BasePass {
    private final JsonObject metadata;
    private final List<JsonObject> rewrites = new ArrayList<>();

    public VisibilityPass(JsonObject metadata) {
        this.metadata = metadata;
    }

    /** Returns the access-flag rewrites this Pass made for the single class
     *  it visited. Each entry is `{class, method, topo_visibility,
     *  prev_access, new_access}`. PassPipeline creates one VisibilityPass
     *  per class file and copies this list into its cross-class aggregate
     *  after each applyPass() call. */
    public List<JsonObject> getRewrites() {
        return rewrites;
    }

    @Override
    public ClassVisitor createVisitor(ClassWriter writer) {
        return new ClassVisitor(Opcodes.ASM9, writer) {
            private String className;

            @Override
            public void visit(int version, int access, String name, String signature,
                             String superName, String[] interfaces) {
                this.className = name;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                int newAccess = adjustAccess(access, className, name);
                if (newAccess != access) {
                    String vis = lookupTopoVis(className, name);
                    JsonObject row = new JsonObject();
                    row.addProperty("class", className);
                    row.addProperty("method", name);
                    row.addProperty("topo_visibility", vis == null ? "" : vis);
                    row.addProperty("prev_access", access);
                    row.addProperty("new_access", newAccess);
                    rewrites.add(row);
                }
                return super.visitMethod(newAccess, name, descriptor, signature, exceptions);
            }
        };
    }

    private int adjustAccess(int access, String className, String methodName) {
        String vis = lookupTopoVis(className, methodName);
        if (vis == null) return access;

        // Clear existing access flags
        int cleared = access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE);

        return switch (vis) {
            case "public" -> cleared | Opcodes.ACC_PUBLIC;
            case "protected" -> cleared | Opcodes.ACC_PROTECTED;
            case "private" -> cleared | Opcodes.ACC_PRIVATE;
            case "internal" -> cleared; // package-private = no flag
            default -> access;
        };
    }

    /** Looks up the declared .topo visibility for (class, method); null if no
     *  declaration matches. Centralised so adjustAccess() and the visitMethod
     *  rewrite-recording path agree on what counts as "rewritten by us". */
    private String lookupTopoVis(String className, String methodName) {
        if (!metadata.has("functions")) return null;

        String classQ = QualifiedNameMatch.classQualified(className, methodName);
        String nsQ = QualifiedNameMatch.namespaceQualified(className, methodName);

        for (var entry : metadata.getAsJsonObject("functions").entrySet()) {
            var fn = entry.getValue().getAsJsonObject();
            String name = fn.get("qualifiedName").getAsString();
            if (!name.equals(classQ) && (nsQ == null || !name.equals(nsQ))) continue;
            return fn.get("visibility").getAsString();
        }
        return null;
    }
}
