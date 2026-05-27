package topo.decompile;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Converts ASM bytecode instructions to TranspileModel JSON objects.
 *
 * JVM bytecode is stack-based, so this converter uses a simple operand stack
 * simulation to track intermediate values. Each bytecode instruction either
 * pushes a value onto the stack (expressions) or consumes values and produces
 * a statement.
 *
 * This implements the "Direct" decompile level: instructions are walked
 * linearly and converted to their nearest TranspileModel equivalents.
 * All produced nodes use Fidelity "recovered".
 */
public class BytecodeConverter {

    private final ClassNode classNode;
    private final MethodNode methodNode;
    private final JsonObject metadata;
    private final Map<String, String> reverseNameMap;

    // Operand stack simulation
    private final Deque<JsonObject> stack = new ArrayDeque<>();

    // Local variable name resolution
    private final Map<Integer, String> localNames = new HashMap<>();

    // Statement accumulator
    private final JsonArray statements = new JsonArray();

    public BytecodeConverter(ClassNode classNode, MethodNode methodNode,
                             JsonObject metadata, Map<String, String> reverseNameMap) {
        this.classNode = classNode;
        this.methodNode = methodNode;
        this.metadata = metadata;
        this.reverseNameMap = reverseNameMap;
    }

    /**
     * Convert the method's bytecode to a list of TranspileModel statement JSON objects.
     */
    public JsonArray convert() {
        initLocalNames();

        if (methodNode.instructions == null) return statements;

        for (var iter = methodNode.instructions.iterator(); iter.hasNext(); ) {
            var insn = iter.next();
            try {
                processInstruction(insn);
            } catch (Exception e) {
                // Graceful degradation: emit an unsupported expression for the failing instruction
                flushStackAsStatements();
                statements.add(makeExprStmt(makeUnsupported(
                        "error processing opcode " + insn.getOpcode() + ": " + e.getMessage())));
            }
        }

        // Flush any remaining stack values as expression statements
        flushStackAsStatements();

        return statements;
    }

    // -----------------------------------------------------------------------
    // Local variable name initialization
    // -----------------------------------------------------------------------

    private void initLocalNames() {
        boolean isStatic = (methodNode.access & Opcodes.ACC_STATIC) != 0;

        // Populate from LocalVariableTable
        if (methodNode.localVariables != null) {
            for (var lv : methodNode.localVariables) {
                localNames.put(lv.index, resolveName(lv.name));
            }
        }

        // Populate from MethodParameters
        if (methodNode.parameters != null) {
            int slot = isStatic ? 0 : 1;
            Type[] argTypes = Type.getArgumentTypes(methodNode.desc);
            for (int i = 0; i < methodNode.parameters.size() && i < argTypes.length; i++) {
                localNames.putIfAbsent(slot, resolveName(methodNode.parameters.get(i).name));
                slot += argTypes[i].getSize();
            }
        }

        // Set 'this' for instance methods
        if (!isStatic) {
            localNames.putIfAbsent(0, "this");
        }
    }

    private String getLocalName(int index) {
        return localNames.getOrDefault(index, "local" + index);
    }

    private String resolveName(String name) {
        String resolved = reverseNameMap.get(name);
        return resolved != null ? resolved : name;
    }

    // -----------------------------------------------------------------------
    // Instruction dispatch
    // -----------------------------------------------------------------------

    private void processInstruction(AbstractInsnNode insn) {
        switch (insn.getType()) {
            case AbstractInsnNode.INSN:
                processInsn((InsnNode) insn);
                break;
            case AbstractInsnNode.INT_INSN:
                processIntInsn((IntInsnNode) insn);
                break;
            case AbstractInsnNode.VAR_INSN:
                processVarInsn((VarInsnNode) insn);
                break;
            case AbstractInsnNode.TYPE_INSN:
                processTypeInsn((TypeInsnNode) insn);
                break;
            case AbstractInsnNode.FIELD_INSN:
                processFieldInsn((FieldInsnNode) insn);
                break;
            case AbstractInsnNode.METHOD_INSN:
                processMethodInsn((MethodInsnNode) insn);
                break;
            case AbstractInsnNode.JUMP_INSN:
                processJumpInsn((JumpInsnNode) insn);
                break;
            case AbstractInsnNode.LDC_INSN:
                processLdcInsn((LdcInsnNode) insn);
                break;
            case AbstractInsnNode.IINC_INSN:
                processIincInsn((IincInsnNode) insn);
                break;
            case AbstractInsnNode.MULTIANEWARRAY_INSN:
                processMultiANewArray((MultiANewArrayInsnNode) insn);
                break;
            // Labels, line numbers, and frames are metadata — skip them
            case AbstractInsnNode.LABEL:
            case AbstractInsnNode.LINE:
            case AbstractInsnNode.FRAME:
                break;
            case AbstractInsnNode.TABLESWITCH_INSN:
                flushStackAsStatements();
                processTableSwitch((TableSwitchInsnNode) insn);
                break;
            case AbstractInsnNode.LOOKUPSWITCH_INSN:
                flushStackAsStatements();
                processLookupSwitch((LookupSwitchInsnNode) insn);
                break;
            case AbstractInsnNode.INVOKE_DYNAMIC_INSN:
                processInvokeDynamic((InvokeDynamicInsnNode) insn);
                break;
            default:
                break;
        }
    }

