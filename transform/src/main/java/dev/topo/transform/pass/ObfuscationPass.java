package dev.topo.transform.pass;

import com.google.gson.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Renames internal (non-public) symbols using salted hash obfuscation.
 *
 * <p>The hash primitive is SipHash-2-4-128 — the same PRF the LLVM
 * backend's {@code SymbolObfuscator} uses (see
 * {@code topo-llvm/lib/Transforms/SymbolObfuscator.cpp} +
 * {@code llvm::getSipHash_2_4_128}). The cross-backend parity gate at
 * {@code scripts/audit/cross-backend-parity.py} pins both sides to the
 * same algorithm and asserts the output length is 32 hex chars (128
 * bits) for the same input string and key.
 *
 * <p>Key-message separation: salt is the PRF key (truncated /
 * zero-padded to 16 bytes), the qualified name is the PRF message.
 * The earlier {@code MessageDigest.update((salt+name).getBytes())}
 * pattern allowed prefix collisions and was equivalent to the
 * deliberately-weak {@code FNV(salt+name)} the archived
 * obfuscation-hardening pass identified as broken on the LLVM side.
 *
 * <p>Override-chain preservation: when a class hierarchy is provided
 * via the {@code ClassHierarchy}-bearing constructor, an overridable
 * method ({@code instance, non-private, non-init}) hashes on the
 * <em>declaring</em> owner — the topmost ancestor that introduces the
 * {@code (name, descriptor)} pair as an overridable method — rather
 * than its literal owner. So {@code A.foo} and {@code B.foo} where
 * {@code B extends A} hash to the same name and the JVM still
 * dispatches {@code b.foo()} to {@code B}'s body. The no-hierarchy
 * constructor keeps the older per-owner behavior for the unit-test
 * surface that builds synthetic ClassWriter bytecode without a
 * parent-class snapshot.
 */
// @category: INSTRUMENT
public class ObfuscationPass implements BasePass {
    private final JsonObject metadata;
    private final String salt;
    private final ClassHierarchy hierarchy; // may be null in unit-test surface
    private final Set<String> publicMethods = new HashSet<>();
    private final Set<String> userClassPrefixes = new HashSet<>();
    // Rename map keyed by the full MethodKey (owner, name, descriptor) so
    // JVM-legal overloads stay distinct slots. Earlier versions keyed by
    // owner::name only and collapsed overloads to the same obfuscated
    // name, producing invalid bytecode.
    //
    // Under override-chain preservation the key here is the
    // *declaring*-owner key, not the literal owner-at-call-site. Two
    // classes sharing an override therefore lookup the same slot and
    // get the same obfuscated name.
    private final Map<MethodKey, String> renameMap = new HashMap<>();

    /** qualifiedName → obfuscated hash, captured as the
     *  Pass renames. PassPipeline reads this into the sidecar's
     *  `salted_map` field. Only emitted in dev mode; the release jar's
     *  sidecar deliberately omits this map so the obfuscation is
     *  effective.
     *
     *  <p>String form of {@link MethodKey} ({@code owner.namedescriptor})
     *  so the sidecar JSON stays plain-text and round-trippable.</p>
     */
    public Map<String, String> getRenameMap() {
        var stringForm = new HashMap<String, String>(renameMap.size());
        for (var entry : renameMap.entrySet()) {
            stringForm.put(entry.getKey().toString(), entry.getValue());
        }
        return stringForm;
    }

    /** Back-compat constructor — used by unit tests that build synthetic
     *  ClassWriter bytecode and therefore have no hierarchy to consult.
     *  Override-chain preservation is disabled in this mode (each owner
     *  hashes independently, the pre-hierarchy behavior). */
    public ObfuscationPass(JsonObject metadata, String salt) {
        this(metadata, salt, null);
    }

    public ObfuscationPass(JsonObject metadata, String salt, ClassHierarchy hierarchy) {
        this.metadata = metadata;
        this.salt = salt;
        this.hierarchy = hierarchy;

        // Build set of public methods that should NOT be renamed.
        // Metadata stores visibility per-function under `functions[*].visibility`;
        // the obsolete top-level `visibilityEntries` array was never present in
        // current metadata schema.
        if (metadata.has("functions") && metadata.get("functions").isJsonObject()) {
            for (var entry : metadata.getAsJsonObject("functions").entrySet()) {
                var fn = entry.getValue().getAsJsonObject();
                if (!fn.has("visibility")) continue;
                if (!"public".equals(fn.get("visibility").getAsString())) continue;
                publicMethods.add(fn.get("qualifiedName").getAsString());
            }
        }

        // Build set of user-declared namespaces (only rename methods in user classes)
        if (metadata.has("functions") && metadata.get("functions").isJsonObject()) {
            for (var entry : metadata.getAsJsonObject("functions").entrySet()) {
                String qname = entry.getKey();
                int lastSep = qname.lastIndexOf("::");
                if (lastSep > 0) {
                    // Convert "app::Main" to "app/Main" for JVM owner matching
                    userClassPrefixes.add(qname.substring(0, lastSep).replace("::", "/"));
                }
            }
        }
    }

