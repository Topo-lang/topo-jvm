package dev.topo.transform.pass;

import com.google.gson.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.LocalVariablesSorter;

import java.util.*;

/**
 * Injects JDK Flight Recorder (JFR) events at stage boundaries.
 * Wraps method bodies with event begin/end calls.
 */
// @category: INSTRUMENT
public class ObservabilityPass implements BasePass {
    private final JsonObject config;
    private final JsonObject metadata;
    private final Map<String, Integer> stageMap;

    public ObservabilityPass(JsonObject config, JsonObject metadata) {
        this.config = config;
        this.metadata = metadata;
        this.stageMap = buildStageMap();
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
                if (shouldInstrument(className, name)) {
                    int stageOrder = getStageOrder(className, name);
                    return new ObservabilityMethodAdapter(
                            Opcodes.ASM9, access, descriptor, mv, className, name, stageOrder);
                }
                return mv;
            }
        };
    }

    private Map<String, Integer> buildStageMap() {
        Map<String, Integer> map = new HashMap<>();
        if (!metadata.has("logicBlocks")) return map;
        for (var entry : metadata.getAsJsonObject("logicBlocks").entrySet()) {
            var block = entry.getValue().getAsJsonObject();
            if (!block.has("calledFunctions") || !block.has("stages")) continue;
            var calledFunctions = block.getAsJsonArray("calledFunctions");
            var stages = block.getAsJsonArray("stages");
            String blockQName = block.get("qualifiedName").getAsString();
            String namespace = blockQName.contains("::")
                ? blockQName.substring(0, blockQName.lastIndexOf("::"))
                : "";
            for (int i = 0; i < calledFunctions.size() && i < stages.size(); i++) {
                String simpleName = calledFunctions.get(i).getAsString();
                String qualifiedCallee = namespace.isEmpty() ? simpleName : namespace + "::" + simpleName;
                map.put(qualifiedCallee, stages.get(i).getAsInt());
            }
        }
        return map;
    }

    private boolean shouldInstrument(String className, String methodName) {
        return QualifiedNameMatch.containsKey(stageMap, className, methodName);
    }

    private int getStageOrder(String className, String methodName) {
        Integer v = QualifiedNameMatch.get(stageMap, className, methodName);
        return v != null ? v : 0;
    }

    private static class ObservabilityMethodAdapter extends LocalVariablesSorter {
        private final String className;
        private final String methodName;
        private final int stageOrder;
        private int eventLocal;

        ObservabilityMethodAdapter(int api, int access, String descriptor,
                                   MethodVisitor mv, String className,
                                   String methodName, int stageOrder) {
            super(api, access, descriptor, mv);
            this.className = className;
            this.methodName = methodName;
            this.stageOrder = stageOrder;
            this.eventLocal = newLocal(Type.getType("Ldev/topo/Observe$StageEvent;"));
        }

        @Override
        public void visitCode() {
            super.visitCode();

            String stageName = className.replace("/", "::") + "::" + methodName;

            // NEW StageEvent -> DUP -> <init> -> DUP -> set stageName -> DUP -> set stageOrder -> DUP -> begin() -> ASTORE
            mv.visitTypeInsn(Opcodes.NEW, "dev/topo/Observe$StageEvent");
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "dev/topo/Observe$StageEvent",
                    "<init>", "()V", false);

            // Set stageName field
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(stageName);
            mv.visitFieldInsn(Opcodes.PUTFIELD, "dev/topo/Observe$StageEvent",
                    "stageName", "Ljava/lang/String;");

            // Set stageOrder field
            mv.visitInsn(Opcodes.DUP);
            pushInt(mv, stageOrder);
            mv.visitFieldInsn(Opcodes.PUTFIELD, "dev/topo/Observe$StageEvent",
                    "stageOrder", "I");

            // DUP before begin() so one copy remains on stack after the void call
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "dev/topo/Observe$StageEvent",
                    "begin", "()V", false);

            // Store the remaining reference
            mv.visitVarInsn(Opcodes.ASTORE, eventLocal);
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                mv.visitVarInsn(Opcodes.ALOAD, eventLocal);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "dev/topo/Observe",
                        "endStage", "(Ldev/topo/Observe$StageEvent;)V", false);
            }
            super.visitInsn(opcode);
        }

        /**
         * Push an int constant using the most compact instruction available.
         */
        private static void pushInt(MethodVisitor mv, int value) {
            if (value >= -1 && value <= 5) {
                mv.visitInsn(Opcodes.ICONST_0 + value);
            } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
                mv.visitIntInsn(Opcodes.BIPUSH, value);
            } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
                mv.visitIntInsn(Opcodes.SIPUSH, value);
            } else {
                mv.visitLdcInsn(value);
            }
        }
    }
}
