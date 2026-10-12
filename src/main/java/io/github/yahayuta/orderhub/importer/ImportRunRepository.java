package io.github.yahayuta.orderhub.importer;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ImportRunRepository {

    private final JdbcClient jdbc;

    public ImportRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ImportRun save(ImportRun run) {
        long id = jdbc.sql("""
                        INSERT INTO import_runs (mall, status, started_at, finished_at, fetched, inserted, updated,
                                                 unchanged, flagged, attempts, error)
                        VALUES (:mall, :status, :startedAt, :finishedAt, :fetched, :inserted, :updated,
                                :unchanged, :flagged, :attempts, :error)
                        RETURNING id
                        """)
                .param("mall", run.mall())
                .param("status", run.status().name())
                .param("startedAt", OrderRepository.utc(run.startedAt()))
                .param("finishedAt", OrderRepository.utc(run.finishedAt()))
                .param("fetched", run.fetched())
                .param("inserted", run.inserted())
                .param("updated", run.updated())
                .param("unchanged", run.unchanged())
                .param("flagged", run.flagged())
                .param("attempts", run.attempts())
                .param("error", run.error())
                .query(Long.class)
                .single();
        return run.withId(id);
    }

    public List<ImportRun> latest(int limit) {
        return jdbc.sql("SELECT * FROM import_runs ORDER BY id DESC LIMIT :limit")
                .param("limit", limit)
                .query((rs, n) -> new ImportRun(rs.getLong("id"), rs.getString("mall"),
                        ImportRun.Status.valueOf(rs.getString("status")),
                        OrderRepository.instant(rs, "started_at"), OrderRepository.instant(rs, "finished_at"),
                        rs.getInt("fetched"), rs.getInt("inserted"), rs.getInt("updated"),
                        rs.getInt("unchanged"), rs.getInt("flagged"), rs.getInt("attempts"),
                        rs.getString("error")))
                .list();
    }
}
