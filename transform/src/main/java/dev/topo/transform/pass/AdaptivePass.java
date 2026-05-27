package dev.topo.transform.pass;

import com.google.gson.*;
import org.objectweb.asm.*;

import java.util.*;

// @category: RUNTIME
/**
 * Injects adaptive dispatch wiring at each adapted stage method:
 *   Adaptive.checkDispatch(name)                // no-op hook, preserved
 *   ++__topo_adaptive_hits_<method>             // per-method counter
 *   if ((hits & (TICK - 1)) == 0)
 *       AdaptiveMonitor.tick(name, TICK, warmupHits)
 * AdaptiveMonitor accumulates hits across calls and, on crossing
 * warmupHits, calls Adaptive.invalidate(name) once — SwitchPoint
 * invalidation then flips the registered dispatch handle from target
 * to fallback for the remainder of the process.
 */
public class AdaptivePass implements BasePass {
    private static final long TICK_INTERVAL = 8L; // must be a power of two
    private static final long DEFAULT_WARMUP_HITS = 100L;

    private static final String ADAPTIVE_INTERNAL = "dev/topo/Adaptive";
    private static final String MONITOR_INTERNAL = "dev/topo/AdaptiveMonitor";

    private final JsonObject config;
    private final JsonObject metadata;
    private final boolean forceMode;
    private final Set<String> stageFunctions;
    private final long warmupHits;
    /** host_method (qualified, `pkg::Class::method`) → the dispatch token
     *  registered with Adaptive.checkDispatch. Same shape the runtime sees,
     *  so debug formatters can reverse the lookup. Sidecar schema:
     *  `dispatch_tables:[{host_method, switchpoint}]`. */
    private final Map<String, String> dispatchTablesByMethod = new LinkedHashMap<>();

    public AdaptivePass(JsonObject config, JsonObject metadata) {
        this.config = config;
        this.metadata = metadata;
        this.forceMode = isForceMode();
        this.stageFunctions = buildStageFunctions();
        this.warmupHits = readWarmupHits();
    }

    /** Per-method adaptive instrumentations applied. PassPipeline drains
     *  this after each per-class invocation. */
    public Map<String, String> getDispatchTablesByMethod() {
        return dispatchTablesByMethod;
    }

    private boolean isForceMode() {
        if (!config.has("adaptiveCfg")) return false;
        var cfg = config.getAsJsonObject("adaptiveCfg");
        return cfg.has("mode") && "force".equals(cfg.get("mode").getAsString());
    }

    private long readWarmupHits() {
        if (!config.has("adaptiveCfg")) return DEFAULT_WARMUP_HITS;
        var cfg = config.getAsJsonObject("adaptiveCfg");
        if (cfg.has("warmupHits")) return cfg.get("warmupHits").getAsLong();
        return DEFAULT_WARMUP_HITS;
    }

    private Set<String> buildStageFunctions() {
        Set<String> functions = new HashSet<>();
        if (!metadata.has("logicBlocks")) return functions;
        for (var entry : metadata.getAsJsonObject("logicBlocks").entrySet()) {
            var block = entry.getValue().getAsJsonObject();
            if (!block.has("calledFunctions")) continue;
            var calledFunctions = block.getAsJsonArray("calledFunctions");
            String blockQName = block.get("qualifiedName").getAsString();
            String namespace = blockQName.contains("::")
                ? blockQName.substring(0, blockQName.lastIndexOf("::"))
                : "";
            for (var fnEl : calledFunctions) {
                String simpleName = fnEl.getAsString();
                String qualifiedCallee = namespace.isEmpty() ? simpleName : namespace + "::" + simpleName;
                functions.add(qualifiedCallee);
            }
        }
        return functions;
    }

