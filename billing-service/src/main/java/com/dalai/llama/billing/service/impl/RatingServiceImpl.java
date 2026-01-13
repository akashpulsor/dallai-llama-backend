package com.dalai.llama.billing.service.impl;

import com.dalai.llama.billing.domain.entity.Cdr;
import com.dalai.llama.billing.service.RatingService;
import com.dalai.llama.billing.service.rating.RatingEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RatingServiceImpl implements RatingService {

    private final RatingEngine ratingEngine;

    @Override
    public void rate(Cdr cdr) {
        ratingEngine.rate(cdr);
    }
}
