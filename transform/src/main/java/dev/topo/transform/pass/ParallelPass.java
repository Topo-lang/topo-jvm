package dev.topo.transform.pass;

import com.google.gson.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.LocalVariablesSorter;

import java.util.*;

/**
 * Transforms sequential method calls in parallel stages into
 * dev.topo.Parallel.spawn() + awaitAll() patterns.
 *
 * For methods identified as orchestrators of parallel logic blocks (logic blocks
 * containing stages with 2+ functions), eligible void static calls are wrapped
 * in Parallel.spawn() via lambda (INVOKEDYNAMIC), collected into a Future list,
 * and awaited before every return instruction.
 */
// @category: ENHANCE
// Demoted OPT → ENHANCE.
// Rationale: `stage<N>` is a declaration-class feature — the pass materialises
// the declaration as `Parallel.spawn() + awaitAll()` bytecode witness. Real
// speedup happens when the workload's task grain is big enough to amortise
// ForkJoinPool spawn/await cost; it is **workload + hardware dependent** and
// therefore fails the OPT entry rule requiring optimisations not to depend on
// runtime data, the same structural reason LLVM TopoParallelPass was demoted.
public class ParallelPass implements BasePass {
    private final JsonObject config;
    private final JsonObject metadata;
    /** Qualified names of methods that orchestrate parallel work. */
    private final Set<String> orchestrators;
    /** host_method → spawn_sites count. Populated as the adapter wraps
     *  eligible INVOKESTATIC()V calls in Parallel.spawn(). Sidecar schema:
     *  `parallel_split:[{host_method, spawn_sites:<int>}]`. */
    private final Map<String, Integer> spawnSitesByMethod = new LinkedHashMap<>();

    public ParallelPass(JsonObject config, JsonObject metadata) {
        this.config = config;
        this.metadata = metadata;
        this.orchestrators = buildOrchestratorSet();
    }

