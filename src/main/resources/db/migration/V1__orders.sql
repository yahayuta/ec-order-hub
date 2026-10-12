-- One row per marketplace order, whichever marketplace it came from.
CREATE TABLE orders (
    id               BIGSERIAL PRIMARY KEY,
    mall             TEXT        NOT NULL,
    external_id      TEXT        NOT NULL,
    ordered_at       TIMESTAMPTZ NOT NULL,
    mall_updated_at  TIMESTAMPTZ NOT NULL,
    status           TEXT        NOT NULL,
    prefecture       TEXT        NOT NULL DEFAULT '',
    stated_total     BIGINT      NOT NULL,
    lines_total      BIGINT      NOT NULL,
    needs_review     BOOLEAN     NOT NULL,
    issues           TEXT        NOT NULL DEFAULT '',
    content_hash     TEXT        NOT NULL,
    first_imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_changed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- the natural key: importing the same order twice must update, never duplicate
    CONSTRAINT orders_mall_external_id UNIQUE (mall, external_id)
);

CREATE INDEX orders_review ON orders (needs_review) WHERE needs_review;

CREATE TABLE order_lines (
    order_id   BIGINT  NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    line_no    INT     NOT NULL,
    sku        TEXT    NOT NULL,
    quantity   INT     NOT NULL,
    unit_price BIGINT  NOT NULL,
    PRIMARY KEY (order_id, line_no)
);

-- Where the next import of each marketplace starts.
CREATE TABLE import_cursors (
    mall      TEXT        PRIMARY KEY,
    cursor_at TIMESTAMPTZ NOT NULL
);

-- One row per import run, so failures and retries stay visible.
CREATE TABLE import_runs (
    id          BIGSERIAL PRIMARY KEY,
    mall        TEXT        NOT NULL,
    status      TEXT        NOT NULL,
    started_at  TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ NOT NULL,
    fetched     INT         NOT NULL,
    inserted    INT         NOT NULL,
    updated     INT         NOT NULL,
    unchanged   INT         NOT NULL,
    flagged     INT         NOT NULL,
    attempts    INT         NOT NULL,
    error       TEXT        NOT NULL DEFAULT ''
);
