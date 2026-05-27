package topo.decompile;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * L2 structured control flow recovery for JVM bytecode.
 *
 * Builds a CFG from the instruction list, detects if/else and loop patterns,
 * and produces TranspileModel JSON with populated IfStmt/WhileStmt/ForStmt bodies.
 *
 * Approach (mirrors LLVMLifter L2):
 *   1. Build basic blocks from label boundaries and branch instructions
 *   2. Detect loops via back edges (jump target precedes jump source)
 *   3. Detect counted loops (ILOAD + comparison + IINC pattern) -> ForStmt
 *   4. Detect if/else via conditional branches that converge -> IfStmt
 *   5. Convert switch instructions to SwitchStmt
 */
public class StructuredLifter {

    private final ClassNode classNode;
    private final MethodNode methodNode;
    private final JsonObject metadata;
    private final Map<String, String> reverseNameMap;

    // CFG representation
    private final List<BasicBlock> blocks = new ArrayList<>();
    private final Map<LabelNode, BasicBlock> labelToBlock = new IdentityHashMap<>();
    private final Map<AbstractInsnNode, BasicBlock> insnToBlock = new IdentityHashMap<>();

    // Local variable name resolution (mirrors BytecodeConverter)
    private final Map<Integer, String> localNames = new HashMap<>();

    public StructuredLifter(ClassNode classNode, MethodNode methodNode,
                            JsonObject metadata, Map<String, String> reverseNameMap) {
        this.classNode = classNode;
        this.methodNode = methodNode;
        this.metadata = metadata;
        this.reverseNameMap = reverseNameMap;
    }

    // Recorded when a construct could not be confidently recovered.
    // JVMLifter merges this into the function's "unsupported" set.
    private final LinkedHashSet<String> unsupportedConstructs = new LinkedHashSet<>();

    /** Constructs that the lifter could not confidently recover (e.g. nested try). */
    public Set<String> getUnsupportedConstructs() {
        return unsupportedConstructs;
    }

    /**
     * Lift the method body with structured control flow recovery.
     */
    public JsonArray lift() {
        if (methodNode.instructions == null || methodNode.instructions.size() == 0) {
            return new JsonArray();
        }

        initLocalNames();

        // Exception structure (try/catch/finally) is carried out-of-band in
        // MethodNode.tryCatchBlocks, not in the instruction stream, so the
        // CFG region walker never sees it. Recover it first; if a single
        // top-level try region is confidently identified we emit a TryCatch
        // statement and structure each sub-region (try / catch / finally)
        // through the same instruction-conversion machinery. When the shape
        // cannot be confidently mapped we fall back to the plain CFG walk and
        // record the construct as unsupported rather than emit wrong structure.
        JsonArray tryCatchResult = tryLiftExceptionHandlers();
        if (tryCatchResult != null) {
            return tryCatchResult;
        }

        buildCFG();

        if (blocks.isEmpty()) {
            return new JsonArray();
        }

        var result = new JsonArray();
        var visited = new HashSet<BasicBlock>();
        liftRegion(blocks.get(0), null, result, visited);
        return result;
    }

    // -----------------------------------------------------------------------
    // Exception-range recovery (try / catch / finally)
    // -----------------------------------------------------------------------

