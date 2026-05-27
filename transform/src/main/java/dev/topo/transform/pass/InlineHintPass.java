package dev.topo.transform.pass;

import com.google.gson.*;
import org.objectweb.asm.*;

import java.util.*;

/**
 * Adds ACC_FINAL to internal/private methods and classes to enable
 * HotSpot CHA devirtualization and aggressive inlining.
 *
 * Class-level final: applied when ALL non-constructor methods in a class
 * are declared internal or private in Topo metadata.
 *
 * Method-level final: applied to individual internal/private methods,
 * skipping constructors, abstract, and native methods.
 */
// @category: INFRA
public class InlineHintPass implements BasePass {
    private final JsonObject config;
    private final JsonObject metadata;
    private final Set<String> finalClassCandidates;
    // Qualified names of classes / methods this Pass actually finalised.
    // Aggregated by PassPipeline. The sidecar exposes them as
    // `final_classes` + `final_methods`. Used by
    // formatters to hint which classes are eligible for HotSpot CHA
    // devirtualisation.
    private final List<String> finalClasses = new ArrayList<>();
    private final List<String> finalMethods = new ArrayList<>();

    public InlineHintPass(JsonObject config, JsonObject metadata) {
        this.config = config;
        this.metadata = metadata;
        this.finalClassCandidates = computeFinalClassCandidates();
    }

    /** Classes this Pass added ACC_FINAL to (Topo `::` form). */
    public List<String> getFinalClasses() { return finalClasses; }

    /** Methods this Pass added ACC_FINAL to (`pkg::Class::method` form). */
    public List<String> getFinalMethods() { return finalMethods; }

    /**
     * Group metadata functions by class. A class is a final candidate if every
     * non-constructor method declared in metadata is internal or private.
     */
    private Set<String> computeFinalClassCandidates() {
        Set<String> candidates = new HashSet<>();
        if (!metadata.has("functions")) return candidates;

        // Group methods by class JVM internal name
        Map<String, List<JsonObject>> byClass = new HashMap<>();
        for (var entry : metadata.getAsJsonObject("functions").entrySet()) {
            var fn = entry.getValue().getAsJsonObject();
            String qn = fn.get("qualifiedName").getAsString();
            String simpleName = fn.get("simpleName").getAsString();

            // Skip constructors
            if ("<init>".equals(simpleName) || "<clinit>".equals(simpleName)) continue;

            // qualified: "pkg::Class::method" -> class = "pkg::Class" -> JVM = "pkg/Class"
            int lastSep = qn.lastIndexOf("::");
            if (lastSep < 0) continue;
            String classQN = qn.substring(0, lastSep);
            String jvmName = classQN.replace("::", "/");

            byClass.computeIfAbsent(jvmName, k -> new ArrayList<>()).add(fn);
        }

        for (var e : byClass.entrySet()) {
            boolean allInternalOrPrivate = e.getValue().stream().allMatch(fn -> {
                String vis = fn.get("visibility").getAsString();
                return "internal".equals(vis) || "private".equals(vis);
            });
            if (allInternalOrPrivate) {
                candidates.add(e.getKey());
            }
        }
        return candidates;
    }

    @Override
    public ClassVisitor createVisitor(ClassWriter writer) {
        return new ClassVisitor(Opcodes.ASM9, writer) {
            private String className;

            @Override
            public void visit(int version, int access, String name, String signature,
                             String superName, String[] interfaces) {
                this.className = name;
                int newAccess = access;
                // Add ACC_FINAL to class if all methods are internal/private,
                // but not if abstract or interface (final + abstract is illegal)
                if (finalClassCandidates.contains(name)
                        && (access & Opcodes.ACC_ABSTRACT) == 0
                        && (access & Opcodes.ACC_INTERFACE) == 0
                        && (access & Opcodes.ACC_FINAL) == 0) {
                    newAccess |= Opcodes.ACC_FINAL;
                    finalClasses.add(name.replace("/", "::"));
                }
                super.visit(version, newAccess, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                int newAccess = applyFinal(access, className, name);
                if (newAccess != access) {
                    finalMethods.add(className.replace("/", "::") + "::" + name);
                }
                return super.visitMethod(newAccess, name, descriptor, signature, exceptions);
            }
        };
    }

    private int applyFinal(int access, String className, String methodName) {
        // Skip constructors
        if ("<init>".equals(methodName) || "<clinit>".equals(methodName)) return access;
        // Skip abstract and native (final + abstract is illegal; final + native is unusual)
        if ((access & Opcodes.ACC_ABSTRACT) != 0) return access;
        if ((access & Opcodes.ACC_NATIVE) != 0) return access;

        if (!metadata.has("functions")) return access;

        String classQ = QualifiedNameMatch.classQualified(className, methodName);
        String nsQ = QualifiedNameMatch.namespaceQualified(className, methodName);

        for (var entry : metadata.getAsJsonObject("functions").entrySet()) {
            var fn = entry.getValue().getAsJsonObject();
            String name = fn.get("qualifiedName").getAsString();
            if (!name.equals(classQ) && (nsQ == null || !name.equals(nsQ))) continue;

            String vis = fn.get("visibility").getAsString();
            if ("internal".equals(vis) || "private".equals(vis)) {
                return access | Opcodes.ACC_FINAL;
            }
            break;
        }
        return access;
    }
}
