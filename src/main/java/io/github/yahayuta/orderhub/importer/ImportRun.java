package io.github.yahayuta.orderhub.importer;

import java.time.Instant;

public record ImportRun(Long id, String mall, Status status, Instant startedAt, Instant finishedAt,
                        int fetched, int inserted, int updated, int unchanged, int flagged, int attempts,
                        String error) {

    public enum Status { SUCCEEDED, FAILED }

    ImportRun withId(long newId) {
        return new ImportRun(newId, mall, status, startedAt, finishedAt, fetched, inserted, updated, unchanged,
                flagged, attempts, error);
    }
}
