package dev.topo.transform.pass;

import com.google.gson.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Promotes instance methods to ACC_STATIC when metadata declares isStatic: true.
 *
 * Uses the Descriptor Extension approach: prepends the owner type to the method
 * descriptor so slot layout remains unchanged (slot 0 becomes an explicit parameter
 * instead of implicit "this").
 *
 * Also rewrites same-class INVOKEVIRTUAL call sites to INVOKESTATIC with the
 * extended descriptor.
 */
// @category: INFRA
public class StaticPromotionPass implements BasePass {
    private final JsonObject metadata;
    // Maps "jvmClassName.simpleName" -> jvmClassName, for methods to promote
    private final Map<String, String> promotionTargets;
    // Qualified names of methods this Pass actually promoted.
    // Aggregated by PassPipeline across all class files. The sidecar schema
    // for StaticPromotionPass is a flat string list of
    // qualified method names (formatter shows users which methods have no
    // implicit `this` in jdb because the descriptor was extended).
    private final List<String> promotedMethods = new ArrayList<>();

    public StaticPromotionPass(JsonObject metadata) {
        this.metadata = metadata;
        this.promotionTargets = buildPromotionTargets();
    }

    /** Returns the list of qualified method names promoted to static in
     *  this Pass invocation (one ClassVisitor pass = one class file).
     *  PassPipeline accumulates these across classes. Format mirrors the
     *  metadata `qualifiedName` field — `pkg::Class::method` — so users
     *  see the same identifier shape they wrote in `.topo`. */
    public List<String> getPromotedMethods() {
        return promotedMethods;
    }

    private Map<String, String> buildPromotionTargets() {
        Map<String, String> targets = new HashMap<>();
        if (!metadata.has("functions")) return targets;

        for (var entry : metadata.getAsJsonObject("functions").entrySet()) {
            var fn = entry.getValue().getAsJsonObject();
            if (!fn.has("isStatic") || !fn.get("isStatic").getAsBoolean()) continue;

            String qn = fn.get("qualifiedName").getAsString();
            String simpleName = fn.get("simpleName").getAsString();

            // Skip constructors
            if ("<init>".equals(simpleName) || "<clinit>".equals(simpleName)) continue;

            // "pkg::Class::method" -> class = "pkg::Class" -> JVM = "pkg/Class"
            int lastSep = qn.lastIndexOf("::");
            if (lastSep < 0) continue;
            String classQN = qn.substring(0, lastSep);
            String jvmClassName = classQN.replace("::", "/");

            targets.put(jvmClassName + "." + simpleName, jvmClassName);
        }
        return targets;
    }

    @Override
    public ClassVisitor createVisitor(ClassWriter writer) {
        return new ClassNode(Opcodes.ASM9) {
            @Override
            public void visitEnd() {
                super.visitEnd();
                transformClass(this);
                this.accept(writer);
            }
        };
    }

    private void transformClass(ClassNode cn) {
        // Step 1: Collect methods to promote in this class
        // Map from "name+origDesc" to newDesc
        Map<String, String> descMap = new HashMap<>();

        for (MethodNode mn : cn.methods) {
            String key = cn.name + "." + mn.name;
            if (!promotionTargets.containsKey(key)) continue;

            // Skip already static, abstract, native, constructors
            if ((mn.access & Opcodes.ACC_STATIC) != 0) continue;
            if ((mn.access & Opcodes.ACC_ABSTRACT) != 0) continue;
            if ((mn.access & Opcodes.ACC_NATIVE) != 0) continue;
            if ("<init>".equals(mn.name) || "<clinit>".equals(mn.name)) continue;

            String origDesc = mn.desc;
            String newDesc = "(L" + cn.name + ";" + origDesc.substring(1);
            descMap.put(mn.name + origDesc, newDesc);
        }

        if (descMap.isEmpty()) return;

        // Step 2: Modify method definitions
        for (MethodNode mn : cn.methods) {
            String lookupKey = mn.name + mn.desc;
            if (descMap.containsKey(lookupKey)) {
                mn.access |= Opcodes.ACC_STATIC;
                mn.desc = descMap.get(lookupKey);
                // Record the promotion. `cn.name` is the JVM
                // internal name (slashes); we reconstruct the `::` form so
                // the sidecar matches what users wrote in `.topo`
                // (e.g. `app/Main` → `app::Main::helperCompute`).
                promotedMethods.add(cn.name.replace("/", "::") + "::" + mn.name);
            }
        }

        // Step 3: Rewrite call sites in ALL methods
        for (MethodNode mn : cn.methods) {
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode call) {
                    if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && call.owner.equals(cn.name)) {
                        String lookupKey = call.name + call.desc;
                        String newDesc = descMap.get(lookupKey);
                        if (newDesc != null) {
                            call.setOpcode(Opcodes.INVOKESTATIC);
                            call.desc = newDesc;
                        }
                    }
                }
            }
        }
    }
}