    // -----------------------------------------------------------------------
    // InsnNode (zero-operand instructions)
    // -----------------------------------------------------------------------

    private void processInsn(InsnNode insn) {
        int op = insn.getOpcode();
        switch (op) {
            // Constants
            case Opcodes.ICONST_M1: push(makeLiteral("integer", "-1")); break;
            case Opcodes.ICONST_0:  push(makeLiteral("integer", "0"));  break;
            case Opcodes.ICONST_1:  push(makeLiteral("integer", "1"));  break;
            case Opcodes.ICONST_2:  push(makeLiteral("integer", "2"));  break;
            case Opcodes.ICONST_3:  push(makeLiteral("integer", "3"));  break;
            case Opcodes.ICONST_4:  push(makeLiteral("integer", "4"));  break;
            case Opcodes.ICONST_5:  push(makeLiteral("integer", "5"));  break;
            case Opcodes.LCONST_0:  push(makeLiteral("integer", "0"));  break;
            case Opcodes.LCONST_1:  push(makeLiteral("integer", "1"));  break;
            case Opcodes.FCONST_0:  push(makeLiteral("float", "0.0"));  break;
            case Opcodes.FCONST_1:  push(makeLiteral("float", "1.0"));  break;
            case Opcodes.FCONST_2:  push(makeLiteral("float", "2.0"));  break;
            case Opcodes.DCONST_0:  push(makeLiteral("float", "0.0"));  break;
            case Opcodes.DCONST_1:  push(makeLiteral("float", "1.0"));  break;
            case Opcodes.ACONST_NULL: push(makeLiteral("integer", "null")); break;

            // Arithmetic — int
            case Opcodes.IADD: processBinaryOp("add"); break;
            case Opcodes.ISUB: processBinaryOp("sub"); break;
            case Opcodes.IMUL: processBinaryOp("mul"); break;
            case Opcodes.IDIV: processBinaryOp("div"); break;
            case Opcodes.IREM: processBinaryOp("mod"); break;

            // Arithmetic — long
            case Opcodes.LADD: processBinaryOp("add"); break;
            case Opcodes.LSUB: processBinaryOp("sub"); break;
            case Opcodes.LMUL: processBinaryOp("mul"); break;
            case Opcodes.LDIV: processBinaryOp("div"); break;
            case Opcodes.LREM: processBinaryOp("mod"); break;

            // Arithmetic — float
            case Opcodes.FADD: processBinaryOp("add"); break;
            case Opcodes.FSUB: processBinaryOp("sub"); break;
            case Opcodes.FMUL: processBinaryOp("mul"); break;
            case Opcodes.FDIV: processBinaryOp("div"); break;
            case Opcodes.FREM: processBinaryOp("mod"); break;

            // Arithmetic — double
            case Opcodes.DADD: processBinaryOp("add"); break;
            case Opcodes.DSUB: processBinaryOp("sub"); break;
            case Opcodes.DMUL: processBinaryOp("mul"); break;
            case Opcodes.DDIV: processBinaryOp("div"); break;
            case Opcodes.DREM: processBinaryOp("mod"); break;

            // Unary negation
            case Opcodes.INEG:
            case Opcodes.LNEG:
            case Opcodes.FNEG:
            case Opcodes.DNEG:
                processUnaryOp("negate");
                break;

            // Returns
            case Opcodes.IRETURN:
            case Opcodes.LRETURN:
            case Opcodes.FRETURN:
            case Opcodes.DRETURN:
            case Opcodes.ARETURN:
                processReturn(true);
                break;
            case Opcodes.RETURN:
                processReturn(false);
                break;

            // Array load
            case Opcodes.IALOAD:
            case Opcodes.LALOAD:
            case Opcodes.FALOAD:
            case Opcodes.DALOAD:
            case Opcodes.AALOAD:
            case Opcodes.BALOAD:
            case Opcodes.CALOAD:
            case Opcodes.SALOAD:
                processArrayLoad();
                break;

            // Array store
            case Opcodes.IASTORE:
            case Opcodes.LASTORE:
            case Opcodes.FASTORE:
            case Opcodes.DASTORE:
            case Opcodes.AASTORE:
            case Opcodes.BASTORE:
            case Opcodes.CASTORE:
            case Opcodes.SASTORE:
                processArrayStore();
                break;

            // Array length
            case Opcodes.ARRAYLENGTH:
                processArrayLength();
                break;

            // Type conversions — push through (the value semantics are preserved)
            case Opcodes.I2L: case Opcodes.I2F: case Opcodes.I2D:
            case Opcodes.L2I: case Opcodes.L2F: case Opcodes.L2D:
            case Opcodes.F2I: case Opcodes.F2L: case Opcodes.F2D:
            case Opcodes.D2I: case Opcodes.D2L: case Opcodes.D2F:
            case Opcodes.I2B: case Opcodes.I2C: case Opcodes.I2S:
                // At Direct level, type casts are transparent — value stays on stack
                break;

            // Stack manipulation
            case Opcodes.POP:
                if (!stack.isEmpty()) {
                    var val = pop();
                    // If it was a call expression, emit as statement (side effect)
                    if ("call".equals(val.has("kind") ? val.get("kind").getAsString() : "")) {
                        statements.add(makeExprStmt(val));
                    }
                }
                break;
            case Opcodes.POP2:
                if (!stack.isEmpty()) pop();
                if (!stack.isEmpty()) pop();
                break;
            case Opcodes.DUP:
                if (!stack.isEmpty()) {
                    var top = stack.peek();
                    push(deepCopy(top));
                }
                break;
            case Opcodes.SWAP:
                if (stack.size() >= 2) {
                    var v1 = pop();
                    var v2 = pop();
                    push(v1);
                    push(v2);
                }
                break;

            // Comparison — long
            case Opcodes.LCMP:
                processBinaryOp("sub"); // Direct-level approximation
                break;

            // Comparison — float/double
            case Opcodes.FCMPL: case Opcodes.FCMPG:
            case Opcodes.DCMPL: case Opcodes.DCMPG:
                processBinaryOp("sub"); // Direct-level approximation
                break;

            // Throw
            case Opcodes.ATHROW:
                if (!stack.isEmpty()) {
                    var exception = pop();
                    statements.add(makeExprStmt(makeUnsupported("throw " + exprToString(exception))));
                }
                break;

            // Monitor (synchronization)
            case Opcodes.MONITORENTER:
            case Opcodes.MONITOREXIT:
                if (!stack.isEmpty()) pop();
                break;

            default:
                // Bitwise and shift operations — treat as binary ops
                if (op >= Opcodes.ISHL && op <= Opcodes.LUSHR) {
                    processBinaryOp("and"); // Approximation for shift/bitwise at Direct level
                } else {
                    push(makeUnsupported("opcode_" + op));
                }
                break;
        }
    }

