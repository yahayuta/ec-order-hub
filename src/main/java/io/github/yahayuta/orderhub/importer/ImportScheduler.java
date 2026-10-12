package io.github.yahayuta.orderhub.importer;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Imports every marketplace on a fixed delay. Off unless orderhub.scheduler.enabled=true. */
@Component
@ConditionalOnProperty(name = "orderhub.scheduler.enabled", havingValue = "true")
public class ImportScheduler {

    private final ImportService importer;

    public ImportScheduler(ImportService importer) {
        this.importer = importer;
    }

    @Scheduled(fixedDelayString = "${orderhub.scheduler.interval-ms:300000}",
            initialDelayString = "${orderhub.scheduler.initial-delay-ms:10000}")
    public void importAll() {
        for (String mall : importer.malls()) {
            importer.importMall(mall);
        }
    }
}
