package io.github.yahayuta.orderhub.mall;

import io.github.yahayuta.orderhub.domain.Order;
import java.time.Instant;
import java.util.List;

/** One marketplace. Each implementation knows that marketplace's API and format. */
public interface MallClient {

    /** Short code used in the database and the API, e.g. {@code sakura}. */
    String code();

    /** Orders created or changed at or after {@code since}, already in the shared format. */
    List<Order> fetchUpdatedSince(Instant since);
}