    @Override
    public ClassVisitor createVisitor(ClassWriter writer) {
        return new ClassVisitor(Opcodes.ASM9, writer) {
            private String className;
            private final List<String> adaptedCounterFields = new ArrayList<>();

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
                if (shouldAdapt(className, name)) {
                    String fieldName = counterFieldName(name);
                    adaptedCounterFields.add(fieldName);
                    String hostMethod = className.replace("/", "::") + "::" + name;
                    dispatchTablesByMethod.put(hostMethod, hostMethod);
                    return new AdaptiveMethodAdapter(Opcodes.ASM9, mv,
                            className, name, fieldName, warmupHits);
                }
                return mv;
            }

            @Override
            public void visitEnd() {
                for (String fieldName : adaptedCounterFields) {
                    FieldVisitor fv = super.visitField(
                            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                            fieldName, "J", null, 0L);
                    if (fv != null) fv.visitEnd();
                }
                super.visitEnd();
            }
        };
    }

    private static String counterFieldName(String methodName) {
        // Sanitize to a valid JVM identifier — method names can contain '<', '>', '$'.
        StringBuilder sb = new StringBuilder("__topo_adaptive_hits_");
        for (int i = 0; i < methodName.length(); i++) {
            char c = methodName.charAt(i);
            sb.append(Character.isJavaIdentifierPart(c) ? c : '_');
        }
        return sb.toString();
    }

    private boolean shouldAdapt(String className, String methodName) {
        String classQ = QualifiedNameMatch.classQualified(className, methodName);
        String nsQ = QualifiedNameMatch.namespaceQualified(className, methodName);

        // Per-function check (metadata "adaptive" field, if ever serialized)
        if (metadata.has("functions") && metadata.get("functions").isJsonObject()) {
            for (var entry : metadata.getAsJsonObject("functions").entrySet()) {
                String key = entry.getKey();
                if (!key.equals(classQ) && (nsQ == null || !key.equals(nsQ))) continue;
                var fn = entry.getValue().getAsJsonObject();
                if (fn.has("adaptive") && fn.get("adaptive").getAsBoolean()) {
                    return true;
                }
            }
        }

        // Force mode: adapt all stage-mapped functions
        if (forceMode) {
            return QualifiedNameMatch.contains(stageFunctions, className, methodName);
        }

        return false;
    }

    private static class AdaptiveMethodAdapter extends MethodVisitor {
        private final String className;
        private final String fieldName;
        private final String dispatchName;
        private final long warmupHits;

        AdaptiveMethodAdapter(int api, MethodVisitor mv,
                              String className, String methodName,
                              String fieldName, long warmupHits) {
            super(api, mv);
            this.className = className;
            this.fieldName = fieldName;
            this.dispatchName = className.replace("/", "::") + "::" + methodName;
            this.warmupHits = warmupHits;
        }

        @Override
        public void visitCode() {
            super.visitCode();

            // Adaptive.checkDispatch(dispatchName)
            mv.visitLdcInsn(dispatchName);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, ADAPTIVE_INTERNAL,
                    "checkDispatch", "(Ljava/lang/String;)V", false);

            // long hits = ++__topo_adaptive_hits_<method>;
            mv.visitFieldInsn(Opcodes.GETSTATIC, className, fieldName, "J");
            mv.visitInsn(Opcodes.LCONST_1);
            mv.visitInsn(Opcodes.LADD);
            mv.visitInsn(Opcodes.DUP2);
            mv.visitFieldInsn(Opcodes.PUTSTATIC, className, fieldName, "J");

            // if ((hits & (TICK - 1)) == 0) AdaptiveMonitor.tick(name, TICK, warmupHits);
            mv.visitLdcInsn(TICK_INTERVAL - 1L);
            mv.visitInsn(Opcodes.LAND);
            mv.visitInsn(Opcodes.LCONST_0);
            mv.visitInsn(Opcodes.LCMP);
            Label skip = new Label();
            mv.visitJumpInsn(Opcodes.IFNE, skip);

            mv.visitLdcInsn(dispatchName);
            mv.visitLdcInsn(TICK_INTERVAL);
            mv.visitLdcInsn(warmupHits);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, MONITOR_INTERNAL,
                    "tick", "(Ljava/lang/String;JJ)V", false);

            // Per-Pass profile JFR event. Emitted at the
            // dispatch-tick boundary (the point at which AdaptiveMonitor
            // may invalidate the SwitchPoint), one stackless
            // topo.pass.AdaptivePass event per tick: {method,from,to}.
            // JfrNdjsonConverter routes it into pass_events.AdaptivePass[].
            mv.visitLdcInsn(dispatchName);
            mv.visitLdcInsn("target");
            mv.visitLdcInsn("fallback");
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "dev/topo/PassEvents",
                    "emitAdaptiveTransition",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                    false);

            mv.visitLabel(skip);
        }
    }
}
