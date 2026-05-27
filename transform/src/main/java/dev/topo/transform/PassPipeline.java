package dev.topo.transform;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.topo.transform.pass.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Runs the configured sequence of ASM bytecode transformation passes.
 *
 * When a sidecar directory is configured (via
 * {@link #run(Path, Path, Path)}), each judging Pass that accumulated
 * decisions writes one `<PassName>.json` file there with a
 * protocol-conformant common header + Pass-specific detail.
 */
public class PassPipeline {
    private final JsonObject config;
    private final JsonObject metadata;
    // Aggregated rewrites across all visited class files. VisibilityPass is
    // re-instantiated per class so PassPipeline owns the aggregate.
    private final List<JsonObject> visibilityRewrites = new ArrayList<>();
    private long visibilityElapsedNs = 0L;
    // Accumulators for the other judging Passes. Each list
    // holds qualified names in `pkg::Class::method` form (or `pkg::Class`
    // for class-level final hints) so sidecars match what users wrote in
    // `.topo`. Per-Pass elapsed_ns is summed across class invocations.
    private final List<String> staticPromotedMethods = new ArrayList<>();
    private long staticPromotionElapsedNs = 0L;
    private boolean staticPromotionRan = false;
    private final List<String> inlineHintFinalClasses = new ArrayList<>();
    private final List<String> inlineHintFinalMethods = new ArrayList<>();
    private long inlineHintElapsedNs = 0L;
    private boolean inlineHintRan = false;
    // ReturnSpec — host_method → ordered list of dead field names removed.
    private final Map<String, Set<String>> returnSpecEliminated = new LinkedHashMap<>();
    private long returnSpecElapsedNs = 0L;
    private boolean returnSpecRan = false;
    // ParallelPass — host_method → spawn_sites count.
    private final Map<String, Integer> parallelSpawnSites = new LinkedHashMap<>();
    private long parallelElapsedNs = 0L;
    private boolean parallelRan = false;
    // PipelinePass — host_method → stage_count of the lowered chain.
    private final Map<String, Integer> pipelineLowered = new LinkedHashMap<>();
    private long pipelineElapsedNs = 0L;
    private boolean pipelineRan = false;
    // AdaptivePass — host_method → switchpoint name (= host_method).
    private final Map<String, String> adaptiveDispatch = new LinkedHashMap<>();
    private long adaptiveElapsedNs = 0L;
    private boolean adaptiveRan = false;
    // ArenaPass — host_method → arena byte size injected.
    private final Map<String, Long> arenaInjected = new LinkedHashMap<>();
    private long arenaElapsedNs = 0L;
    private boolean arenaRan = false;
    // DataLayoutPass — host_type (Topo `::` form) → set of {topo_name, jvm_name, descriptor}.
    private final Map<String, Set<JsonObject>> dataLayoutRenames = new LinkedHashMap<>();
    private long dataLayoutElapsedNs = 0L;
    private boolean dataLayoutRan = false;
    // TypeNarrowingPass — host_method → narrowed Array.get site count.
    private final Map<String, Integer> typeNarrowingSites = new LinkedHashMap<>();
    private long typeNarrowingElapsedNs = 0L;
    private boolean typeNarrowingRan = false;
    // ObfuscationPass — qualifiedName → obfuscated hash (dev-mode reverse map).
    private final Map<String, String> obfuscationSaltedMap = new LinkedHashMap<>();
    private long obfuscationElapsedNs = 0L;
    private boolean obfuscationRan = false;

    public PassPipeline(JsonObject config, JsonObject metadata) {
        this.config = config;
        this.metadata = metadata;
    }

    /** Back-compat — runs without sidecar emission. */
    public void run(Path inputDir, Path outputDir) throws IOException {
        run(inputDir, outputDir, null);
    }

    /** When {@code sidecarDir} is non-null, writes per-Pass
     *  reports to `<sidecarDir>/<PassName>.json` after the transform loop
     *  completes. Failure to write the sidecar logs to stderr but does not
     *  break the build (sidecar is non-load-bearing).
     *
     *  <p><strong>Single-threaded by design at the current scale.</strong>
     *  Every benchmark JAR in {@code topo-llvm/benchmarks/} and every
     *  Java-side fixture is well under one hundred classes; for a JAR of
     *  that size the JVM startup + classpath scan dominates, and a
     *  parallel-stream rewrite of the per-class loop would only add the
     *  cost of thread-safe accumulators
     *  ({@link #visibilityRewrites}, {@link #staticPromotedMethods},
     *  {@link #returnSpecEliminated}, etc.) without measurable wall-clock
     *  win. The threshold at which parallelisation becomes worthwhile is
     *  on the order of one thousand classes per JAR — once a real user
     *  project crosses that, switch this loop to a
     *  {@link java.util.stream.Stream#parallel parallel stream} and swap
     *  every accumulator field to a concurrent/thread-safe collection.
     *  Until that point, the sequential walk keeps the implementation
     *  obvious and the sidecar emission order stable. */
    public void run(Path inputDir, Path outputDir, Path sidecarDir) throws IOException {
        Files.createDirectories(outputDir);

        // Collect all .class files
        List<Path> classFiles;
        try (var stream = Files.walk(inputDir)) {
            classFiles = stream.filter(p -> p.toString().endsWith(".class")).toList();
        }

        // Read every class once so we can both feed it to applyPasses() and
        // build a class-hierarchy snapshot ObfuscationPass needs to keep
        // override chains intact (issue
        // jvm-obfuscation-and-method-key-design-gaps). The cost is one extra
        // ClassReader pass per file with SKIP_CODE — negligible next to the
        // 14 Pass rewrites the main loop runs.
        List<byte[]> originals = new ArrayList<>(classFiles.size());
        for (Path classFile : classFiles) {
            originals.add(Files.readAllBytes(classFile));
        }
        ClassHierarchy hierarchy = ClassHierarchy.fromClassBytes(originals);

        for (int i = 0; i < classFiles.size(); i++) {
            Path classFile = classFiles.get(i);
            byte[] transformed = applyPasses(originals.get(i), hierarchy);

            // Preserve relative path structure
            Path relative = inputDir.relativize(classFile);
            Path outFile = outputDir.resolve(relative);
            Files.createDirectories(outFile.getParent());
            Files.write(outFile, transformed);
        }

        if (sidecarDir != null) {
            writeSidecars(sidecarDir);
        }
    }

    /** Writes one JSON file per judging Pass into sidecarDir. Errors are
     *  reported to stderr; sidecar emission is non-load-bearing. */
    private void writeSidecars(Path sidecarDir) {
        try {
            Files.createDirectories(sidecarDir);
        } catch (IOException e) {
            System.err.println("warning: cannot create sidecar dir " + sidecarDir + ": " + e.getMessage());
            return;
        }

        // VisibilityPass — common header + rewrites list.
        JsonObject visibility = new JsonObject();
        JsonObject header = new JsonObject();
        header.addProperty("pass", "VisibilityPass");
        header.addProperty("category", "COVERED");
        header.addProperty("fired", !visibilityRewrites.isEmpty());
        header.addProperty("fired_count", visibilityRewrites.size());
        header.addProperty("decision",
            visibilityRewrites.isEmpty() ? "no_rewrites" : "applied");
        header.addProperty("reason",
            visibilityRewrites.isEmpty()
                ? "no .topo function declared a non-default visibility"
                : "rewrote method access flags to match .topo visibility");
        header.addProperty("elapsed_ns", visibilityElapsedNs);
        visibility.add("header", header);

        JsonArray rewrites = new JsonArray();
        for (var r : visibilityRewrites) rewrites.add(r);
        visibility.add("rewrites", rewrites);

        writeAtomic(sidecarDir.resolve("VisibilityPass.json"), visibility);

        // StaticPromotionPass — common header + promoted_methods list.
        // Schema: { "promoted_methods": ["geom::Mesh::normalize"] }.
        // Emitted only when the gate fired so the sibling directory's
        // presence accurately reflects which Passes the build ran. Mechanical
        // Passes that ran but had nothing to do still write a sidecar with
        // `fired=false` so query consumers can distinguish "Pass disabled"
        // (sidecar absent) from "Pass ran but no methods qualified"
        // (sidecar present, fired_count=0).
        if (staticPromotionRan) {
            JsonObject sp = new JsonObject();
            JsonObject spHeader = new JsonObject();
            spHeader.addProperty("pass", "StaticPromotionPass");
            spHeader.addProperty("category", "INFRA");
            spHeader.addProperty("fired", !staticPromotedMethods.isEmpty());
            spHeader.addProperty("fired_count", staticPromotedMethods.size());
            spHeader.addProperty("decision",
                staticPromotedMethods.isEmpty() ? "no_promotions" : "applied");
            spHeader.addProperty("reason",
                staticPromotedMethods.isEmpty()
                    ? "no .topo function declared isStatic"
                    : "promoted instance methods declared isStatic to ACC_STATIC "
                      + "with descriptor-extension (slot 0 → explicit receiver "
                      + "parameter so existing slot layout stays valid)");
            spHeader.addProperty("elapsed_ns", staticPromotionElapsedNs);
            sp.add("header", spHeader);
            JsonArray promoted = new JsonArray();
            for (String m : staticPromotedMethods) promoted.add(m);
            sp.add("promoted_methods", promoted);
            writeAtomic(sidecarDir.resolve("StaticPromotionPass.json"), sp);
        }

        // InlineHintPass — common header + final_classes + final_methods.
        // Schema: { "final_methods": [...], "final_classes": [...] }.
        // Listed separately because users care which classes became sealed
        // for HotSpot CHA vs which individual methods got the hint.
        if (inlineHintRan) {
            JsonObject ih = new JsonObject();
            JsonObject ihHeader = new JsonObject();
            ihHeader.addProperty("pass", "InlineHintPass");
            ihHeader.addProperty("category", "INFRA");
            int total = inlineHintFinalClasses.size() + inlineHintFinalMethods.size();
            ihHeader.addProperty("fired", total > 0);
            ihHeader.addProperty("fired_count", total);
            ihHeader.addProperty("decision",
                total == 0 ? "no_hints" : "applied");
            ihHeader.addProperty("reason",
                total == 0
                    ? "no internal/private declaration eligible for ACC_FINAL"
                    : "added ACC_FINAL to internal/private classes/methods so "
                      + "HotSpot CHA can devirtualise virtual call sites");
            ihHeader.addProperty("elapsed_ns", inlineHintElapsedNs);
            ih.add("header", ihHeader);
            JsonArray cls = new JsonArray();
            for (String c : inlineHintFinalClasses) cls.add(c);
            ih.add("final_classes", cls);
            JsonArray meth = new JsonArray();
            for (String m : inlineHintFinalMethods) meth.add(m);
            ih.add("final_methods", meth);
            writeAtomic(sidecarDir.resolve("InlineHintPass.json"), ih);
        }

        // ReturnSpecializationPass — common header + entries[{host_method,
        // eliminated_fields[]}]. Mirrors LLVM-side schema modulo
        // field-name (vs field-index) — JVM doesn't number struct fields.
        if (returnSpecRan) {
            JsonObject rs = new JsonObject();
            JsonObject hdr = new JsonObject();
            hdr.addProperty("pass", "ReturnSpecializationPass");
            hdr.addProperty("category", "COVERED");
            int totalEliminated = returnSpecEliminated.values().stream()
                .mapToInt(Set::size).sum();
            hdr.addProperty("fired", totalEliminated > 0);
            hdr.addProperty("fired_count", totalEliminated);
            hdr.addProperty("decision",
                totalEliminated == 0 ? "no_eliminations" : "applied");
            hdr.addProperty("reason",
                totalEliminated == 0
                    ? "no dead return field detected (every multi-return "
                      + "field is consumed by some call site)"
                    : "replaced PUTFIELD with POP/POP2 for dead return "
                      + "fields confirmed unused across all call sites");
            hdr.addProperty("elapsed_ns", returnSpecElapsedNs);
            rs.add("header", hdr);
            JsonArray entries = new JsonArray();
            for (var entry : returnSpecEliminated.entrySet()) {
                JsonObject row = new JsonObject();
                row.addProperty("host_method", entry.getKey());
                JsonArray fields = new JsonArray();
                for (String f : entry.getValue()) fields.add(f);
                row.add("eliminated_fields", fields);
                entries.add(row);
            }
            rs.add("entries", entries);
            writeAtomic(sidecarDir.resolve("ReturnSpecializationPass.json"), rs);
        }

        // ParallelPass — parallel_split:[{host_method, spawn_sites}].
        if (parallelRan) {
            JsonObject pp = new JsonObject();
            JsonObject hdr = new JsonObject();
            hdr.addProperty("pass", "ParallelPass");
            hdr.addProperty("category", "ENHANCE");
            int sumSites = parallelSpawnSites.values().stream()
                .mapToInt(Integer::intValue).sum();
            hdr.addProperty("fired", sumSites > 0);
            hdr.addProperty("fired_count", sumSites);
            hdr.addProperty("decision",
                sumSites == 0 ? "no_parallel_split" : "applied");
            hdr.addProperty("reason",
                sumSites == 0
                    ? "no orchestrator method had parallel-stage candidate "
                      + "calls (void static invocations on parallel stage)"
                    : "wrapped eligible void static calls in "
                      + "Parallel.spawn() with awaitAllVoid at return");
            hdr.addProperty("elapsed_ns", parallelElapsedNs);
            pp.add("header", hdr);
            JsonArray splits = new JsonArray();
            for (var entry : parallelSpawnSites.entrySet()) {
                JsonObject row = new JsonObject();
                row.addProperty("host_method", entry.getKey());
                row.addProperty("spawn_sites", entry.getValue());
                splits.add(row);
            }
            pp.add("parallel_split", splits);
            writeAtomic(sidecarDir.resolve("ParallelPass.json"), pp);
        }

        // PipelinePass — lowered_pipelines:[{host_method, stage_count}].
        if (pipelineRan) {
            JsonObject pp = new JsonObject();
            JsonObject hdr = new JsonObject();
            hdr.addProperty("pass", "PipelinePass");
            hdr.addProperty("category", "ENHANCE");
            int count = pipelineLowered.size();
            hdr.addProperty("fired", count > 0);
            hdr.addProperty("fired_count", count);
            hdr.addProperty("decision",
                count == 0 ? "no_pipelines_lowered" : "applied");
            hdr.addProperty("reason",
                count == 0
                    ? "no pipeline logic block matched an int-returning "
                      + "orchestrator method"
                    : "rewrote pipeline orchestrator bodies into "
                      + "CompletableFuture chains derived from the DAG");
            hdr.addProperty("elapsed_ns", pipelineElapsedNs);
            pp.add("header", hdr);
            JsonArray lowered = new JsonArray();
            for (var entry : pipelineLowered.entrySet()) {
                JsonObject row = new JsonObject();
                row.addProperty("host_method", entry.getKey());
                row.addProperty("stage_count", entry.getValue());
                lowered.add(row);
            }
            pp.add("lowered_pipelines", lowered);
            writeAtomic(sidecarDir.resolve("PipelinePass.json"), pp);
        }

        // AdaptivePass — dispatch_tables:[{host_method, switchpoint}].
        if (adaptiveRan) {
            JsonObject ap = new JsonObject();
            JsonObject hdr = new JsonObject();
            hdr.addProperty("pass", "AdaptivePass");
            hdr.addProperty("category", "RUNTIME");
            int count = adaptiveDispatch.size();
            hdr.addProperty("fired", count > 0);
            hdr.addProperty("fired_count", count);
            hdr.addProperty("decision",
                count == 0 ? "no_dispatch_instrumented" : "applied");
            hdr.addProperty("reason",
                count == 0
                    ? "no function declared `adaptive` and force mode is off"
                    : "injected Adaptive.checkDispatch + hit counter at "
                      + "method entry; AdaptiveMonitor.tick fires "
                      + "SwitchPoint invalidation after warmup");
            hdr.addProperty("elapsed_ns", adaptiveElapsedNs);
            ap.add("header", hdr);
            JsonArray dispatch = new JsonArray();
            for (var entry : adaptiveDispatch.entrySet()) {
                JsonObject row = new JsonObject();
                row.addProperty("host_method", entry.getKey());
                row.addProperty("switchpoint", entry.getValue());
                dispatch.add(row);
            }
            ap.add("dispatch_tables", dispatch);
            writeAtomic(sidecarDir.resolve("AdaptivePass.json"), ap);
        }

        // ArenaPass — scopes:[{host_method, scope, size}].
        if (arenaRan) {
            JsonObject ar = new JsonObject();
            JsonObject hdr = new JsonObject();
            hdr.addProperty("pass", "ArenaPass");
            hdr.addProperty("category", "ENHANCE");
            int count = arenaInjected.size();
            hdr.addProperty("fired", count > 0);
            hdr.addProperty("fired_count", count);
            hdr.addProperty("decision",
                count == 0 ? "no_scopes_wrapped" : "applied");
            hdr.addProperty("reason",
                count == 0
                    ? "no `lifetime(scope)` declaration matched a method"
                    : "wrapped scope-entry method bodies in Arena "
                      + "try-finally lifecycle (setCurrent/clearCurrent)");
            hdr.addProperty("elapsed_ns", arenaElapsedNs);
            ar.add("header", hdr);
            JsonArray scopes = new JsonArray();
            for (var entry : arenaInjected.entrySet()) {
                JsonObject row = new JsonObject();
                row.addProperty("host_method", entry.getKey());
                row.addProperty("scope", "frame");
                row.addProperty("size", entry.getValue());
                scopes.add(row);
            }
            ar.add("scopes", scopes);
            writeAtomic(sidecarDir.resolve("ArenaPass.json"), ar);
        }

        // DataLayoutPass — field_rename:[{host_type, fields[]}].
        if (dataLayoutRan) {
            JsonObject dl = new JsonObject();
            JsonObject hdr = new JsonObject();
            hdr.addProperty("pass", "DataLayoutPass");
            hdr.addProperty("category", "ENHANCE");
            int typeCount = dataLayoutRenames.size();
            int totalFields = dataLayoutRenames.values().stream()
                .mapToInt(Set::size).sum();
            hdr.addProperty("fired", typeCount > 0);
            hdr.addProperty("fired_count", totalFields);
            // Only force mode actually runs the visitor; the auto branch
            // returns a passthrough visitor so this section reaches only
            // when mode=="force" and at least one single-field candidate
            // appeared.
            hdr.addProperty("decision",
                typeCount == 0 ? "no_candidates" : "forced_soa");
            hdr.addProperty("reason",
                typeCount == 0
                    ? "no single-field AoS sweep over a Topo Array<T> "
                      + "detected in any transformed method"
                    : "rewrote single-field Array<T>.get(i).field access "
                      + "to ColumnarView primitive-column reads");
            hdr.addProperty("elapsed_ns", dataLayoutElapsedNs);
            dl.add("header", hdr);
            JsonArray rename = new JsonArray();
            for (var entry : dataLayoutRenames.entrySet()) {
                JsonObject row = new JsonObject();
                row.addProperty("host_type", entry.getKey());
                JsonArray fields = new JsonArray();
                for (JsonObject f : entry.getValue()) fields.add(f);
                row.add("fields", fields);
                rename.add(row);
            }
            dl.add("field_rename", rename);
            writeAtomic(sidecarDir.resolve("DataLayoutPass.json"), dl);
        }

        // TypeNarrowingPass — narrowed_sites:[{host_method, count}].
        if (typeNarrowingRan) {
            JsonObject tn = new JsonObject();
            JsonObject hdr = new JsonObject();
            hdr.addProperty("pass", "TypeNarrowingPass");
            hdr.addProperty("category", "COVERED");
            int totalSites = typeNarrowingSites.values().stream()
                .mapToInt(Integer::intValue).sum();
            hdr.addProperty("fired", totalSites > 0);
            hdr.addProperty("fired_count", totalSites);
            hdr.addProperty("decision",
                totalSites == 0 ? "no_narrowing_sites" : "applied");
            hdr.addProperty("reason",
                totalSites == 0
                    ? "no Array.get(i)+CHECKCAST site found in any method"
                    : "rewrote Array.get(i)+CHECKCAST sites to "
                      + "rawData()+AALOAD+CHECKCAST (skips the virtual "
                      + "call edge HotSpot would otherwise need to "
                      + "devirtualise via speculation)");
            hdr.addProperty("elapsed_ns", typeNarrowingElapsedNs);
            tn.add("header", hdr);
            JsonArray sites = new JsonArray();
            for (var entry : typeNarrowingSites.entrySet()) {
                JsonObject row = new JsonObject();
                row.addProperty("host_method", entry.getKey());
                row.addProperty("count", entry.getValue());
                sites.add(row);
            }
            tn.add("narrowed_sites", sites);
            writeAtomic(sidecarDir.resolve("TypeNarrowingPass.json"), tn);
        }

        // ObfuscationPass — salted_map:{<obfuscated>: <original>} (dev only).
        // The schema specifies dev mode emits this map and release builds
        // omit it. Topo.toml currently doesn't surface that distinction at
        // BackendRequest level, so we emit always and trust the user not
        // to ship the sidecar dir alongside their release jar — same
        // pattern the *.topo-passes/ directory already encourages.
        if (obfuscationRan) {
            JsonObject ob = new JsonObject();
            JsonObject hdr = new JsonObject();
            hdr.addProperty("pass", "ObfuscationPass");
            hdr.addProperty("category", "INSTRUMENT");
            int count = obfuscationSaltedMap.size();
            hdr.addProperty("fired", count > 0);
            hdr.addProperty("fired_count", count);
            hdr.addProperty("decision",
                count == 0 ? "no_symbols_renamed" : "applied");
            hdr.addProperty("reason",
                count == 0
                    ? "no internal/private user-class symbol matched the "
                      + "rename criteria"
                    : "renamed non-public user-class methods to salted "
                      + "SipHash-2-4-128 hashes; sidecar holds the "
                      + "dev-time reverse map");
            hdr.addProperty("elapsed_ns", obfuscationElapsedNs);
            ob.add("header", hdr);
            JsonObject saltedMap = new JsonObject();
            for (var entry : obfuscationSaltedMap.entrySet()) {
                // Key the JSON object by obfuscated hash so debuggers can
                // look up the original by the name they see in a stack trace.
                saltedMap.addProperty(entry.getValue(), entry.getKey());
            }
            ob.add("salted_map", saltedMap);
            writeAtomic(sidecarDir.resolve("ObfuscationPass.json"), ob);
        }
    }

    private static void writeAtomic(Path dest, JsonObject body) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Path tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, gson.toJson(body) + "\n");
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("warning: failed to write " + dest + ": " + e.getMessage());
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }

    private byte[] applyPasses(byte[] classBytes, ClassHierarchy hierarchy) {
        // Each pass reads bytes and produces new bytes via ClassVisitor chain.
        //
        // The 15-Pass sequence below is the canonical ordering documented
        // in the topo-jvm pass-pipeline spec (the "Pass table", entries
        // 1-15). Sequential 1-15 numbering replaces the historical
        // fractional indices (1.3 / 1.5 / 1.75 / ... / 5.5) that accumulated
        // as new passes were inserted between existing ones — those numbers
        // were a fossil record of insertion history, not a stable schema, and
        // diverged from the registry-style numbering on the LLVM side. The
        // spec sub-section on why LLVM and JVM keep different
        // registration shapes explains the divergence: LLVM
        // merges link-time into one module + one PassCategoryRegistry sweep;
        // JVM rewrites every classfile independently, no merged IR, and each
        // ASM Pass is a stateless visitor).
        //
        // Pass-ordering constraints (also pinned in the spec):
        //   * Visibility runs first (later passes may key on the latest
        //     access flags, e.g. InlineHint adds ACC_FINAL).
        //   * Prefetch must run BEFORE TypeNarrowing — TypeNarrowing
        //     rewrites the `Array.get(i)` call site that Prefetch's
        //     detector keys on.
        //   * Parallel runs BEFORE LoopVectorize — the loop body must be
        //     analyzable before the parallel wrapper hides it.
        //   * StageReorder runs BEFORE Obfuscation — name-based stage
        //     lookup needs the original symbol names.
        byte[] current = classBytes;

        // Pass 1: Visibility (always applied if metadata present)
        if (metadata.has("functions")) {
            VisibilityPass vp = new VisibilityPass(metadata);
            long t0 = System.nanoTime();
            current = applyPass(current, vp);
            visibilityElapsedNs += System.nanoTime() - t0;
            visibilityRewrites.addAll(vp.getRewrites());
        }

        // Pass 2: Static promotion (isStatic methods → ACC_STATIC)
        if (metadata.has("functions") && getOptLevel() >= 1) {
            StaticPromotionPass sp = new StaticPromotionPass(metadata);
            long t0 = System.nanoTime();
            current = applyPass(current, sp);
            staticPromotionElapsedNs += System.nanoTime() - t0;
            staticPromotionRan = true;
            staticPromotedMethods.addAll(sp.getPromotedMethods());
        }

        // Pass 3: Inline hints (add ACC_FINAL for JIT devirtualization)
        if (metadata.has("functions") && getOptLevel() >= 1) {
            InlineHintPass ih = new InlineHintPass(config, metadata);
            long t0 = System.nanoTime();
            current = applyPass(current, ih);
            inlineHintElapsedNs += System.nanoTime() - t0;
            inlineHintRan = true;
            inlineHintFinalClasses.addAll(ih.getFinalClasses());
            inlineHintFinalMethods.addAll(ih.getFinalMethods());
        }

        // Pass 4: Return specialization (dead return field elimination)
        if (metadata.has("callSites") && metadata.has("functions") && getOptLevel() >= 1) {
            ReturnSpecializationPass rs = new ReturnSpecializationPass(config, metadata);
            long t0 = System.nanoTime();
            current = applyPass(current, rs);
            returnSpecElapsedNs += System.nanoTime() - t0;
            returnSpecRan = true;
            for (var entry : rs.getEliminatedByMethod().entrySet()) {
                returnSpecEliminated.merge(entry.getKey(),
                    new LinkedHashSet<>(entry.getValue()),
                    (a, b) -> { a.addAll(b); return a; });
            }
        }

        // Pass 5: Data layout AoS→SoA (force only — SoA benefits only appear
        // for single-field sweeps where contiguous float[] enables C2 auto-vectorization;
        // auto mode would mislead users into expecting gains on multi-field workloads)
        if (isEnabledForce("dataLayoutCfg")) {
            DataLayoutPass dl = new DataLayoutPass(config, metadata);
            long t0 = System.nanoTime();
            current = applyPass(current, dl);
            dataLayoutElapsedNs += System.nanoTime() - t0;
            dataLayoutRan = true;
            for (var entry : dl.getFieldRenamesByType().entrySet()) {
                dataLayoutRenames.merge(entry.getKey(),
                    new LinkedHashSet<>(entry.getValue()),
                    (a, b) -> { a.addAll(b); return a; });
            }
        }

        // Pass 6: Prefetch hints (streaming loop + pipeline boundary)
        // force-only: declaration-class witness on JVM (no speedup path).
        // MUST run before TypeNarrowingPass — PrefetchPass's detector looks for
        // `INVOKEVIRTUAL Array.get(i)`, which TypeNarrowingPass rewrites away.
        // The injected prefetch call site uses `Array.rawData()[i+distance]`,
        // so it composes correctly with a later TypeNarrowingPass run on the
        // original loop body.
        if (isEnabledForce("prefetchCfg")) {
            current = applyPass(current, new PrefetchPass(config, metadata));
        }

        // Pass 7: Type narrowing (Array.get() → rawData()[i] direct access)
        // force-only: C2 already devirtualizes monomorphic Array.get() after warmup
        if (isEnabledForce("typeNarrowingCfg")) {
            TypeNarrowingPass tn = new TypeNarrowingPass(config, metadata);
            long t0 = System.nanoTime();
            current = applyPass(current, tn);
            typeNarrowingElapsedNs += System.nanoTime() - t0;
            typeNarrowingRan = true;
            for (var entry : tn.getNarrowedByMethod().entrySet()) {
                typeNarrowingSites.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }

        // Pass 8: Parallel (if enabled)
        if (isEnabled("parallelCfg")) {
            ParallelPass pp = new ParallelPass(config, metadata);
            long t0 = System.nanoTime();
            current = applyPass(current, pp);
            parallelElapsedNs += System.nanoTime() - t0;
            parallelRan = true;
            for (var entry : pp.getSpawnSitesByMethod().entrySet()) {
                parallelSpawnSites.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }

        // Pass 9: Loop vectorization via Vector API (force only — auto is a no-op
        // because C2 already auto-vectorizes simple float[] loops; this pass targets
        // gathered access patterns that C2 cannot handle)
        if (isEnabledForce("loopParallelCfg")) {
            current = applyPass(current, new LoopVectorizePass(config, metadata));
        }

        // Pass 10: Pipeline — fires if any logicBlock is a pipeline AND the
        // `[pipeline].mode` config gate is not `off`.  The Topo.toml
        // `[pipeline]` section maps to `pipelineCfg.mode` in BackendRequest
        // JSON; default is `auto`, so legacy projects that never wrote a
        // `[pipeline]` section keep running.  Benchmark base configs set
        // `mode = "off"` to compare against the unwritten baseline —
        // before this gate was added those `off` writes were silently
        // ignored (knownSections didn't list `pipeline`), so base / auto /
        // forced jars came out byte-identical on the PipelinePass axis.
        if (isPipelineEnabled() && hasAnyPipelineLogicBlock()) {
            PipelinePass pl = new PipelinePass(config, metadata);
            long t0 = System.nanoTime();
            current = applyPass(current, pl);
            pipelineElapsedNs += System.nanoTime() - t0;
            pipelineRan = true;
            for (var entry : pl.getLoweredByMethod().entrySet()) {
                pipelineLowered.put(entry.getKey(), entry.getValue());
            }
        }

        // Pass 11: Observability (if enabled)
        if (isEnabled("observabilityCfg")) {
            current = applyPass(current, new ObservabilityPass(config, metadata));
        }

        // Pass 12: Adaptive dispatch (if enabled)
        if (isEnabled("adaptiveCfg")) {
            AdaptivePass ap = new AdaptivePass(config, metadata);
            long t0 = System.nanoTime();
            current = applyPass(current, ap);
            adaptiveElapsedNs += System.nanoTime() - t0;
            adaptiveRan = true;
            for (var entry : ap.getDispatchTablesByMethod().entrySet()) {
                adaptiveDispatch.put(entry.getKey(), entry.getValue());
            }
        }

        // Pass 13: Arena lifecycle (if lifetime enabled)
        if (isEnabled("lifetimeCfg")) {
            ArenaPass arena = new ArenaPass(config, metadata);
            long t0 = System.nanoTime();
            current = applyPass(current, arena);
            arenaElapsedNs += System.nanoTime() - t0;
            arenaRan = true;
            for (var entry : arena.getInjectedByMethod().entrySet()) {
                arenaInjected.put(entry.getKey(), entry.getValue());
            }
        }

        // Pass 14: Stage reorder (always-mechanical; INFRA category — no
        // judging accumulator, no sidecar block. Documented in the
        // topo-jvm pass-pipeline spec as row 14 of the Pass table.)
        current = applyPass(current, new StageReorderPass(config, metadata));

        // Pass 15: Obfuscation (if mode is salted)
        String obfMode = config.has("obfMode") ? config.get("obfMode").getAsString() : "normal";
        if ("salted".equals(obfMode)) {
            String salt = config.has("obfSalt") ? config.get("obfSalt").getAsString() : "";
            ObfuscationPass obf = new ObfuscationPass(metadata, salt, hierarchy);
            long t0 = System.nanoTime();
            current = applyPass(current, obf);
            obfuscationElapsedNs += System.nanoTime() - t0;
            obfuscationRan = true;
            // ObfuscationPass populates its renameMap lazily as the ClassRemapper
            // resolves names; one Pass instance is created per class file, so
            // each invocation reveals more renames as more classes are visited.
            obfuscationSaltedMap.putAll(obf.getRenameMap());
        }

        return current;
    }

    private byte[] applyPass(byte[] input, BasePass pass) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        ClassVisitor visitor = pass.createVisitor(writer);
        // EXPAND_FRAMES is required by LocalVariablesSorter (used by ArenaPass and
        // other adapters that allocate locals mid-method). COMPUTE_FRAMES below
        // recompresses frames on output, so there is no size penalty.
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private int getOptLevel() {
        return config.has("optLevel") ? config.get("optLevel").getAsInt() : 2;
    }

    private boolean isEnabled(String configKey) {
        if (!config.has(configKey)) return false;
        var cfg = config.getAsJsonObject(configKey);
        if (!cfg.has("mode")) return false;
        String mode = cfg.get("mode").getAsString();
        return "auto".equals(mode) || "force".equals(mode);
    }

    /**
     * Pipeline gate with Auto default.  Unlike other features whose
     * default is Off, PipelineConfig defaults to Auto on the C++ side —
     * so when `pipelineCfg` is absent from BackendRequest JSON (legacy
     * caller) OR the `mode` field is missing, we still run.  Only an
     * explicit `mode = "off"` disables the pass.
     */
    private boolean isPipelineEnabled() {
        if (!config.has("pipelineCfg")) return true;
        var cfg = config.getAsJsonObject("pipelineCfg");
        if (!cfg.has("mode")) return true;
        String mode = cfg.get("mode").getAsString();
        return !"off".equals(mode);
    }

    private boolean hasAnyPipelineLogicBlock() {
        if (!metadata.has("logicBlocks")) return false;
        var blocks = metadata.getAsJsonObject("logicBlocks");
        for (var entry : blocks.entrySet()) {
            var v = entry.getValue();
            if (!v.isJsonObject()) continue;
            var o = v.getAsJsonObject();
            if (o.has("isPipeline") && o.get("isPipeline").getAsBoolean()) return true;
        }
        return false;
    }

    private boolean isEnabledForce(String configKey) {
        if (!config.has(configKey)) return false;
        var cfg = config.getAsJsonObject(configKey);
        if (!cfg.has("mode")) return false;
        return "force".equals(cfg.get("mode").getAsString());
    }
}
