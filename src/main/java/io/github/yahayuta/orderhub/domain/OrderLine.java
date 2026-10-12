package io.github.yahayuta.orderhub.domain;

public record OrderLine(String sku, int quantity, long unitPrice) {

    public long amount() {
        return quantity * unitPrice;
    }
}