    /**
     * Attempt to reconstruct try/catch/finally from MethodNode.tryCatchBlocks.
     *
     * Returns the lifted statement array when a single, confidently-mappable
     * top-level try region is found; returns {@code null} to signal the caller
     * to fall back to the ordinary CFG-based structuring (no exception
     * structure, or a shape we refuse to guess at).
     *
     * Supported shapes:
     *   - try / single catch
     *   - try / multiple catch (and multi-catch, which javac lowers to several
     *     TryCatchBlockNode entries sharing one handler)
     *   - try / catch / finally
     *   - try / finally (no catch)
     *
     * Conservative fallbacks (return null, record unsupported):
     *   - no exception handlers          -> null, no record (normal path)
     *   - more than one distinct protected range (nested / sequential try)
     *   - handler ranges that interleave with the try range in a way we
     *     cannot slice into contiguous instruction sub-lists
     */
    private JsonArray tryLiftExceptionHandlers() {
        List<TryCatchBlockNode> tcbs = methodNode.tryCatchBlocks;
        if (tcbs == null || tcbs.isEmpty()) {
            return null; // normal path, no exception structure
        }

        // Index every instruction so we can compare positions and slice ranges.
        var insnList = methodNode.instructions;
        Map<AbstractInsnNode, Integer> pos = new IdentityHashMap<>();
        List<AbstractInsnNode> linear = new ArrayList<>();
        for (var it = insnList.iterator(); it.hasNext(); ) {
            var insn = it.next();
            pos.put(insn, linear.size());
            linear.add(insn);
        }

        // Group handlers by their protected [start,end) range.
        // javac emits one TryCatchBlockNode per catch type; a multi-catch and
        // a try-with-multiple-catch both share the same protected range.
        // Distinct ranges => nested or sequential try: refuse (conservative).
        Integer tryStart = null, tryEnd = null;
        var handlers = new ArrayList<TryCatchBlockNode>();
        for (var tcb : tcbs) {
            Integer s = pos.get(skipToReal(tcb.start));
            Integer e = pos.get(skipToRealOrEnd(tcb.end, pos, linear));
            if (s == null || e == null) {
                unsupportedConstructs.add("try-catch (unresolved range)");
                return null;
            }
            if (tryStart == null) {
                tryStart = s;
                tryEnd = e;
            } else if (!tryStart.equals(s) || !tryEnd.equals(e)) {
                // Different protected range in the same method.
                unsupportedConstructs.add("nested-or-sequential try-catch");
                return null;
            }
            handlers.add(tcb);
        }

        // Handler ordering: catch handlers carry a type; the finally / "any"
        // handler has type == null. javac duplicates finally code inline and
        // also emits a synthetic catch-all (type == null) covering the try
        // (and often the catch bodies). We treat the type==null handler as the
        // finally block.
        TryCatchBlockNode finallyHandler = null;
        var catchHandlers = new ArrayList<TryCatchBlockNode>();
        for (var h : handlers) {
            if (h.type == null) {
                // Prefer the first catch-all as the finally source.
                if (finallyHandler == null) finallyHandler = h;
            } else {
                catchHandlers.add(h);
            }
        }

        // Determine the instruction window each region occupies.
        // The try body is [tryStart, tryEnd). Each handler body starts at its
        // handler label; its end is the next region boundary (the next
        // handler, the finally handler, or end of method). We sort all handler
        // start positions to derive contiguous, non-overlapping windows.
        int tryStartPos = tryStart;
        int tryEndPos = tryEnd;

        // Collect (handlerStartPos, node) for catch + finally, in code order.
        var regionStarts = new ArrayList<int[]>(); // [startPos, idx into handlers list marker]
        var orderedHandlers = new ArrayList<TryCatchBlockNode>();
        for (var h : catchHandlers) {
            Integer hp = pos.get(skipToReal(h.handler));
            if (hp == null) {
                unsupportedConstructs.add("try-catch (unresolved handler)");
                return null;
            }
            regionStarts.add(new int[]{hp, orderedHandlers.size()});
            orderedHandlers.add(h);
        }
        Integer finallyStartPos = null;
        if (finallyHandler != null) {
            Integer hp = pos.get(skipToReal(finallyHandler.handler));
            if (hp == null) {
                unsupportedConstructs.add("try-finally (unresolved handler)");
                return null;
            }
            finallyStartPos = hp;
            regionStarts.add(new int[]{hp, -1});
        }
        regionStarts.sort(Comparator.comparingInt(a -> a[0]));

        // The try body must not overlap any handler region.
        for (var rs : regionStarts) {
            if (rs[0] >= tryStartPos && rs[0] < tryEndPos) {
                unsupportedConstructs.add("try-catch (handler inside try range)");
                return null;
            }
        }

        // Compute the end of each handler window: the next region start, else
        // the method end. Handlers are expected to follow the try body.
        var handlerWindows = new LinkedHashMap<TryCatchBlockNode, int[]>(); // node -> [start,end)
        for (int i = 0; i < regionStarts.size(); i++) {
            int start = regionStarts.get(i)[0];
            int marker = regionStarts.get(i)[1];
            int end = (i + 1 < regionStarts.size())
                    ? regionStarts.get(i + 1)[0]
                    : linear.size();
            TryCatchBlockNode node = (marker == -1) ? finallyHandler
                    : orderedHandlers.get(marker);
            handlerWindows.put(node, new int[]{start, end});
        }

        // Build the TryCatch JSON.
        var tryCatch = new JsonObject();
        tryCatch.addProperty("kind", "trycatch");
        tryCatch.addProperty("fidelity", "recovered");

        // Try body: instructions [tryStartPos, tryEndPos), minus the trailing
        // GOTO that skips over the handlers (normal-completion jump).
        tryCatch.add("tryBody",
                liftInstructionWindow(linear, tryStartPos, tryEndPos));

        // Catch clauses.
        var catchesArr = new JsonArray();
        int catchVarSeq = 0;
        for (var h : orderedHandlers) {
            int[] win = handlerWindows.get(h);
            var clause = new JsonObject();

            // exceptionType = qualified name of the caught type.
            String qualified = h.type.replace('/', '.');
            String resolved = reverseNameMap.get(qualified);
            if (resolved != null) qualified = resolved;
            var typeNode = new JsonObject();
            var nameParts = new JsonArray();
            for (var part : qualified.split("\\.")) nameParts.add(part);
            typeNode.add("nameParts", nameParts);
            clause.add("exceptionType", typeNode);

            // Synthesized variable name (e, e2, e3, ...). javac stores the
            // pending exception with ASTORE at the handler entry; that store
            // is consumed here, so it is dropped from the emitted body.
            String varName = catchVarSeq == 0 ? "e" : "e" + (catchVarSeq + 1);
            catchVarSeq++;
            clause.addProperty("varName", varName);

            int bodyStart = win[0];
            // Skip a leading ASTORE (stores the caught exception into a local).
            if (bodyStart < linear.size()
                    && linear.get(bodyStart).getOpcode() == Opcodes.ASTORE) {
                bodyStart++;
            }
            clause.add("body",
                    liftInstructionWindow(linear, bodyStart, win[1]));
            catchesArr.add(clause);
        }
        tryCatch.add("catchClauses", catchesArr);

        // Finally body (type == null handler), if present.
        if (finallyHandler != null) {
            int[] win = handlerWindows.get(finallyHandler);
            int bodyStart = win[0];
            // The catch-all handler also begins with ASTORE of the in-flight
            // exception and ends with an ATHROW re-raise; strip both so the
            // finally body holds only the user's finally statements.
            if (bodyStart < linear.size()
                    && linear.get(bodyStart).getOpcode() == Opcodes.ASTORE) {
                bodyStart++;
            }
            int bodyEnd = win[1];
            // Trim a trailing ALOAD/ATHROW re-raise pair if present.
            int scan = bodyEnd - 1;
            while (scan > bodyStart
                    && (linear.get(scan).getOpcode() < 0)) {
                scan--;
            }
            if (scan > bodyStart
                    && linear.get(scan).getOpcode() == Opcodes.ATHROW) {
                bodyEnd = scan; // drop ATHROW
                int beforeThrow = bodyEnd - 1;
                while (beforeThrow > bodyStart
                        && linear.get(beforeThrow).getOpcode() < 0) {
                    beforeThrow--;
                }
                if (beforeThrow >= bodyStart
                        && linear.get(beforeThrow).getOpcode() == Opcodes.ALOAD) {
                    bodyEnd = beforeThrow; // drop the ALOAD feeding ATHROW
                }
            }
            tryCatch.add("finallyBody",
                    liftInstructionWindow(linear, bodyStart, bodyEnd));
        }

        var result = new JsonArray();
        result.add(tryCatch);
        return result;
    }

    /**
     * Convert the instruction window [from, to) to TranspileModel statements.
     * Reuses {@link #convertInstructions} which strips pure control-flow GOTOs
     * and runs the stack-simulating BytecodeConverter.
     */
    private JsonArray liftInstructionWindow(List<AbstractInsnNode> linear,
                                            int from, int to) {
        if (from < 0) from = 0;
        if (to > linear.size()) to = linear.size();
        if (from >= to) return new JsonArray();
        // Keep only real instructions; labels / frames / line numbers are
        // metadata and ASM cannot clone a bare LabelNode/FrameNode outside its
        // owning list (convertInstructions clones each node). This mirrors the
        // BasicBlock.instructions invariant used elsewhere in this class.
        var slice = new ArrayList<AbstractInsnNode>();
        for (int i = from; i < to; i++) {
            var insn = linear.get(i);
            if (insn instanceof LabelNode || insn instanceof LineNumberNode
                    || insn instanceof FrameNode) {
                continue;
            }
            slice.add(insn);
        }
        // convertInstructions additionally strips pure control-flow GOTOs
        // (normal-completion jumps past handlers); the structural emitter owns
        // control flow.
        return convertInstructions(slice);
    }

    /** Skip labels/lines/frames forward from {@code label} to a real insn. */
    private static AbstractInsnNode skipToReal(LabelNode label) {
        return findFirstReal(label);
    }

    /**
     * The protected-range end label is exclusive and may sit at the very end
     * of the list (no following real instruction). Resolve it to a position;
     * if no real instruction follows, treat it as one-past-the-last.
     */
    private AbstractInsnNode skipToRealOrEnd(LabelNode endLabel,
                                             Map<AbstractInsnNode, Integer> pos,
                                             List<AbstractInsnNode> linear) {
        AbstractInsnNode real = findFirstReal(endLabel);
        if (real != null) return real;
        // No real instruction after the end label: clamp to last element so
        // the [start,end) slice still terminates.
        return linear.isEmpty() ? null : linear.get(linear.size() - 1);
    }

    // -----------------------------------------------------------------------
    // Basic block representation
    // -----------------------------------------------------------------------

