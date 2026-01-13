package com.dalai.llama.billing.service;

import com.dalai.llama.billing.domain.entity.Cdr;

public interface RatingService {

    void rate(Cdr cdr);
}