    // -----------------------------------------------------------------------
    // IntInsnNode (single int operand)
    // -----------------------------------------------------------------------

    private void processIntInsn(IntInsnNode insn) {
        switch (insn.getOpcode()) {
            case Opcodes.BIPUSH:
            case Opcodes.SIPUSH:
                push(makeLiteral("integer", String.valueOf(insn.operand)));
                break;
            case Opcodes.NEWARRAY:
                // Pop count, push unsupported for now
                var count = popOrDefault();
                push(makeUnsupported("new " + newArrayTypeName(insn.operand)
                        + "[" + exprToString(count) + "]"));
                break;
            default:
                push(makeUnsupported("intinsn_" + insn.getOpcode()));
                break;
        }
    }

    // -----------------------------------------------------------------------
    // VarInsnNode (local variable load/store)
    // -----------------------------------------------------------------------

    private void processVarInsn(VarInsnNode insn) {
        switch (insn.getOpcode()) {
            // Loads -> push VarRefExpr
            case Opcodes.ILOAD:
            case Opcodes.LLOAD:
            case Opcodes.FLOAD:
            case Opcodes.DLOAD:
            case Opcodes.ALOAD:
                push(makeVarRef(getLocalName(insn.var)));
                break;

            // Stores -> pop value, emit AssignStmt
            case Opcodes.ISTORE:
            case Opcodes.LSTORE:
            case Opcodes.FSTORE:
            case Opcodes.DSTORE:
            case Opcodes.ASTORE:
                processStore(insn.var);
                break;

            case Opcodes.RET:
                // JSR/RET are legacy — treat as unsupported
                statements.add(makeExprStmt(makeUnsupported("ret")));
                break;

            default:
                break;
        }
    }

    private void processStore(int varIndex) {
        var value = popOrDefault();
        String name = getLocalName(varIndex);

        // If the name has not been declared yet in this method, emit VarDeclStmt
        if (!localNames.containsKey(varIndex)) {
            localNames.put(varIndex, name);
        }

        var stmt = new JsonObject();
        stmt.addProperty("kind", "assign");
        stmt.addProperty("fidelity", "recovered");
        stmt.add("target", makeVarRef(name));
        stmt.add("value", value);
        statements.add(stmt);
    }

    // -----------------------------------------------------------------------
    // TypeInsnNode (NEW, ANEWARRAY, CHECKCAST, INSTANCEOF)
    // -----------------------------------------------------------------------

    private void processTypeInsn(TypeInsnNode insn) {
        switch (insn.getOpcode()) {
            case Opcodes.NEW:
                // Push a placeholder — the actual ConstructExpr is created
                // when the matching INVOKESPECIAL <init> is encountered
                push(makeNewPlaceholder(insn.desc));
                break;
            case Opcodes.ANEWARRAY:
                var count = popOrDefault();
                push(makeUnsupported("new " + typeDescToReadable(insn.desc)
                        + "[" + exprToString(count) + "]"));
                break;
            case Opcodes.CHECKCAST:
                // Cast — value stays on stack at Direct level
                break;
            case Opcodes.INSTANCEOF:
                var obj = popOrDefault();
                var result = new JsonObject();
                result.addProperty("kind", "call");
                result.addProperty("fidelity", "recovered");
                result.addProperty("callee", "instanceof");
                var args = new JsonArray();
                args.add(obj);
                args.add(makeLiteral("string", typeDescToReadable(insn.desc)));
                result.add("args", args);
                push(result);
                break;
            default:
                break;
        }
    }