    static class BasicBlock {
        int index;
        final List<AbstractInsnNode> instructions = new ArrayList<>();
        final List<BasicBlock> successors = new ArrayList<>();
        final List<BasicBlock> predecessors = new ArrayList<>();
        LabelNode label; // entry label, if any

        @Override
        public String toString() {
            return "BB" + index;
        }
    }

    // -----------------------------------------------------------------------
    // CFG construction
    // -----------------------------------------------------------------------

    private void buildCFG() {
        var insnList = methodNode.instructions;

        // Step 1: Identify block entry points (labels and instruction after branches)
        var blockEntries = new LinkedHashSet<AbstractInsnNode>();
        // First real instruction is always an entry
        AbstractInsnNode firstReal = findFirstReal(insnList.getFirst());
        if (firstReal != null) {
            blockEntries.add(firstReal);
        }

        for (var iter = insnList.iterator(); iter.hasNext(); ) {
            var insn = iter.next();

            if (insn instanceof LabelNode) {
                AbstractInsnNode next = findFirstReal(insn);
                if (next != null) {
                    blockEntries.add(next);
                }
            }

            if (isBlockTerminator(insn)) {
                // Instruction after terminator starts a new block
                AbstractInsnNode next = findFirstReal(insn.getNext());
                if (next != null) {
                    blockEntries.add(next);
                }
                // Jump targets are block entries
                if (insn instanceof JumpInsnNode jump) {
                    AbstractInsnNode target = findFirstReal(jump.label);
                    if (target != null) {
                        blockEntries.add(target);
                    }
                } else if (insn instanceof TableSwitchInsnNode sw) {
                    for (var lab : sw.labels) {
                        AbstractInsnNode target = findFirstReal(lab);
                        if (target != null) blockEntries.add(target);
                    }
                    AbstractInsnNode def = findFirstReal(sw.dflt);
                    if (def != null) blockEntries.add(def);
                } else if (insn instanceof LookupSwitchInsnNode sw) {
                    for (var lab : sw.labels) {
                        AbstractInsnNode target = findFirstReal(lab);
                        if (target != null) blockEntries.add(target);
                    }
                    AbstractInsnNode def = findFirstReal(sw.dflt);
                    if (def != null) blockEntries.add(def);
                }
            }
        }

        // Step 2: Create basic blocks
        var entryList = new ArrayList<>(blockEntries);
        var entrySet = new HashSet<>(entryList);

        BasicBlock current = null;
        for (var iter = insnList.iterator(); iter.hasNext(); ) {
            var insn = iter.next();

            // Skip non-real instructions for block membership, but track labels
            if (insn instanceof LabelNode labelNode) {
                // Associate label with the block that follows it
                AbstractInsnNode real = findFirstReal(insn);
                if (real != null && entrySet.contains(real)) {
                    // This label marks a block boundary. If we haven't created the block yet,
                    // it'll be created when we reach the real instruction.
                    // For now, record the label -> upcoming block mapping.
                    // We defer because the block may not exist yet.
                }
                continue;
            }
            if (insn instanceof LineNumberNode || insn instanceof FrameNode) {
                continue;
            }

            if (entrySet.contains(insn)) {
                // Start a new block
                current = new BasicBlock();
                current.index = blocks.size();
                blocks.add(current);
                insnToBlock.put(insn, current);
            }

            if (current != null) {
                current.instructions.add(insn);
                insnToBlock.put(insn, current);
            }
        }

        // Step 3: Map labels to blocks
        for (var iter = insnList.iterator(); iter.hasNext(); ) {
            var insn = iter.next();
            if (insn instanceof LabelNode labelNode) {
                AbstractInsnNode real = findFirstReal(insn);
                if (real != null) {
                    BasicBlock bb = insnToBlock.get(real);
                    if (bb != null) {
                        labelToBlock.put(labelNode, bb);
                        bb.label = labelNode;
                    }
                }
            }
        }

        // Step 4: Build edges
        for (var bb : blocks) {
            if (bb.instructions.isEmpty()) continue;
            AbstractInsnNode last = bb.instructions.get(bb.instructions.size() - 1);

            if (last instanceof JumpInsnNode jump) {
                BasicBlock target = labelToBlock.get(jump.label);
                if (target != null) {
                    addEdge(bb, target);
                }
                if (jump.getOpcode() != Opcodes.GOTO) {
                    // Conditional branch: fall-through edge
                    BasicBlock fallThrough = getFallThrough(bb);
                    if (fallThrough != null) {
                        addEdge(bb, fallThrough);
                    }
                }
            } else if (last instanceof TableSwitchInsnNode sw) {
                BasicBlock def = labelToBlock.get(sw.dflt);
                if (def != null) addEdge(bb, def);
                for (var lab : sw.labels) {
                    BasicBlock target = labelToBlock.get(lab);
                    if (target != null) addEdge(bb, target);
                }
            } else if (last instanceof LookupSwitchInsnNode sw) {
                BasicBlock def = labelToBlock.get(sw.dflt);
                if (def != null) addEdge(bb, def);
                for (var lab : sw.labels) {
                    BasicBlock target = labelToBlock.get(lab);
                    if (target != null) addEdge(bb, target);
                }
            } else if (!isReturnOrThrow(last)) {
                // Fall through to next block
                BasicBlock fallThrough = getFallThrough(bb);
                if (fallThrough != null) {
                    addEdge(bb, fallThrough);
                }
            }
        }
    }

    private BasicBlock getFallThrough(BasicBlock bb) {
        int nextIdx = bb.index + 1;
        if (nextIdx < blocks.size()) {
            return blocks.get(nextIdx);
        }
        return null;
    }

    private void addEdge(BasicBlock from, BasicBlock to) {
        if (!from.successors.contains(to)) {
            from.successors.add(to);
        }
        if (!to.predecessors.contains(from)) {
            to.predecessors.add(from);
        }
    }

    private static boolean isBlockTerminator(AbstractInsnNode insn) {
        if (insn instanceof JumpInsnNode) return true;
        if (insn instanceof TableSwitchInsnNode) return true;
        if (insn instanceof LookupSwitchInsnNode) return true;
        return isReturnOrThrow(insn);
    }

    private static boolean isReturnOrThrow(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        return op == Opcodes.IRETURN || op == Opcodes.LRETURN || op == Opcodes.FRETURN
                || op == Opcodes.DRETURN || op == Opcodes.ARETURN || op == Opcodes.RETURN
                || op == Opcodes.ATHROW;
    }