    @Override
    public ClassVisitor createVisitor(ClassWriter writer) {
        // Use ASM's Remapper infrastructure
        var remapper = new Remapper() {
            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                if ("<init>".equals(name) || "<clinit>".equals(name)) return name;
                // Preserve JVM entry point
                if ("main".equals(name) && "([Ljava/lang/String;)V".equals(descriptor)) return name;
                // Only rename methods in user-declared classes
                if (!userClassPrefixes.contains(owner)) return name;
                String qualifiedName = owner.replace("/", "::") + "::" + name;
                if (publicMethods.contains(qualifiedName)) return name;
                // Walk to the declaring owner so an override (B.foo over
                // A.foo) hashes to the same slot as the base — otherwise
                // the JVM no longer treats them as the same virtual method
                // after the rename.
                //
                // The hash input must include the owner so two unrelated
                // same-signature methods (e.g. two private `helper()V` in
                // different classes) get distinct names. Pre-fix the
                // obfuscator hashed only `name + descriptor`, which
                // accidentally preserved overrides but also collided every
                // unrelated same-signature pair and leaked information.
                // Using the *declaring* owner (resolved by the hierarchy
                // walk) restores both properties: overrides share a slot
                // because they all resolve to the same declaring owner;
                // unrelated/private methods stay distinct because the walk
                // stops at their literal owner.
                MethodKey leaf = MethodKey.of(owner, name, descriptor);
                MethodKey declaring = resolveDeclaringOwner(leaf);
                return renameMap.computeIfAbsent(declaring,
                    k -> obfuscate(k.owner() + "/" + k.name() + k.descriptor()));
            }
        };
        return new ClassRemapper(writer, remapper);
    }

    /**
     * Returns the {@link MethodKey} whose {@code owner} is the topmost
     * ancestor of {@code leaf.owner} that also locally declares
     * {@code leaf.name + leaf.descriptor} as an overridable method.
     *
     * <p>If no hierarchy was supplied, or the leaf is not overridable,
     * or no ancestor declares the same signature, returns {@code leaf}
     * unchanged. Constructors and class initializers are handled by the
     * caller; they never reach this method.</p>
     */
    MethodKey resolveDeclaringOwner(MethodKey leaf) {
        if (hierarchy == null) return leaf;
        // The leaf itself must be in the overridable set — if the user
        // class declares this method as private or static the JVM
        // dispatches statically and the override chain does not apply.
        if (!hierarchy.declaresOverridable(leaf)) return leaf;

        MethodKey current = leaf;
        // Bound walk-up so a pathological hierarchy can never wedge the
        // pass. JVM class depth is well under 100 in practice; pick a
        // generous cap. java/lang/Object terminates by returning null
        // from superOf().
        for (int depth = 0; depth < 256; depth++) {
            String parent = hierarchy.superOf(current.owner());
            if (parent == null) break;
            MethodKey parentKey = MethodKey.of(parent, current.name(), current.descriptor());
            if (!hierarchy.declaresOverridable(parentKey)) break;
            current = parentKey;
        }
        return current;
    }

    /**
     * SipHash-2-4-128 over the qualified name keyed by the salt. The
     * full 128-bit (32 hex char) digest is emitted to match the LLVM
     * obfuscator's strength.
     *
     * <p>Salt-as-key + name-as-message (as opposed to the earlier
     * {@code SHA-256(salt + name) → 64-bit prefix}) gives the
     * SipHash-2-4 collision bound: ~2^64 for distinct (key, message)
     * pairs vs ~2^32 for the old 64-bit truncation. The two backends
     * now share the same algorithm tag and digest length.
     */
    String obfuscate(String name) {
        byte[] keyBytes = salt.getBytes(StandardCharsets.UTF_8);
        byte[] msgBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] hash = SipHash.hash128(keyBytes, msgBytes);
        var sb = new StringBuilder("_t");
        for (int i = 0; i < 16; i++) {
            sb.append(String.format("%02x", hash[i] & 0xFF));
        }
        return sb.toString();
    }

    /** Algorithm tag emitted alongside the rename map for the
     *  cross-backend parity gate to match against the LLVM side. */
    public static final String HASH_ALGORITHM = "SipHash-2-4-128";
}