    // -----------------------------------------------------------------------
    // FieldInsnNode (GETFIELD, PUTFIELD, GETSTATIC, PUTSTATIC)
    // -----------------------------------------------------------------------

    private void processFieldInsn(FieldInsnNode insn) {
        switch (insn.getOpcode()) {
            case Opcodes.GETFIELD: {
                var obj = popOrDefault();
                var access = new JsonObject();
                access.addProperty("kind", "memberaccess");
                access.addProperty("fidelity", "recovered");
                access.add("object", obj);
                access.addProperty("member", resolveName(insn.name));
                push(access);
                break;
            }
            case Opcodes.PUTFIELD: {
                var value = popOrDefault();
                var obj = popOrDefault();
                var target = new JsonObject();
                target.addProperty("kind", "memberaccess");
                target.addProperty("fidelity", "recovered");
                target.add("object", obj);
                target.addProperty("member", resolveName(insn.name));

                var stmt = new JsonObject();
                stmt.addProperty("kind", "assign");
                stmt.addProperty("fidelity", "recovered");
                stmt.add("target", target);
                stmt.add("value", value);
                statements.add(stmt);
                break;
            }
            case Opcodes.GETSTATIC: {
                var access = new JsonObject();
                access.addProperty("kind", "memberaccess");
                access.addProperty("fidelity", "recovered");
                access.add("object", makeVarRef(typeDescToReadable(insn.owner)));
                access.addProperty("member", resolveName(insn.name));
                push(access);
                break;
            }
            case Opcodes.PUTSTATIC: {
                var value = popOrDefault();
                var target = new JsonObject();
                target.addProperty("kind", "memberaccess");
                target.addProperty("fidelity", "recovered");
                target.add("object", makeVarRef(typeDescToReadable(insn.owner)));
                target.addProperty("member", resolveName(insn.name));

                var stmt = new JsonObject();
                stmt.addProperty("kind", "assign");
                stmt.addProperty("fidelity", "recovered");
                stmt.add("target", target);
                stmt.add("value", value);
                statements.add(stmt);
                break;
            }
        }
    }

    // -----------------------------------------------------------------------
    // MethodInsnNode (INVOKEVIRTUAL, INVOKESTATIC, INVOKESPECIAL, INVOKEINTERFACE)
    // -----------------------------------------------------------------------

    private void processMethodInsn(MethodInsnNode insn) {
        Type methodType = Type.getMethodType(insn.desc);
        Type[] argTypes = methodType.getArgumentTypes();
        Type returnType = methodType.getReturnType();

        // Pop arguments (in reverse order)
        var methodArgs = new JsonObject[argTypes.length];
        for (int i = argTypes.length - 1; i >= 0; i--) {
            methodArgs[i] = popOrDefault();
        }

        // Handle constructor calls (INVOKESPECIAL <init>)
        if (insn.getOpcode() == Opcodes.INVOKESPECIAL && insn.name.equals("<init>")) {
            var receiver = popOrDefault();

            // Check if receiver is a NEW placeholder
            if (isNewPlaceholder(receiver)) {
                var constructExpr = new JsonObject();
                constructExpr.addProperty("kind", "construct");
                constructExpr.addProperty("fidelity", "recovered");
                constructExpr.add("type", JVMLifter.descriptorToTypeNode(
                        "L" + insn.owner + ";"));
                var argsArr = new JsonArray();
                for (var arg : methodArgs) argsArr.add(arg);
                constructExpr.add("args", argsArr);
                push(constructExpr);
                return;
            }

            // Super/this constructor call — emit as statement
            String callee = typeDescToReadable(insn.owner) + "." + "<init>";
            var callExpr = makeCallExpr(callee, methodArgs);
            statements.add(makeExprStmt(callExpr));
            return;
        }

        // Pop receiver for instance methods
        JsonObject receiver = null;
        if (insn.getOpcode() != Opcodes.INVOKESTATIC) {
            receiver = popOrDefault();
        }

        // Build callee name
        String callee;
        if (receiver != null) {
            // For instance methods, use receiver.method notation
            callee = typeDescToReadable(insn.owner) + "." + resolveName(insn.name);
        } else {
            callee = typeDescToReadable(insn.owner) + "." + resolveName(insn.name);
        }

        var callExpr = new JsonObject();
        callExpr.addProperty("kind", "call");
        callExpr.addProperty("fidelity", "recovered");
        callExpr.addProperty("callee", callee);

        var argsArr = new JsonArray();
        // For instance methods, include receiver as first arg
        if (receiver != null) {
            argsArr.add(receiver);
        }
        for (var arg : methodArgs) argsArr.add(arg);
        callExpr.add("args", argsArr);

        // If return type is void, emit as statement; otherwise push result
        if (returnType.getSort() == Type.VOID) {
            statements.add(makeExprStmt(callExpr));
        } else {
            push(callExpr);
        }
    }

    // -----------------------------------------------------------------------
    // JumpInsnNode (conditional/unconditional branches)
    // -----------------------------------------------------------------------