    /**
     * Skip past labels, line numbers, and frames to find the first "real" instruction.
     */
    private static AbstractInsnNode findFirstReal(AbstractInsnNode insn) {
        while (insn != null) {
            if (!(insn instanceof LabelNode) && !(insn instanceof LineNumberNode)
                    && !(insn instanceof FrameNode)) {
                return insn;
            }
            insn = insn.getNext();
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Structured region lifting (recursive, mirrors LLVMLifter::liftBlockRegion)
    // -----------------------------------------------------------------------

    private void liftRegion(BasicBlock entry, BasicBlock exit,
                            JsonArray stmts, Set<BasicBlock> visited) {
        if (entry == null || entry == exit || visited.contains(entry)) {
            return;
        }
        visited.add(entry);

        // Check for loop (back edge: a successor whose index <= entry's index)
        BackEdgeInfo backEdge = detectBackEdge(entry);
        if (backEdge != null) {
            liftLoop(entry, exit, backEdge, stmts, visited);
            return;
        }

        // Check for switch
        if (!entry.instructions.isEmpty()) {
            AbstractInsnNode last = entry.instructions.get(entry.instructions.size() - 1);
            if (last instanceof TableSwitchInsnNode || last instanceof LookupSwitchInsnNode) {
                liftSwitch(entry, exit, stmts, visited);
                return;
            }
        }

        // Check for if/else (conditional branch with convergence)
        if (isConditionalBranch(entry)) {
            liftIfElse(entry, exit, stmts, visited);
            return;
        }

        // Straight-line block: lift instructions and follow successor
        liftBlockInstructions(entry, stmts);

        for (var succ : entry.successors) {
            liftRegion(succ, exit, stmts, visited);
        }
    }

    // -----------------------------------------------------------------------
    // Loop detection and lifting
    // -----------------------------------------------------------------------

    static class BackEdgeInfo {
        BasicBlock latchBlock; // the block that jumps back
        // The header is the entry block itself
    }

    private BackEdgeInfo detectBackEdge(BasicBlock header) {
        // A loop exists when some successor block (or a block reachable from here)
        // has an edge back to this header. In the simple case, a block within the
        // region has header as a successor.
        // We check: does any block reachable from header have header as a successor?
        var reachable = new HashSet<BasicBlock>();
        var worklist = new ArrayDeque<BasicBlock>();
        for (var succ : header.successors) {
            if (succ != header) {
                worklist.add(succ);
            } else {
                // Self-loop
                var info = new BackEdgeInfo();
                info.latchBlock = header;
                return info;
            }
        }

        while (!worklist.isEmpty()) {
            var bb = worklist.poll();
            if (!reachable.add(bb)) continue;
            for (var succ : bb.successors) {
                if (succ == header) {
                    var info = new BackEdgeInfo();
                    info.latchBlock = bb;
                    return info;
                }
                if (!reachable.contains(succ)) {
                    worklist.add(succ);
                }
            }
        }
        return null;
    }

    private void liftLoop(BasicBlock header, BasicBlock regionExit,
                          BackEdgeInfo backEdge, JsonArray stmts,
                          Set<BasicBlock> visited) {
        // Collect all blocks in the loop body (blocks reachable from header
        // that can reach the latch without leaving the loop)
        var loopBlocks = collectLoopBlocks(header, backEdge.latchBlock);

        // Find loop exit block(s): successors of loop blocks that are not in the loop
        BasicBlock loopExit = null;
        for (var bb : loopBlocks) {
            for (var succ : bb.successors) {
                if (!loopBlocks.contains(succ)) {
                    loopExit = succ;
                    break;
                }
            }
            if (loopExit != null) break;
        }

        // Try to detect a counted loop pattern (for-loop)
        ForLoopInfo forInfo = detectCountedLoop(header, loopBlocks);
        if (forInfo != null) {
            var forStmt = new JsonObject();
            forStmt.addProperty("kind", "for");
            forStmt.addProperty("fidelity", "recovered");
            forStmt.add("init", forInfo.initStmt);
            forStmt.add("condition", forInfo.condition);
            forStmt.add("increment", forInfo.increment);

            var body = new JsonArray();
            var bodyVisited = new HashSet<>(visited);
            bodyVisited.add(header); // skip the header itself (contains the cmp/branch)
            for (var bb : loopBlocks) {
                if (bb == header) continue;
                if (bodyVisited.contains(bb)) continue;
                bodyVisited.add(bb);
                liftBlockInstructions(bb, body);
                // Check for break/continue: GOTO targeting header or loopExit
                AbstractInsnNode lastInsn = getLastRealInsn(bb);
                if (lastInsn != null && lastInsn.getOpcode() == Opcodes.GOTO) {
                    var jumpInsn = (JumpInsnNode) lastInsn;
                    BasicBlock jumpTarget = labelToBlock.get(jumpInsn.label);
                    if (jumpTarget == loopExit) {
                        var breakStmt = new JsonObject();
                        breakStmt.addProperty("kind", "break");
                        breakStmt.addProperty("fidelity", "recovered");
                        body.add(breakStmt);
                    } else if (jumpTarget == header) {
                        var continueStmt = new JsonObject();
                        continueStmt.addProperty("kind", "continue");
                        continueStmt.addProperty("fidelity", "recovered");
                        body.add(continueStmt);
                    }
                }
            }
            forStmt.add("body", body);

            stmts.add(forStmt);
        } else {
            // Emit WhileStmt
            var whileStmt = new JsonObject();
            whileStmt.addProperty("kind", "while");
            whileStmt.addProperty("fidelity", "recovered");

            // Extract condition from header's terminating branch
            JsonObject condition = extractConditionFromBlock(header);
            whileStmt.add("condition", condition);

            var body = new JsonArray();
            var bodyVisited = new HashSet<>(visited);
            bodyVisited.add(header);
            for (var bb : loopBlocks) {
                if (bb == header) continue;
                if (bodyVisited.contains(bb)) continue;
                bodyVisited.add(bb);
                liftBlockInstructions(bb, body);
                // Check for break/continue: GOTO targeting header or loopExit
                AbstractInsnNode lastInsn = getLastRealInsn(bb);
                if (lastInsn != null && lastInsn.getOpcode() == Opcodes.GOTO) {
                    var jumpInsn = (JumpInsnNode) lastInsn;
                    BasicBlock jumpTarget = labelToBlock.get(jumpInsn.label);
                    if (jumpTarget == loopExit) {
                        var breakStmt = new JsonObject();
                        breakStmt.addProperty("kind", "break");
                        breakStmt.addProperty("fidelity", "recovered");
                        body.add(breakStmt);
                    } else if (jumpTarget == header) {
                        var continueStmt = new JsonObject();
                        continueStmt.addProperty("kind", "continue");
                        continueStmt.addProperty("fidelity", "recovered");
                        body.add(continueStmt);
                    }
                }
            }
            whileStmt.add("body", body);

            stmts.add(whileStmt);
        }

        // Mark all loop blocks as visited
        visited.addAll(loopBlocks);

        // Continue with the loop exit
        if (loopExit != null) {
            liftRegion(loopExit, regionExit, stmts, visited);
        }
    }

    /**
     * Collect all basic blocks that belong to a natural loop.
     * A natural loop consists of the header plus all blocks that can reach
     * the latch without going through the header.
     */
    private Set<BasicBlock> collectLoopBlocks(BasicBlock header, BasicBlock latch) {
        var loopBlocks = new LinkedHashSet<BasicBlock>();
        loopBlocks.add(header);
        if (latch == header) return loopBlocks; // self-loop

        // Walk backwards from latch to header
        var worklist = new ArrayDeque<BasicBlock>();
        loopBlocks.add(latch);
        worklist.add(latch);

        while (!worklist.isEmpty()) {
            var bb = worklist.poll();
            for (var pred : bb.predecessors) {
                if (loopBlocks.add(pred)) {
                    if (pred != header) {
                        worklist.add(pred);
                    }
                }
            }
        }
        return loopBlocks;
    }

    // -----------------------------------------------------------------------
    // Counted loop detection (for-loop pattern)
    // -----------------------------------------------------------------------

    static class ForLoopInfo {
        JsonObject initStmt;
        JsonObject condition;
        JsonObject increment;
    }

    /**
     * Detect pattern: ISTORE/ILOAD init; IF_ICMP* comparison; IINC increment.
     *
     * Typical javac for-loop bytecode:
     *   ICONST_0 / ISTORE i          (init)
     *   label: ILOAD i / ILOAD n / IF_ICMPGE exit   (condition)
     *   ... body ...
     *   IINC i 1                     (increment)
     *   GOTO label                   (back edge)
     */
    private ForLoopInfo detectCountedLoop(BasicBlock header, Set<BasicBlock> loopBlocks) {
        if (header.instructions.isEmpty()) return null;

        // The header should end with a conditional branch
        AbstractInsnNode lastInsn = header.instructions.get(header.instructions.size() - 1);
        if (!(lastInsn instanceof JumpInsnNode jump)) return null;
        int op = jump.getOpcode();
        if (op == Opcodes.GOTO) return null; // unconditional, not a for-loop header

        // Find the IINC instruction in loop blocks (the increment)
        IincInsnNode iincInsn = null;
        for (var bb : loopBlocks) {
            if (bb == header) continue;
            for (var insn : bb.instructions) {
                if (insn instanceof IincInsnNode iinc) {
                    iincInsn = iinc;
                    break;
                }
            }
            if (iincInsn != null) break;
        }
        if (iincInsn == null) return null;

        int varIndex = iincInsn.var;
        String varName = getLocalName(varIndex);

        // Find the initialization: look at the block preceding the header.
        // The init is typically an ICONST/BIPUSH + ISTORE in the predecessor block.
        JsonObject initValue = null;
        for (var pred : header.predecessors) {
            if (loopBlocks.contains(pred)) continue; // skip latch
            // Scan from the end of the predecessor for ISTORE to varIndex
            for (int i = pred.instructions.size() - 1; i >= 0; i--) {
                var insn = pred.instructions.get(i);
                if (insn instanceof VarInsnNode store
                        && store.getOpcode() == Opcodes.ISTORE
                        && store.var == varIndex) {
                    // The value should be on the stack — look at the instruction before
                    if (i > 0) {
                        initValue = insnToExpr(pred.instructions.get(i - 1));
                    }
                    break;
                }
            }
            if (initValue != null) break;
        }

        if (initValue == null) {
            initValue = makeLiteral("integer", "0"); // fallback
        }

        // Build init: assign varName = initValue
        var initStmt = new JsonObject();
        initStmt.addProperty("kind", "assign");
        initStmt.addProperty("fidelity", "recovered");
        initStmt.add("target", makeVarRef(varName));
        initStmt.add("value", initValue);

        // Build condition from the header's comparison
        JsonObject condition = extractConditionFromBlock(header);

        // Build increment: varName = varName + incr
        var increment = new JsonObject();
        increment.addProperty("kind", "binaryop");
        increment.addProperty("fidelity", "recovered");
        increment.addProperty("op", "add");
        increment.add("lhs", makeVarRef(varName));
        increment.add("rhs", makeLiteral("integer", String.valueOf(iincInsn.incr)));

        var info = new ForLoopInfo();
        info.initStmt = initStmt;
        info.condition = condition;
        info.increment = increment;
        return info;
    }

    // -----------------------------------------------------------------------
    // If/else detection and lifting
    // -----------------------------------------------------------------------

    private boolean isConditionalBranch(BasicBlock bb) {
        if (bb.instructions.isEmpty()) return false;
        AbstractInsnNode last = bb.instructions.get(bb.instructions.size() - 1);
        if (!(last instanceof JumpInsnNode)) return false;
        return last.getOpcode() != Opcodes.GOTO;
    }

    private void liftIfElse(BasicBlock entry, BasicBlock regionExit,
                            JsonArray stmts, Set<BasicBlock> visited) {
        // Lift instructions before the branch
        liftBlockInstructionsExceptLast(entry, stmts);

        // The last instruction is a conditional branch
        JumpInsnNode jump = (JumpInsnNode) entry.instructions.get(
                entry.instructions.size() - 1);

        // Determine true and false branches
        // JVM conditional branches jump when condition is TRUE, fall through when FALSE.
        // However, javac typically inverts: IF_ICMPGE jumps to the else/exit,
        // and fall-through is the then-body. We follow the CFG edges directly.
        BasicBlock jumpTarget = labelToBlock.get(jump.label);
        BasicBlock fallThrough = getFallThrough(entry);

        // In javac output, the conditional jump typically skips the then-body.
        // So: fallThrough = then-body, jumpTarget = else or convergence.
        // We need to invert the condition sense for readability.
        BasicBlock thenBlock = fallThrough;
        BasicBlock elseBlock = jumpTarget;

        // Find convergence point (where both branches merge)
        BasicBlock converge = findConvergence(thenBlock, elseBlock, regionExit);

        // Build the IfStmt with inverted condition (since javac inverts branches)
        var ifStmt = new JsonObject();
        ifStmt.addProperty("kind", "if");
        ifStmt.addProperty("fidelity", "recovered");
        ifStmt.add("condition", invertCondition(
                extractConditionFromBranch(entry)));

        // Lift then-body
        var thenBody = new JsonArray();
        if (thenBlock != null && thenBlock != converge) {
            var thenVisited = new HashSet<>(visited);
            liftRegion(thenBlock, converge, thenBody, thenVisited);
            visited.addAll(thenVisited);
        }
        ifStmt.add("thenBody", thenBody);

        // Lift else-body (only if the else block is not the convergence point)
        var elseBody = new JsonArray();
        if (elseBlock != null && elseBlock != converge) {
            var elseVisited = new HashSet<>(visited);
            liftRegion(elseBlock, converge, elseBody, elseVisited);
            visited.addAll(elseVisited);
        }
        ifStmt.add("elseBody", elseBody);

        stmts.add(ifStmt);

        // Continue from the convergence point
        if (converge != null) {
            liftRegion(converge, regionExit, stmts, visited);
        }
    }

    /**
     * Find the convergence point where two branches merge.
     * Uses a simple forward walk: find the first block reachable from both branches.
     */
    private BasicBlock findConvergence(BasicBlock a, BasicBlock b, BasicBlock regionExit) {
        if (a == null || b == null) return regionExit;
        if (a == b) return a;

        // Collect all blocks reachable from 'a'
        var reachableFromA = new LinkedHashSet<BasicBlock>();
        var worklist = new ArrayDeque<BasicBlock>();
        worklist.add(a);
        while (!worklist.isEmpty()) {
            var bb = worklist.poll();
            if (!reachableFromA.add(bb)) continue;
            if (bb == regionExit) continue;
            worklist.addAll(bb.successors);
        }

        // Walk from 'b' and find the first block also reachable from 'a'
        // But first check if 'b' itself is reachable from 'a' — that means 'b' is the converge
        if (reachableFromA.contains(b)) {
            // 'a' can reach 'b', so 'b' might be the convergence if there's no else
            // Actually, this means 'b' is the else/converge point.
            return b;
        }

        // Collect blocks reachable from 'b'
        var reachableFromB = new LinkedHashSet<BasicBlock>();
        worklist.add(b);
        while (!worklist.isEmpty()) {
            var bb = worklist.poll();
            if (!reachableFromB.add(bb)) continue;
            if (bb == regionExit) continue;
            worklist.addAll(bb.successors);
        }

        // Find the first common block (by block index order for determinism)
        for (var bb : blocks) {
            if (reachableFromA.contains(bb) && reachableFromB.contains(bb)) {
                return bb;
            }
        }

        return regionExit;
    }

    // -----------------------------------------------------------------------
    // Switch lifting
    // -----------------------------------------------------------------------

    private void liftSwitch(BasicBlock entry, BasicBlock regionExit,
                            JsonArray stmts, Set<BasicBlock> visited) {
        liftBlockInstructionsExceptLast(entry, stmts);

        AbstractInsnNode last = entry.instructions.get(entry.instructions.size() - 1);

        List<Integer> keys = new ArrayList<>();
        List<BasicBlock> caseBlocks = new ArrayList<>();
        BasicBlock defaultBlock = null;

        if (last instanceof TableSwitchInsnNode sw) {
            defaultBlock = labelToBlock.get(sw.dflt);
            for (int i = 0; i < sw.labels.size(); i++) {
                keys.add(sw.min + i);
                caseBlocks.add(labelToBlock.get(sw.labels.get(i)));
            }
        } else if (last instanceof LookupSwitchInsnNode sw) {
            defaultBlock = labelToBlock.get(sw.dflt);
            for (int i = 0; i < sw.keys.size(); i++) {
                keys.add(sw.keys.get(i));
                caseBlocks.add(labelToBlock.get(sw.labels.get(i)));
            }
        }

        // Emit SwitchStmt JSON
        JsonObject switchValue = extractSwitchValue(entry);

        // Deduplicate: multiple keys may point to the same block
        var blockToKeys = new LinkedHashMap<BasicBlock, List<Integer>>();
        for (int i = 0; i < keys.size(); i++) {
            blockToKeys.computeIfAbsent(caseBlocks.get(i), k -> new ArrayList<>())
                    .add(keys.get(i));
        }

        // Find convergence across all case targets
        var allTargets = new ArrayList<>(blockToKeys.keySet());
        if (defaultBlock != null) allTargets.add(defaultBlock);
        BasicBlock converge = findMultiConvergence(allTargets, regionExit);

        var switchStmt = new JsonObject();
        switchStmt.addProperty("kind", "switch");
        switchStmt.addProperty("fidelity", "recovered");
        switchStmt.add("subject", switchValue);

        var casesArr = new JsonArray();

        for (var caseEntry : blockToKeys.entrySet()) {
            BasicBlock target = caseEntry.getKey();
            List<Integer> caseKeys = caseEntry.getValue();

            if (target == defaultBlock) continue; // handle default separately

            var caseObj = new JsonObject();
            // Build value expression from the first key; if multiple keys map to
            // the same block, use the first one as the value
            // (TranspileModel SwitchCase has a single value per case, so emit one
            //  case entry per group — multiple keys share the same body)
            caseObj.add("value", makeLiteral("integer", String.valueOf(caseKeys.get(0))));

            // If multiple keys, emit additional cases with same body
            var caseBody = new JsonArray();
            var caseVisited = new HashSet<>(visited);
            liftRegion(target, converge, caseBody, caseVisited);
            visited.addAll(caseVisited);
            caseObj.add("body", caseBody);
            casesArr.add(caseObj);

            // Emit extra cases for additional keys (same body, different value)
            for (int ki = 1; ki < caseKeys.size(); ki++) {
                var extraCase = new JsonObject();
                extraCase.add("value", makeLiteral("integer", String.valueOf(caseKeys.get(ki))));
                extraCase.add("body", caseBody.deepCopy());
                casesArr.add(extraCase);
            }
        }

        // Default case: value is absent (null signals default in TranspileModel)
        if (defaultBlock != null && defaultBlock != converge && !visited.contains(defaultBlock)) {
            var defaultCaseObj = new JsonObject();
            // No "value" field — indicates default case
            var defaultBody = new JsonArray();
            var defaultVisited = new HashSet<>(visited);
            liftRegion(defaultBlock, converge, defaultBody, defaultVisited);
            visited.addAll(defaultVisited);
            defaultCaseObj.add("body", defaultBody);
            casesArr.add(defaultCaseObj);
        }

        switchStmt.add("cases", casesArr);
        stmts.add(switchStmt);

        // Mark all case blocks visited
        for (var target : allTargets) {
            visited.add(target);
        }

        // Continue from convergence
        if (converge != null) {
            liftRegion(converge, regionExit, stmts, visited);
        }
    }

    private BasicBlock findMultiConvergence(List<BasicBlock> targets, BasicBlock regionExit) {
        if (targets.isEmpty()) return regionExit;
        if (targets.size() == 1) return regionExit;

        // Find the first block reachable from all targets
        List<Set<BasicBlock>> reachSets = new ArrayList<>();
        for (var target : targets) {
            var reachable = new HashSet<BasicBlock>();
            var worklist = new ArrayDeque<BasicBlock>();
            worklist.add(target);
            while (!worklist.isEmpty()) {
                var bb = worklist.poll();
                if (!reachable.add(bb)) continue;
                if (bb == regionExit) continue;
                worklist.addAll(bb.successors);
            }
            reachSets.add(reachable);
        }

        for (var bb : blocks) {
            boolean inAll = true;
            for (var rs : reachSets) {
                if (!rs.contains(bb)) { inAll = false; break; }
            }
            if (inAll) return bb;
        }
        return regionExit;
    }

    private JsonObject extractSwitchValue(BasicBlock block) {
        // Walk instructions backwards to find the last value-producing instruction
        // before the switch (the value was on the operand stack)
        for (int i = block.instructions.size() - 2; i >= 0; i--) {
            var insn = block.instructions.get(i);
            JsonObject expr = insnToExpr(insn);
            if (expr != null) return expr;
        }
        return makeUnsupported("switch_value");
    }

    // -----------------------------------------------------------------------
    // Condition extraction from blocks
    // -----------------------------------------------------------------------

    /**
     * Extract a condition expression from a block's terminal conditional branch.
     * Uses stack simulation on just the header block's instructions.
     */
    private JsonObject extractConditionFromBlock(BasicBlock block) {
        return extractConditionFromBranch(block);
    }

    private JsonObject extractConditionFromBranch(BasicBlock block) {
        if (block.instructions.isEmpty()) {
            return makeLiteral("boolean", "true");
        }

        // Simulate the operand stack for this block to find what feeds the branch
        var miniStack = new ArrayDeque<JsonObject>();
        for (var insn : block.instructions) {
            if (insn instanceof JumpInsnNode jump) {
                return buildConditionFromJump(jump, miniStack);
            }
            // Mini stack simulation for common patterns
            pushInsnToStack(insn, miniStack);
        }
        return makeLiteral("boolean", "true");
    }

    private JsonObject buildConditionFromJump(JumpInsnNode jump, Deque<JsonObject> stack) {
        int op = jump.getOpcode();
        switch (op) {
            // Unary comparisons with zero
            case Opcodes.IFEQ:
                return buildBinaryOp("eq", safePop(stack), makeLiteral("integer", "0"));
            case Opcodes.IFNE:
                return buildBinaryOp("noteq", safePop(stack), makeLiteral("integer", "0"));
            case Opcodes.IFLT:
                return buildBinaryOp("less", safePop(stack), makeLiteral("integer", "0"));
            case Opcodes.IFGE:
                return buildBinaryOp("greatereq", safePop(stack), makeLiteral("integer", "0"));
            case Opcodes.IFGT:
                return buildBinaryOp("greater", safePop(stack), makeLiteral("integer", "0"));
            case Opcodes.IFLE:
                return buildBinaryOp("lesseq", safePop(stack), makeLiteral("integer", "0"));

            // Binary int comparisons
            case Opcodes.IF_ICMPEQ: {
                var rhs = safePop(stack); var lhs = safePop(stack);
                return buildBinaryOp("eq", lhs, rhs);
            }
            case Opcodes.IF_ICMPNE: {
                var rhs = safePop(stack); var lhs = safePop(stack);
                return buildBinaryOp("noteq", lhs, rhs);
            }
            case Opcodes.IF_ICMPLT: {
                var rhs = safePop(stack); var lhs = safePop(stack);
                return buildBinaryOp("less", lhs, rhs);
            }
            case Opcodes.IF_ICMPGE: {
                var rhs = safePop(stack); var lhs = safePop(stack);
                return buildBinaryOp("greatereq", lhs, rhs);
            }
            case Opcodes.IF_ICMPGT: {
                var rhs = safePop(stack); var lhs = safePop(stack);
                return buildBinaryOp("greater", lhs, rhs);
            }
            case Opcodes.IF_ICMPLE: {
                var rhs = safePop(stack); var lhs = safePop(stack);
                return buildBinaryOp("lesseq", lhs, rhs);
            }

            // Reference comparisons
            case Opcodes.IF_ACMPEQ: {
                var rhs = safePop(stack); var lhs = safePop(stack);
                return buildBinaryOp("eq", lhs, rhs);
            }
            case Opcodes.IF_ACMPNE: {
                var rhs = safePop(stack); var lhs = safePop(stack);
                return buildBinaryOp("noteq", lhs, rhs);
            }

            // Null checks
            case Opcodes.IFNULL:
                return buildBinaryOp("eq", safePop(stack), makeLiteral("integer", "null"));
            case Opcodes.IFNONNULL:
                return buildBinaryOp("noteq", safePop(stack), makeLiteral("integer", "null"));

            default:
                return makeLiteral("boolean", "true");
        }
    }

    /**
     * Invert a condition expression. Since javac emits inverted branch conditions
     * (jump when condition is FALSE to skip the then-body), we invert for readability.
     */
    private JsonObject invertCondition(JsonObject condition) {
        if (!"binaryop".equals(condition.has("kind") ? condition.get("kind").getAsString() : "")) {
            // Wrap in a NOT
            var not_ = new JsonObject();
            not_.addProperty("kind", "unaryop");
            not_.addProperty("fidelity", "recovered");
            not_.addProperty("op", "not");
            not_.add("operand", condition);
            return not_;
        }

        String op = condition.get("op").getAsString();
        String inverted = switch (op) {
            case "eq" -> "noteq";
            case "noteq" -> "eq";
            case "less" -> "greatereq";
            case "greatereq" -> "less";
            case "greater" -> "lesseq";
            case "lesseq" -> "greater";
            default -> null;
        };

        if (inverted != null) {
            var result = condition.deepCopy();
            result.addProperty("op", inverted);
            return result;
        }

        // Fallback: wrap in NOT
        var not_ = new JsonObject();
        not_.addProperty("kind", "unaryop");
        not_.addProperty("fidelity", "recovered");
        not_.addProperty("op", "not");
        not_.add("operand", condition);
        return not_;
    }

    // -----------------------------------------------------------------------
    // Mini stack simulation for condition extraction
    // -----------------------------------------------------------------------

    private void pushInsnToStack(AbstractInsnNode insn, Deque<JsonObject> stack) {
        if (insn instanceof VarInsnNode var_) {
            int op = var_.getOpcode();
            if (op >= Opcodes.ILOAD && op <= Opcodes.ALOAD) {
                stack.push(makeVarRef(getLocalName(var_.var)));
            }
        } else if (insn instanceof LdcInsnNode ldc) {
            if (ldc.cst instanceof Integer) {
                stack.push(makeLiteral("integer", String.valueOf(ldc.cst)));
            } else if (ldc.cst instanceof Long) {
                stack.push(makeLiteral("integer", String.valueOf(ldc.cst)));
            } else if (ldc.cst instanceof Float) {
                stack.push(makeLiteral("float", String.valueOf(ldc.cst)));
            } else if (ldc.cst instanceof Double) {
                stack.push(makeLiteral("float", String.valueOf(ldc.cst)));
            } else if (ldc.cst instanceof String) {
                stack.push(makeLiteral("string", (String) ldc.cst));
            }
        } else if (insn instanceof IntInsnNode intInsn) {
            if (intInsn.getOpcode() == Opcodes.BIPUSH || intInsn.getOpcode() == Opcodes.SIPUSH) {
                stack.push(makeLiteral("integer", String.valueOf(intInsn.operand)));
            }
        } else if (insn instanceof InsnNode plain) {
            int op = plain.getOpcode();
            if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) {
                stack.push(makeLiteral("integer", String.valueOf(op - Opcodes.ICONST_0)));
            } else if (op == Opcodes.LCONST_0 || op == Opcodes.LCONST_1) {
                stack.push(makeLiteral("integer", String.valueOf(op - Opcodes.LCONST_0)));
            } else if (op == Opcodes.ACONST_NULL) {
                stack.push(makeLiteral("integer", "null"));
            } else if (op >= Opcodes.IADD && op <= Opcodes.DREM) {
                // Binary arithmetic
                var rhs = safePop(stack);
                var lhs = safePop(stack);
                String binOp = arithmeticOpName(op);
                stack.push(buildBinaryOp(binOp, lhs, rhs));
            }
        } else if (insn instanceof FieldInsnNode field) {
            if (field.getOpcode() == Opcodes.GETFIELD) {
                var obj = safePop(stack);
                var access = new JsonObject();
                access.addProperty("kind", "memberaccess");
                access.addProperty("fidelity", "recovered");
                access.add("object", obj);
                access.addProperty("member", resolveName(field.name));
                stack.push(access);
            } else if (field.getOpcode() == Opcodes.GETSTATIC) {
                var access = new JsonObject();
                access.addProperty("kind", "memberaccess");
                access.addProperty("fidelity", "recovered");
                access.add("object", makeVarRef(field.owner.replace('/', '.')));
                access.addProperty("member", resolveName(field.name));
                stack.push(access);
            }
        }
    }

    private String arithmeticOpName(int op) {
        // Map opcode ranges to operation names
        int relative = (op - Opcodes.IADD) % 4; // IADD=96, ISUB=100, ...
        // Actually the pattern is: IADD, LADD, FADD, DADD, ISUB, LSUB, FSUB, DSUB, ...
        // Groups of 4 for each type: int, long, float, double
        int group = (op - Opcodes.IADD) / 4;
        return switch (group) {
            case 0 -> "add";
            case 1 -> "sub";
            case 2 -> "mul";
            case 3 -> "div";
            case 4 -> "mod";
            default -> "add";
        };
    }

    // -----------------------------------------------------------------------
    // Block instruction lifting (delegates to BytecodeConverter-style logic)
    // -----------------------------------------------------------------------

    /**
     * Lift all instructions in a block to statements (excluding the terminator
     * branch which is handled by the control flow logic).
     */
    private void liftBlockInstructions(BasicBlock block, JsonArray stmts) {
        var converter = new BytecodeConverter(classNode, methodNode, metadata, reverseNameMap);
        var miniStmts = convertInstructions(block.instructions);
        for (int i = 0; i < miniStmts.size(); i++) {
            stmts.add(miniStmts.get(i));
        }
    }

    /**
     * Lift instructions except the last one (which is typically a branch).
     */
    private void liftBlockInstructionsExceptLast(BasicBlock block, JsonArray stmts) {
        if (block.instructions.size() <= 1) return;
        var insns = block.instructions.subList(0, block.instructions.size() - 1);
        var miniStmts = convertInstructions(insns);
        for (int i = 0; i < miniStmts.size(); i++) {
            stmts.add(miniStmts.get(i));
        }
    }

    /**
     * Convert a list of instructions to TranspileModel statements using
     * a temporary BytecodeConverter with stack simulation.
     */
    private JsonArray convertInstructions(List<AbstractInsnNode> instructions) {
        // Create a synthetic MethodNode with just these instructions
        var syntheticMethod = new MethodNode();
        syntheticMethod.access = methodNode.access;
        syntheticMethod.desc = methodNode.desc;
        syntheticMethod.name = methodNode.name;
        syntheticMethod.localVariables = methodNode.localVariables;
        syntheticMethod.parameters = methodNode.parameters;
        syntheticMethod.instructions = new InsnList();

        for (var insn : instructions) {
            // Skip branch instructions — they're handled by control flow
            if (insn instanceof JumpInsnNode && insn.getOpcode() == Opcodes.GOTO) continue;
            // Keep conditional branches for the converter (it extracts conditions)
            // but skip GOTO as it's pure control flow
            syntheticMethod.instructions.add(cloneInsn(insn));
        }

        var converter = new BytecodeConverter(classNode, syntheticMethod, metadata, reverseNameMap);
        return converter.convert();
    }

    /**
     * Clone an instruction node for use in a synthetic method.
     * ASM InsnNode is mutable and linked-list-based, so we need copies.
     */
    private AbstractInsnNode cloneInsn(AbstractInsnNode insn) {
        // ASM's clone requires a label map; for our purposes we create identity mapping
        var labelMap = new IdentityHashMap<LabelNode, LabelNode>();

        // Pre-populate with any labels we know about
        for (var iter = methodNode.instructions.iterator(); iter.hasNext(); ) {
            var i = iter.next();
            if (i instanceof LabelNode l) {
                labelMap.put(l, l);
            }
        }

        return insn.clone(labelMap);
    }

    /**
     * Return the last instruction in a block with a real opcode (skip labels, frames, line numbers).
     */
    private AbstractInsnNode getLastRealInsn(BasicBlock bb) {
        for (int i = bb.instructions.size() - 1; i >= 0; i--) {
            var insn = bb.instructions.get(i);
            if (insn.getOpcode() >= 0) return insn;
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Simple instruction-to-expression conversion (for init values, switch values)
    // -----------------------------------------------------------------------

    private JsonObject insnToExpr(AbstractInsnNode insn) {
        if (insn instanceof VarInsnNode var_) {
            int op = var_.getOpcode();
            if (op >= Opcodes.ILOAD && op <= Opcodes.ALOAD) {
                return makeVarRef(getLocalName(var_.var));
            }
        } else if (insn instanceof LdcInsnNode ldc) {
            if (ldc.cst instanceof Integer) return makeLiteral("integer", String.valueOf(ldc.cst));
            if (ldc.cst instanceof Long) return makeLiteral("integer", String.valueOf(ldc.cst));
            if (ldc.cst instanceof Float) return makeLiteral("float", String.valueOf(ldc.cst));
            if (ldc.cst instanceof Double) return makeLiteral("float", String.valueOf(ldc.cst));
            if (ldc.cst instanceof String) return makeLiteral("string", (String) ldc.cst);
        } else if (insn instanceof IntInsnNode intInsn) {
            if (intInsn.getOpcode() == Opcodes.BIPUSH || intInsn.getOpcode() == Opcodes.SIPUSH) {
                return makeLiteral("integer", String.valueOf(intInsn.operand));
            }
        } else if (insn instanceof InsnNode plain) {
            int op = plain.getOpcode();
            if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) {
                return makeLiteral("integer", String.valueOf(op - Opcodes.ICONST_0));
            }
            if (op == Opcodes.ACONST_NULL) {
                return makeLiteral("integer", "null");
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Local variable names (mirrors BytecodeConverter)
    // -----------------------------------------------------------------------

    private void initLocalNames() {
        boolean isStatic = (methodNode.access & Opcodes.ACC_STATIC) != 0;

        if (methodNode.localVariables != null) {
            for (var lv : methodNode.localVariables) {
                localNames.put(lv.index, resolveName(lv.name));
            }
        }

        if (methodNode.parameters != null) {
            int slot = isStatic ? 0 : 1;
            var argTypes = org.objectweb.asm.Type.getArgumentTypes(methodNode.desc);
            for (int i = 0; i < methodNode.parameters.size() && i < argTypes.length; i++) {
                localNames.putIfAbsent(slot, resolveName(methodNode.parameters.get(i).name));
                slot += argTypes[i].getSize();
            }
        }

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
    // JSON factories
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

    private static JsonObject buildBinaryOp(String op, JsonObject lhs, JsonObject rhs) {
        var expr = new JsonObject();
        expr.addProperty("kind", "binaryop");
        expr.addProperty("fidelity", "recovered");
        expr.addProperty("op", op);
        expr.add("lhs", lhs);
        expr.add("rhs", rhs);
        return expr;
    }

    private static JsonObject safePop(Deque<JsonObject> stack) {
        if (stack.isEmpty()) return makeUnsupported("stack_underflow");
        return stack.pop();
    }
}
