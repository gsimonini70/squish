package com.lucsartech.squish.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ScopeState}, the in-memory runtime scope override shared between the
 * dashboard controller and the watchdog. No Spring context or database is needed.
 */
@DisplayName("ScopeState (runtime scope override)")
class ScopeStateTest {

    @Test
    @DisplayName("a fresh state is inactive and versioned at 0")
    void freshStateInactive() {
        var s = new ScopeState();
        var snap = s.snapshot();
        assertThat(snap.active()).isFalse();
        assertThat(snap.version()).isZero();
    }

    @Test
    @DisplayName("apply activates the override, records the fields and bumps the version")
    void applyRecordsFieldsAndBumpsVersion() {
        var s = new ScopeState();
        s.apply(10, 20, "001030", true, "alice");

        var snap = s.snapshot();
        assertThat(snap.active()).isTrue();
        assertThat(snap.idFrom()).isEqualTo(10);
        assertThat(snap.idTo()).isEqualTo(20);
        assertThat(snap.docType()).isEqualTo("001030");
        assertThat(snap.autoRevert()).isTrue();
        assertThat(snap.appliedBy()).isEqualTo("alice");
        assertThat(snap.appliedAt()).isNotNull();
        assertThat(snap.version()).isEqualTo(1);
        assertThat(snap.hasUpperBound()).isTrue();
    }

    @Test
    @DisplayName("a blank doc-type is normalised to null (keep configured filter)")
    void blankDocTypeBecomesNull() {
        var s = new ScopeState();
        s.apply(0, 0, "   ", false, "bob");
        assertThat(s.snapshot().docType()).isNull();
        assertThat(s.snapshot().hasUpperBound()).isFalse();
    }

    @Test
    @DisplayName("clear deactivates an active override and bumps the version")
    void clearDeactivates() {
        var s = new ScopeState();
        s.apply(1, 2, null, false, "carol");
        long afterApply = s.snapshot().version();

        s.clear("test");
        var snap = s.snapshot();
        assertThat(snap.active()).isFalse();
        assertThat(snap.version()).isEqualTo(afterApply + 1);
    }

    @Test
    @DisplayName("clearing an already-inactive override is a no-op (no version bump)")
    void clearInactiveIsNoOp() {
        var s = new ScopeState();
        long before = s.snapshot().version();
        s.clear("nothing to do");
        assertThat(s.snapshot().version()).isEqualTo(before);
        assertThat(s.snapshot().active()).isFalse();
    }
}
