package dev.topo.transform.pass;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for {@link MethodKey} — the (owner, name, descriptor)
 * triple that JVM-side passes use as method identity.
 */
class MethodKeyTest {

    @Test
    void distinctDescriptorsProduceDistinctKeys() {
        MethodKey a = MethodKey.of("app/Service", "helper", "(I)V");
        MethodKey b = MethodKey.of("app/Service", "helper", "(J)V");
        assertNotEquals(a, b, "JVM-legal overloads must hash to distinct slots");
        assertNotEquals(a.hashCode(), b.hashCode(),
            "hashCode must split overloads (probabilistic — picked descriptors that differ)");
    }

    @Test
    void sameAllFieldsAreEqual() {
        MethodKey a = MethodKey.of("app/Service", "helper", "(I)V");
        MethodKey b = MethodKey.of("app/Service", "helper", "(I)V");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void wildcardMatchesAnyDescriptor() {
        // Wildcard matching lives on the free helper MethodKeyMatcher —
        // not on MethodKey itself — so a wildcard key cannot accidentally
        // be used as a HashMap key against concrete entries (see issue
        // jvm-obfuscation-and-method-key-design-gaps).
        MethodKey wildcard = MethodKey.ofAnyOverload("app/Service", "helper");
        MethodKey intOverload = MethodKey.of("app/Service", "helper", "(I)V");
        MethodKey longOverload = MethodKey.of("app/Service", "helper", "(J)V");
        assertTrue(MethodKeyMatcher.matches(wildcard, intOverload));
        assertTrue(MethodKeyMatcher.matches(wildcard, longOverload));
        assertTrue(MethodKeyMatcher.matches(intOverload, wildcard));
        assertFalse(MethodKeyMatcher.matches(intOverload, longOverload),
            "exact-form overloads must not match each other");
    }

    @Test
    void wildcardEqualityIsExact() {
        // equals() is exact (MethodKeyMatcher.matches is the
        // wildcard-aware predicate); a wildcard stored in a set is not
        // equal to a concrete key.
        MethodKey wildcard = MethodKey.ofAnyOverload("app/Service", "helper");
        MethodKey concrete = MethodKey.of("app/Service", "helper", "(I)V");
        assertNotEquals(wildcard, concrete);
    }

    @Test
    void differentOwnersOrNamesDoNotMatch() {
        MethodKey a = MethodKey.of("app/A", "helper", "(I)V");
        MethodKey b = MethodKey.of("app/B", "helper", "(I)V");
        MethodKey c = MethodKey.of("app/A", "other", "(I)V");
        assertFalse(MethodKeyMatcher.matches(a, b));
        assertFalse(MethodKeyMatcher.matches(a, c));
        assertFalse(MethodKeyMatcher.matches(
            MethodKey.ofAnyOverload("app/A", "helper"), b));
    }

    @Test
    void wildcardKeyInHashMapFootgunIsClosed() {
        // The whole point of the matches/equals split: a wildcard
        // MethodKey hashes on the literal `"*"` descriptor, so a
        // HashMap lookup against a concrete key cannot find it. This
        // assertion pins that contract — code that wants wildcard
        // lookups must route through MethodKeyMatcher, not the
        // HashMap surface.
        Map<MethodKey, String> map = new HashMap<>();
        map.put(MethodKey.of("app/Service", "helper", "(I)V"), "int-variant");
        assertNull(map.get(MethodKey.ofAnyOverload("app/Service", "helper")),
            "wildcard MUST NOT find the concrete entry — that would be the "
            + "asymmetry footgun the matches/equals split forbids");
        // But MethodKeyMatcher.matches still returns true for the same pair:
        assertTrue(MethodKeyMatcher.matches(
            MethodKey.ofAnyOverload("app/Service", "helper"),
            MethodKey.of("app/Service", "helper", "(I)V")),
            "wildcard match through the helper still works");
    }

    @Test
    void hashMapDistinguishesOverloads() {
        Map<MethodKey, String> map = new HashMap<>();
        map.put(MethodKey.of("app/Service", "helper", "(I)V"), "int-variant");
        map.put(MethodKey.of("app/Service", "helper", "(J)V"), "long-variant");
        assertEquals(2, map.size(), "two overloads occupy two slots");
        assertEquals("int-variant", map.get(MethodKey.of("app/Service", "helper", "(I)V")));
        assertEquals("long-variant", map.get(MethodKey.of("app/Service", "helper", "(J)V")));
    }

    @Test
    void hashSetDistinguishesOverloads() {
        Set<MethodKey> set = new HashSet<>();
        set.add(MethodKey.of("app/Service", "helper", "(I)V"));
        set.add(MethodKey.of("app/Service", "helper", "(J)V"));
        assertEquals(2, set.size(), "set must split overloads, not coalesce them");
    }

    @Test
    void toStringIncludesAllThreeFields() {
        // The sidecar JSON uses toString() as the rename-map key — the
        // representation must round-trip enough to reconstruct
        // (owner, name, descriptor).
        MethodKey k = MethodKey.of("app/Service", "helper", "(I)V");
        String s = k.toString();
        assertTrue(s.contains("app/Service"), "toString must carry owner");
        assertTrue(s.contains("helper"), "toString must carry name");
        assertTrue(s.contains("(I)V"), "toString must carry descriptor");
    }

    @Test
    void nullArgumentsRejected() {
        assertThrows(NullPointerException.class,
            () -> MethodKey.of(null, "n", "(I)V"));
        assertThrows(NullPointerException.class,
            () -> MethodKey.of("a", null, "(I)V"));
        assertThrows(NullPointerException.class,
            () -> MethodKey.of("a", "n", null));
    }
}