    private void processJumpInsn(JumpInsnNode insn) {
        switch (insn.getOpcode()) {
            case Opcodes.GOTO:
                // Control flow — skip at Direct level
                break;

            // Unary comparisons with zero
            case Opcodes.IFEQ:
                emitCondition("eq", popOrDefault(), makeLiteral("integer", "0"));
                break;
            case Opcodes.IFNE:
                emitCondition("noteq", popOrDefault(), makeLiteral("integer", "0"));
                break;
            case Opcodes.IFLT:
                emitCondition("less", popOrDefault(), makeLiteral("integer", "0"));
                break;
            case Opcodes.IFGE:
                emitCondition("greatereq", popOrDefault(), makeLiteral("integer", "0"));
                break;
            case Opcodes.IFGT:
                emitCondition("greater", popOrDefault(), makeLiteral("integer", "0"));
                break;
            case Opcodes.IFLE:
                emitCondition("lesseq", popOrDefault(), makeLiteral("integer", "0"));
                break;

            // Binary int comparisons
            case Opcodes.IF_ICMPEQ:
                emitBinaryCondition("eq");
                break;
            case Opcodes.IF_ICMPNE:
                emitBinaryCondition("noteq");
                break;
            case Opcodes.IF_ICMPLT:
                emitBinaryCondition("less");
                break;
            case Opcodes.IF_ICMPGE:
                emitBinaryCondition("greatereq");
                break;
            case Opcodes.IF_ICMPGT:
                emitBinaryCondition("greater");
                break;
            case Opcodes.IF_ICMPLE:
                emitBinaryCondition("lesseq");
                break;

            // Reference comparisons
            case Opcodes.IF_ACMPEQ:
                emitBinaryCondition("eq");
                break;
            case Opcodes.IF_ACMPNE:
                emitBinaryCondition("noteq");
                break;

            // Null checks
            case Opcodes.IFNULL:
                emitCondition("eq", popOrDefault(), makeLiteral("integer", "null"));
                break;
            case Opcodes.IFNONNULL:
                emitCondition("noteq", popOrDefault(), makeLiteral("integer", "null"));
                break;

            default:
                break;
        }
    }

    private void emitCondition(String op, JsonObject lhs, JsonObject rhs) {
        var condition = new JsonObject();
        condition.addProperty("kind", "binaryop");
        condition.addProperty("fidelity", "recovered");
        condition.addProperty("op", op);
        condition.add("lhs", lhs);
        condition.add("rhs", rhs);

        // Direct level contract: linear, instruction-by-instruction translation
        // with control flow preserved in goto form — no CFG reconstruction.
        // TranspileModel has no goto/branch Stmt kind, so a conditional branch
        // is preserved flatly as an ExprStmt of its test expression. Emitting a
        // structured IfStmt here (even with an empty thenBody) would falsely
        // signal CFG recovery on the path that explicitly performs none — that
        // belongs exclusively to the Structured level (StructuredLifter).
        statements.add(makeExprStmt(condition));
    }

    private void emitBinaryCondition(String op) {
        var rhs = popOrDefault();
        var lhs = popOrDefault();
        emitCondition(op, lhs, rhs);
    }

    // -----------------------------------------------------------------------
    // LdcInsnNode (load constant from constant pool)
    // -----------------------------------------------------------------------

    private void processLdcInsn(LdcInsnNode insn) {
        if (insn.cst instanceof Integer) {
            push(makeLiteral("integer", String.valueOf(insn.cst)));
        } else if (insn.cst instanceof Long) {
            push(makeLiteral("integer", String.valueOf(insn.cst)));
        } else if (insn.cst instanceof Float) {
            push(makeLiteral("float", String.valueOf(insn.cst)));
        } else if (insn.cst instanceof Double) {
            push(makeLiteral("float", String.valueOf(insn.cst)));
        } else if (insn.cst instanceof String) {
            push(makeLiteral("string", (String) insn.cst));
        } else if (insn.cst instanceof Type) {
            // Class literal (e.g., Foo.class)
            push(makeVarRef(((Type) insn.cst).getClassName() + ".class"));
        } else {
            push(makeUnsupported("ldc " + insn.cst.getClass().getSimpleName()));
        }
    }

    // -----------------------------------------------------------------------
    // IincInsnNode (increment local variable)
    // -----------------------------------------------------------------------

    private void processIincInsn(IincInsnNode insn) {
        String name = getLocalName(insn.var);
        // Emit: name = name + increment
        var target = makeVarRef(name);
        var value = new JsonObject();
        value.addProperty("kind", "binaryop");
        value.addProperty("fidelity", "recovered");
        value.addProperty("op", "add");
        value.add("lhs", makeVarRef(name));
        value.add("rhs", makeLiteral("integer", String.valueOf(insn.incr)));

        var stmt = new JsonObject();
        stmt.addProperty("kind", "assign");
        stmt.addProperty("fidelity", "recovered");
        stmt.add("target", target);
        stmt.add("value", value);
        statements.add(stmt);
    }

    // -----------------------------------------------------------------------
    // MultiANewArrayInsnNode
    // -----------------------------------------------------------------------

    private void processMultiANewArray(MultiANewArrayInsnNode insn) {
        // Pop dimension sizes
        for (int i = 0; i < insn.dims; i++) {
            popOrDefault();
        }
        push(makeUnsupported("multianewarray " + insn.desc + " dims=" + insn.dims));
    }

    // -----------------------------------------------------------------------
    // InvokeDynamicInsnNode (lambda, string concatenation, etc.)
    // -----------------------------------------------------------------------

