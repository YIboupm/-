package com.compras.procurement.catalog;

import java.math.BigDecimal;

/** Volume price: from {@code minQty} units on, each unit costs {@code unitPrice}. */
public record PriceBreak(int minQty, BigDecimal unitPrice) {
}
