package com.dalai.llama.preprod.domain;

/** How an uploaded shot product-reference photo should be used -- mirrors creator-service's real
 * CAST/INSPIRATION split: CAST means "this photo shows the actual person/product to render, keep
 * its identity exactly"; INSPIRATION means "borrow only mood/composition/lighting from this
 * photo, never its actual subject or branding". */
public enum ProductReferenceClassification {
    CAST,
    INSPIRATION
}