    private void processInvokeDynamic(InvokeDynamicInsnNode insn) {
        Type methodType = Type.getMethodType(insn.desc);
        Type[] argTypes = methodType.getArgumentTypes();
        Type returnType = methodType.getReturnType();

        // Pop call-site arguments (these are the lambda's captured values for
        // a LambdaMetafactory bootstrap; for any other bootstrap they are just
        // operands to the synthetic call).
        var siteArgs = new JsonObject[argTypes.length];
        for (int i = argTypes.length - 1; i >= 0; i--) {
            siteArgs[i] = popOrDefault();
        }

        // LambdaMetafactory recovery: reconstruct a lambda model node instead
        // of an opaque invokedynamic call. Only the standard
        // java/lang/invoke/LambdaMetafactory.{metafactory,altMetafactory}
        // bootstrap is recognized; every other bootstrap (string concat
        // makeConcatWithConstants, record bootstraps, custom indy, …) stays on
        // the conservative synthetic-call path so it remains marked unsupported.
        JsonObject lambdaExpr = tryBuildLambda(insn, siteArgs);
        if (lambdaExpr != null) {
            // The SAM functional-interface object is the indy return value.
            push(lambdaExpr);
            return;
        }

        // Conservative fallback: synthetic call (kept unsupported by JVMLifter).
        String callee = "invokedynamic:" + insn.name;
        var callExpr = makeCallExpr(callee, siteArgs);

        if (returnType.getSort() == Type.VOID) {
            statements.add(makeExprStmt(callExpr));
        } else {
            push(callExpr);
        }
    }

    /**
     * Recognize a {@code java.lang.invoke.LambdaMetafactory} bootstrap and
     * rebuild it as a {@code lambda} model node. Returns {@code null} for any
     * bootstrap that is not the standard metafactory/altMetafactory, or when
     * the synthetic implementation method cannot be resolved conservatively.
     *
     * <p>LambdaMetafactory bootstrap arguments:
     * {@code bsmArgs[0]} = erased SAM {@link Type} (the functional-interface
     * method signature), {@code bsmArgs[1]} = {@link Handle} to the
     * implementation method (javac emits {@code lambda$foo$0} in the same
     * class for a lambda body, or a direct method handle for a method
     * reference), {@code bsmArgs[2]} = instantiated SAM {@link Type}.
     *
     * <p>The call-site arguments (already popped, passed in {@code siteArgs})
     * are the captured values. For an instance-method-backed lambda body the
     * implementation method's leading parameters correspond to those captures;
     * its trailing parameters correspond to the SAM parameters. Capture mode is
     * always by-value: the JVM copies captured values into the call site.
     */
    private JsonObject tryBuildLambda(InvokeDynamicInsnNode insn, JsonObject[] siteArgs) {
        Handle bsm = insn.bsm;
        if (bsm == null) return null;
        if (!"java/lang/invoke/LambdaMetafactory".equals(bsm.getOwner())) return null;
        String bsmName = bsm.getName();
        if (!"metafactory".equals(bsmName) && !"altMetafactory".equals(bsmName)) {
            return null;
        }

        Object[] bsmArgs = insn.bsmArgs;
        if (bsmArgs == null || bsmArgs.length < 2) return null;
        if (!(bsmArgs[0] instanceof Type samType)) return null;
        if (!(bsmArgs[1] instanceof Handle implHandle)) return null;
        if (samType.getSort() != Type.METHOD) return null;

        // Resolve the implementation method body. It must live in the class
        // currently being lifted (the common javac-synthesized
        // `lambda$owner$N` case). A handle into another class (e.g. a method
        // reference to a library method) cannot be inlined as a body here →
        // conservative bail-out so we never emit a wrong/empty lambda.
        if (!classNode.name.equals(implHandle.getOwner())) return null;
        MethodNode implMethod = findMethod(implHandle.getName(), implHandle.getDesc());
        if (implMethod == null) return null;

        Type[] implArgs = Type.getArgumentTypes(implMethod.desc);
        Type[] samArgs = samType.getArgumentTypes();
        int captureCount = siteArgs.length;

        // Sanity: impl method's parameter count must be capture-count plus the
        // SAM parameter count (leading = captures, trailing = SAM params).
        // If the shape is inconsistent (unexpected impl signature), bail out
        // rather than emit a misaligned lambda.
        if (implArgs.length != captureCount + samArgs.length) return null;

        List<String> implParamNames = resolveImplParamNames(implMethod);

        // Captures: leading impl parameters, named from the impl signature,
        // bound to the popped call-site argument expressions by position.
        var captures = new JsonArray();
        for (int i = 0; i < captureCount; i++) {
            String name = i < implParamNames.size() ? implParamNames.get(i) : "cap" + i;
            var cap = new JsonObject();
            cap.addProperty("name", name);
            cap.addProperty("mode", "by_value");
            captures.add(cap);
        }

        // Lambda parameters: the SAM method's parameters. Use the impl
        // method's trailing parameter names where available (they carry the
        // lambda's actual formal-parameter names), else synthesize.
        var params = new JsonArray();
        for (int i = 0; i < samArgs.length; i++) {
            int implIdx = captureCount + i;
            String name = implIdx < implParamNames.size()
                    ? implParamNames.get(implIdx) : "p" + i;
            var param = new JsonObject();
            param.add("type", JVMLifter.asmTypeToTypeNode(samArgs[i]));
            param.addProperty("name", name);
            params.add(param);
        }

        // Lambda body: lift the implementation method's instructions.
        var bodyConverter = new BytecodeConverter(classNode, implMethod,
                metadata, reverseNameMap);
        JsonArray body = bodyConverter.convert();

        var lambda = new JsonObject();
        lambda.addProperty("kind", "lambda");
        lambda.addProperty("fidelity", "recovered");
        lambda.add("captures", captures);
        lambda.add("params", params);
        lambda.add("returnType",
                JVMLifter.asmTypeToTypeNode(Type.getReturnType(implMethod.desc)));
        lambda.add("body", body);

        // Carry the bound capture argument expressions so a downstream
        // consumer / dedup pass can reconstruct the closure environment.
        var captureArgs = new JsonArray();
        for (var arg : siteArgs) captureArgs.add(arg);
        lambda.add("captureArgs", captureArgs);

        return lambda;
    }

