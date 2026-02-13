package com.dalai.llama.product.service.impl;

import com.dalai.llama.product.domain.entity.Product;
import com.dalai.llama.product.domain.entity.ProductApp;
import com.dalai.llama.product.domain.exception.ProductNotFoundException;
import com.dalai.llama.product.repository.ProductAppRepository;
import com.dalai.llama.product.repository.ProductRepository;
import com.dalai.llama.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;

    private final ProductAppRepository productAppRepository;

    @Override
    public List<Product> getAllActiveProducts() {
        return productRepository.findByActiveTrue();
    }

    @Override
    public Product getByCode(String code) {
        return productRepository.findByCode(code)
                .orElseThrow(() -> new ProductNotFoundException(code));
    }

    @Override
    @Transactional
    public Product createProduct(Product product) {
        product.setId(UUID.randomUUID());
        product.setCreatedAt(Instant.now());
        product.setUpdatedAt(Instant.now());
        return productRepository.save(product);
    }

    @Transactional
    public List<ProductApp> getAppsByProductCode(String productCode) {
        getByCode(productCode);
        return productAppRepository.findEnabledAppsByProductCode(productCode);
    }
}