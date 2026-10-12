package io.github.yahayuta.orderhub.domain;

/** The order states every marketplace is mapped onto. */
public enum OrderStatus {
    NEW,
    SHIPPED,
    CANCELLED,
    /** The marketplace sent a state this service does not know; a person has to decide. */
    UNKNOWN
}
