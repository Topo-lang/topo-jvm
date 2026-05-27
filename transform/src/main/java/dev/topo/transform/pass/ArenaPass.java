package dev.topo.transform.pass;

import com.google.gson.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.LocalVariablesSorter;

import java.util.*;

// @category: ENHANCE
/**
 * Declaration-class pass: materializes the .topo `lifetime(scope)` declaration
 * into a visible `dev/topo/Arena` try-finally lifecycle at each scope entry
 * method. The pass does NOT redirect `new T[...]` allocations; JVM lifetime
 * is valued for declaration-to-bytecode traceability, not heap-alloc rewriting.
 */
public class ArenaPass implements BasePass {
    private final JsonObject config;
    private final JsonObject metadata;
    private final Map<String, Long> scopeEntryFunctions; // qualifiedName → arenaSize
    /** host_method qualified name → injected arena size.
     *  Populated when the visitor actually wraps a method body in the
     *  Arena lifecycle (try-finally). Sidecar schema:
     *  `scopes:[{host_method, scope, size}]`. JVM only has one scope
     *  level today (lifetimeGroup.startFunc), so we emit `scope="frame"`. */
    private final Map<String, Long> injectedByMethod = new LinkedHashMap<>();

    public ArenaPass(JsonObject config, JsonObject metadata) {
        this.config = config;
        this.metadata = metadata;
        this.scopeEntryFunctions = buildScopeEntryMap();
    }

    /** Per-method injection records aggregated across classes. */
    public Map<String, Long> getInjectedByMethod() {
        return injectedByMethod;
    }

    /**
     * Qualifies a bare function name using `functions` metadata. Metadata's
     * `lifetimeGroups[*].startFunc` is emitted unqualified; cross-reference
     * `functions[*].qualifiedName` whose simpleName matches to recover the
     * namespace-qualified form that visitMethod lookups expect.
     */
    private Set<String> qualify(String bareName) {
        Set<String> result = new HashSet<>();
        if (!metadata.has("functions") || !metadata.get("functions").isJsonObject()) {
            result.add(bareName);
            return result;
        }
        for (var entry : metadata.getAsJsonObject("functions").entrySet()) {
            var fn = entry.getValue().getAsJsonObject();
            if (!fn.has("simpleName") || !fn.has("qualifiedName")) continue;
            if (bareName.equals(fn.get("simpleName").getAsString())) {
                result.add(fn.get("qualifiedName").getAsString());
            }
        }
        if (result.isEmpty()) result.add(bareName);
        return result;
    }

    private Map<String, Long> buildScopeEntryMap() {
        Map<String, Long> map = new HashMap<>();
        long defaultSize = getDefaultArenaSize();

        if (!metadata.has("lifetimeGroups")) return map;
        var groups = metadata.getAsJsonArray("lifetimeGroups");
        for (var elem : groups) {
            var group = elem.getAsJsonObject();
            String startFunc = group.get("startFunc").getAsString();
            for (String qn : qualify(startFunc)) {
                map.put(qn, defaultSize);
            }
        }
        return map;
    }

    private long getDefaultArenaSize() {
        if (!config.has("lifetimeCfg")) return 65536;
        var cfg = config.getAsJsonObject("lifetimeCfg");
        if (cfg.has("defaultArenaSize")) return cfg.get("defaultArenaSize").getAsLong();
        return 65536;
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
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                Long arenaSize = QualifiedNameMatch.get(scopeEntryFunctions, className, name);
                if (arenaSize != null) {
                    String hostMethod = className.replace("/", "::") + "::" + name;
                    injectedByMethod.put(hostMethod, arenaSize);
                    return new ArenaMethodAdapter(Opcodes.ASM9, access, descriptor, mv, arenaSize, hostMethod);
                }
                return mv;
            }
        };
    }

    private static final String ARENA_DESC = "Ldev/topo/Arena;";
    private static final String ARENA_INTERNAL = "dev/topo/Arena";

    /**
     * Wraps the scope-entry method body in a try-finally `Arena` lifecycle:
     *   Arena __arena = new Arena(size);
     *   Arena.setCurrent(__arena);
     *   try {
     *       // original body (unchanged)
     *   } finally {
     *       Arena.clearCurrent();
     *       __arena.close();
     *   }
     */
    private static final String PASS_EVENTS = "dev/topo/PassEvents";

    private static class ArenaMethodAdapter extends LocalVariablesSorter {
        private final long arenaSize;
        private final String hostMethod;
        private int arenaLocal;
        private Label tryStart;
        private Label tryEnd;
        private Label finallyHandler;

        ArenaMethodAdapter(int api, int access, String descriptor,
                          MethodVisitor mv, long arenaSize, String hostMethod) {
            super(api, access, descriptor, mv);
            this.arenaSize = arenaSize;
            this.hostMethod = hostMethod;
        }

        /** Emit one stackless topo.pass.ArenaPass event
         *  {method,scope,size,phase}. JfrNdjsonConverter routes it into
         *  pass_events.ArenaPass[]. */
        private void emitArenaEvent(String phase) {
            mv.visitLdcInsn(hostMethod);
            mv.visitLdcInsn("frame");
            mv.visitLdcInsn(arenaSize);
            mv.visitLdcInsn(phase);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, PASS_EVENTS,
                    "emitArenaLifecycle",
                    "(Ljava/lang/String;Ljava/lang/String;JLjava/lang/String;)V",
                    false);
        }

        @Override
        public void visitCode() {
            super.visitCode();

            arenaLocal = newLocal(Type.getType(ARENA_DESC));
            tryStart = new Label();
            tryEnd = new Label();
            finallyHandler = new Label();

            mv.visitTypeInsn(Opcodes.NEW, ARENA_INTERNAL);
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(arenaSize);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, ARENA_INTERNAL,
                    "<init>", "(J)V", false);
            mv.visitVarInsn(Opcodes.ASTORE, arenaLocal);

            mv.visitVarInsn(Opcodes.ALOAD, arenaLocal);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, ARENA_INTERNAL,
                    "setCurrent", "(" + ARENA_DESC + ")V", false);

            emitArenaEvent("open");

            mv.visitLabel(tryStart);
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                emitArenaEvent("close");
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, ARENA_INTERNAL,
                        "clearCurrent", "()V", false);
                mv.visitVarInsn(Opcodes.ALOAD, arenaLocal);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ARENA_INTERNAL,
                        "close", "()V", false);
            }
            super.visitInsn(opcode);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            mv.visitLabel(tryEnd);
            mv.visitLabel(finallyHandler);
            emitArenaEvent("close");
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, ARENA_INTERNAL,
                    "clearCurrent", "()V", false);
            mv.visitVarInsn(Opcodes.ALOAD, arenaLocal);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ARENA_INTERNAL,
                    "close", "()V", false);
            mv.visitInsn(Opcodes.ATHROW);

            mv.visitTryCatchBlock(tryStart, tryEnd, finallyHandler, null);

            super.visitMaxs(maxStack, maxLocals);
        }
    }
}