    /** Find a method in the class being lifted by exact name + descriptor. */
    private MethodNode findMethod(String name, String desc) {
        if (classNode.methods == null) return null;
        for (var m : classNode.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) return m;
        }
        return null;
    }

    /** Resolve parameter names for an implementation method (best effort). */
    private List<String> resolveImplParamNames(MethodNode impl) {
        var names = new ArrayList<String>();
        boolean isStatic = (impl.access & Opcodes.ACC_STATIC) != 0;
        Type[] argTypes = Type.getArgumentTypes(impl.desc);

        if (impl.localVariables != null && !impl.localVariables.isEmpty()) {
            var sorted = new ArrayList<>(impl.localVariables);
            sorted.sort(Comparator.comparingInt(lv -> lv.index));
            int slot = isStatic ? 0 : 1;
            int pi = 0;
            for (var lv : sorted) {
                if (pi >= argTypes.length) break;
                if (lv.index == slot) {
                    names.add(resolveName(lv.name));
                    slot += argTypes[pi].getSize();
                    pi++;
                }
            }
        }
        if (names.isEmpty() && impl.parameters != null) {
            for (var p : impl.parameters) names.add(resolveName(p.name));
        }
        return names;
    }

    // -----------------------------------------------------------------------
    // Switch instructions (TABLESWITCH / LOOKUPSWITCH)
    // -----------------------------------------------------------------------

    private void processTableSwitch(TableSwitchInsnNode insn) {
        var subject = popOrDefault();
        var switchStmt = new JsonObject();
        switchStmt.addProperty("kind", "switch");
        switchStmt.addProperty("fidelity", "recovered");
        switchStmt.add("subject", subject);

        var cases = new JsonArray();
        for (int i = 0; i < insn.labels.size(); i++) {
            var caseObj = new JsonObject();
            caseObj.add("value", makeLiteral("integer", String.valueOf(insn.min + i)));
            // Direct level: case bodies are empty (no CFG reconstruction)
            caseObj.add("body", new JsonArray());
            cases.add(caseObj);
        }
        // Default case: no value field
        var defaultCase = new JsonObject();
        defaultCase.add("body", new JsonArray());
        cases.add(defaultCase);

        switchStmt.add("cases", cases);
        statements.add(switchStmt);
    }

    private void processLookupSwitch(LookupSwitchInsnNode insn) {
        var subject = popOrDefault();
        var switchStmt = new JsonObject();
        switchStmt.addProperty("kind", "switch");
        switchStmt.addProperty("fidelity", "recovered");
        switchStmt.add("subject", subject);

        var cases = new JsonArray();
        for (int i = 0; i < insn.keys.size(); i++) {
            var caseObj = new JsonObject();
            caseObj.add("value", makeLiteral("integer", String.valueOf(insn.keys.get(i))));
            // Direct level: case bodies are empty (no CFG reconstruction)
            caseObj.add("body", new JsonArray());
            cases.add(caseObj);
        }
        // Default case: no value field
        var defaultCase = new JsonObject();
        defaultCase.add("body", new JsonArray());
        cases.add(defaultCase);

        switchStmt.add("cases", cases);
        statements.add(switchStmt);
    }

    // -----------------------------------------------------------------------
    // Composite operations
    // -----------------------------------------------------------------------

    private void processBinaryOp(String op) {
        var rhs = popOrDefault();
        var lhs = popOrDefault();
        var expr = new JsonObject();
        expr.addProperty("kind", "binaryop");
        expr.addProperty("fidelity", "recovered");
        expr.addProperty("op", op);
        expr.add("lhs", lhs);
        expr.add("rhs", rhs);
        push(expr);
    }

    private void processUnaryOp(String op) {
        var operand = popOrDefault();
        var expr = new JsonObject();
        expr.addProperty("kind", "unaryop");
        expr.addProperty("fidelity", "recovered");
        expr.addProperty("op", op);
        expr.add("operand", operand);
        push(expr);
    }

    private void processReturn(boolean hasValue) {
        var stmt = new JsonObject();
        stmt.addProperty("kind", "return");
        stmt.addProperty("fidelity", "recovered");
        if (hasValue) {
            stmt.add("value", popOrDefault());
        }
        statements.add(stmt);
    }

    private void processArrayLoad() {
        var index = popOrDefault();
        var arrayRef = popOrDefault();
        var expr = new JsonObject();
        expr.addProperty("kind", "index");
        expr.addProperty("fidelity", "recovered");
        expr.add("object", arrayRef);
        expr.add("index", index);
        push(expr);
    }

    private void processArrayStore() {
        var value = popOrDefault();
        var index = popOrDefault();
        var arrayRef = popOrDefault();

        var target = new JsonObject();
        target.addProperty("kind", "index");
        target.addProperty("fidelity", "recovered");
        target.add("object", arrayRef);
        target.add("index", index);

        var stmt = new JsonObject();
        stmt.addProperty("kind", "assign");
        stmt.addProperty("fidelity", "recovered");
        stmt.add("target", target);
        stmt.add("value", value);
        statements.add(stmt);
    }

    private void processArrayLength() {
        var arrayRef = popOrDefault();
        var expr = new JsonObject();
        expr.addProperty("kind", "memberaccess");
        expr.addProperty("fidelity", "recovered");
        expr.add("object", arrayRef);
        expr.addProperty("member", "length");
        push(expr);
    }

    // -----------------------------------------------------------------------
    // Stack management
    // -----------------------------------------------------------------------

    private void push(JsonObject expr) {
        stack.push(expr);
    }

    private JsonObject pop() {
        return stack.pop();
    }

    private JsonObject popOrDefault() {
        if (stack.isEmpty()) {
            return makeUnsupported("stack_underflow");
        }
        return stack.pop();
    }

    private void flushStackAsStatements() {
        while (!stack.isEmpty()) {
            var expr = stack.pollLast(); // bottom-first to preserve order
            if (expr != null) {
                statements.add(makeExprStmt(expr));
            }
        }
    }

    // -----------------------------------------------------------------------
    // JSON node factories
    // -----------------------------------------------------------------------

    private static JsonObject makeLiteral(String litKind, String value) {
        var expr = new JsonObject();
        expr.addProperty("kind", "literal");
        expr.addProperty("fidelity", "recovered");
        expr.addProperty("litKind", litKind);
        expr.addProperty("value", value);
        return expr;
    }

    private static JsonObject makeVarRef(String name) {
        var expr = new JsonObject();
        expr.addProperty("kind", "varref");
        expr.addProperty("fidelity", "recovered");
        expr.addProperty("name", name);
        return expr;
    }

    private static JsonObject makeUnsupported(String description) {
        var expr = new JsonObject();
        expr.addProperty("kind", "unsupported");
        expr.addProperty("fidelity", "recovered");
        expr.addProperty("description", description);
        return expr;
    }

    private static JsonObject makeExprStmt(JsonObject expr) {
        var stmt = new JsonObject();
        stmt.addProperty("kind", "exprstmt");
        stmt.addProperty("fidelity", "recovered");
        stmt.add("expr", expr);
        return stmt;
    }

    private static JsonObject makeCallExpr(String callee, JsonObject[] args) {
        var expr = new JsonObject();
        expr.addProperty("kind", "call");
        expr.addProperty("fidelity", "recovered");
        expr.addProperty("callee", callee);
        var argsArr = new JsonArray();
        for (var arg : args) argsArr.add(arg);
        expr.add("args", argsArr);
        return expr;
    }

    private static JsonObject makeNewPlaceholder(String typeDesc) {
        var expr = new JsonObject();
        expr.addProperty("kind", "unsupported");
        expr.addProperty("fidelity", "recovered");
        expr.addProperty("description", "__new_placeholder__");
        expr.addProperty("__typeDesc", typeDesc);
        return expr;
    }

    private static boolean isNewPlaceholder(JsonObject expr) {
        return expr.has("description")
                && "__new_placeholder__".equals(expr.get("description").getAsString());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Convert JVM internal name (com/example/Foo) to readable form (com.example.Foo). */
    private static String typeDescToReadable(String internalName) {
        return internalName.replace('/', '.');
    }

    /** Get a human-readable type name for NEWARRAY type codes. */
    private static String newArrayTypeName(int typeCode) {
        return switch (typeCode) {
            case 4 -> "boolean";
            case 5 -> "char";
            case 6 -> "float";
            case 7 -> "double";
            case 8 -> "byte";
            case 9 -> "short";
            case 10 -> "int";
            case 11 -> "long";
            default -> "unknown";
        };
    }

    /** Produce a best-effort string representation of an expression for diagnostic messages. */
    private static String exprToString(JsonObject expr) {
        if (expr == null) return "<null>";
        String kind = expr.has("kind") ? expr.get("kind").getAsString() : "?";
        return switch (kind) {
            case "literal" -> expr.has("value") ? expr.get("value").getAsString() : "<literal>";
            case "varref" -> expr.has("name") ? expr.get("name").getAsString() : "<var>";
            case "call" -> expr.has("callee") ? expr.get("callee").getAsString() + "(...)" : "<call>";
            default -> "<" + kind + ">";
        };
    }

    /** Deep copy a JsonObject (since Gson JsonObject is mutable). */
    private static JsonObject deepCopy(JsonObject obj) {
        return obj.deepCopy();
    }
}
