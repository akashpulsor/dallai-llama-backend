package com.dalai.llama.billing.service.rating;

import com.dalai.llama.billing.domain.entity.Cdr;

public interface RatingEngine {

    void rate(Cdr cdr);
}
