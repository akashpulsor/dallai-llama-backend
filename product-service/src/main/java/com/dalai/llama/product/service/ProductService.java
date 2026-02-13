package com.dalai.llama.product.service;

import com.dalai.llama.product.domain.entity.Product;
import com.dalai.llama.product.domain.entity.ProductApp;

import java.util.List;

public interface ProductService {

    List<Product> getAllActiveProducts();

    Product getByCode(String code);

    Product createProduct(Product product);

    List<ProductApp> getAppsByProductCode(String productCode);
}