    /** Per-method spawn-site counts aggregated across visited classes. */
    public Map<String, Integer> getSpawnSitesByMethod() {
        return spawnSitesByMethod;
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
                if (shouldParallelize(className, name)) {
                    String hostMethod = className.replace("/", "::") + "::" + name;
                    return new ParallelMethodAdapter(Opcodes.ASM9, access, descriptor, mv,
                            hostMethod, ParallelPass.this);
                }
                return mv;
            }
        };
    }

    /**
     * Builds the set of orchestrator method qualified names: logic block owners
     * that contain at least one stage with 2+ functions.
     */
    private Set<String> buildOrchestratorSet() {
        Set<String> result = new HashSet<>();
        if (!metadata.has("logicBlocks")) return result;
        for (var entry : metadata.getAsJsonObject("logicBlocks").entrySet()) {
            var block = entry.getValue().getAsJsonObject();
            if (!block.has("calledFunctions") || !block.has("stages")) continue;
            var calledFunctions = block.getAsJsonArray("calledFunctions");
            var stages = block.getAsJsonArray("stages");
            // Group by stage to find parallel stages
            Map<Integer, Integer> stageCounts = new HashMap<>();
            for (int i = 0; i < calledFunctions.size() && i < stages.size(); i++) {
                int stage = stages.get(i).getAsInt();
                stageCounts.merge(stage, 1, Integer::sum);
            }
            // If any stage has 2+ functions, this block's owner is an orchestrator
            boolean hasParallelStage = stageCounts.values().stream().anyMatch(c -> c >= 2);
            if (hasParallelStage) {
                result.add(block.get("qualifiedName").getAsString());
            }
        }
        return result;
    }

    private boolean shouldParallelize(String className, String methodName) {
        // Full class-qualified: app::Main::runFriendly
        String qualifiedName = className.replace("/", "::") + "::" + methodName;
        if (orchestrators.contains(qualifiedName)) return true;

        // Namespace-level: app::runFriendly (Topo namespace declarations
        // omit the class name — derive the package path from the JVM class name)
        int lastSlash = className.lastIndexOf('/');
        if (lastSlash >= 0) {
            String namespace = className.substring(0, lastSlash).replace("/", "::");
            if (orchestrators.contains(namespace + "::" + methodName)) return true;
        }

        return false;
    }

    /**
     * Method adapter that wraps eligible void static calls in Parallel.spawn(),
     * collects the Futures into a list, and calls awaitAllVoid before each return.
     */
    private static class ParallelMethodAdapter extends LocalVariablesSorter {
        private int futureListLocal;
        private final String hostMethod;
        private final ParallelPass outer;
        private int spawnSites = 0;

        ParallelMethodAdapter(int api, int access, String descriptor, MethodVisitor mv,
                              String hostMethod, ParallelPass outer) {
            super(api, access, descriptor, mv);
            futureListLocal = newLocal(Type.getType("Ljava/util/ArrayList;"));
            this.hostMethod = hostMethod;
            this.outer = outer;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            // ArrayList<Future<?>> futures = new ArrayList<>();
            mv.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList",
                    "<init>", "()V", false);
            mv.visitVarInsn(Opcodes.ASTORE, futureListLocal);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                                     String descriptor, boolean isInterface) {
            if (opcode == Opcodes.INVOKESTATIC && descriptor.equals("()V")) {
                // Wrap in lambda via INVOKEDYNAMIC -> Parallel.spawn() -> add to list
                Handle bootstrap = new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/invoke/LambdaMetafactory",
                        "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;"
                                + "Ljava/lang/String;"
                                + "Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodHandle;"
                                + "Ljava/lang/invoke/MethodType;"
                                + ")Ljava/lang/invoke/CallSite;",
                        false
                );
                Handle target = new Handle(Opcodes.H_INVOKESTATIC, owner, name,
                        descriptor, isInterface);
                mv.visitInvokeDynamicInsn(
                        "run",
                        "()Ljava/lang/Runnable;",
                        bootstrap,
                        Type.getType("()V"),
                        target,
                        Type.getType("()V")
                );

                // Future<?> f = Parallel.spawn(runnable);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "dev/topo/Parallel", "spawn",
                        "(Ljava/lang/Runnable;)Ljava/util/concurrent/Future;", false);

                // futures.add(f);
                mv.visitVarInsn(Opcodes.ALOAD, futureListLocal);
                mv.visitInsn(Opcodes.SWAP);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "add",
                        "(Ljava/lang/Object;)Z", false);
                mv.visitInsn(Opcodes.POP); // discard boolean

                // One stackless topo.pass.ParallelPass event
                // per spawn site {method,spawnSite}. JfrNdjsonConverter
                // routes it into pass_events.ParallelPass[]. spawnSite is
                // the wrapped callee `owner::name` so the trace shows what
                // was parallelised.
                mv.visitLdcInsn(hostMethod);
                mv.visitLdcInsn(owner.replace("/", "::") + "::" + name);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "dev/topo/PassEvents",
                        "emitParallelSpawn",
                        "(Ljava/lang/String;Ljava/lang/String;)V", false);

                spawnSites++;
            } else {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                // Emit: Parallel.awaitAllVoid(futures.toArray(new Future[0]))
                mv.visitVarInsn(Opcodes.ALOAD, futureListLocal);
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/util/concurrent/Future");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "toArray",
                        "([Ljava/lang/Object;)[Ljava/lang/Object;", false);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/util/concurrent/Future;");
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "dev/topo/Parallel", "awaitAllVoid",
                        "([Ljava/util/concurrent/Future;)V", false);
            }
            super.visitInsn(opcode);
        }

        @Override
        public void visitEnd() {
            super.visitEnd();
            if (spawnSites > 0) {
                outer.spawnSitesByMethod.merge(hostMethod, spawnSites, Integer::sum);
            }
        }
    }
}
