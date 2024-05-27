package com.dalaillama.content.service;

import com.dalaillama.content.dto.PriceListRequestDto;
import com.dalaillama.content.dto.PriceListResponseDto;

import java.util.List;

public interface PriceService {

    PriceListResponseDto addPrice(PriceListRequestDto priceListRequestDto);

    List<PriceListResponseDto> getAllPrice();
}
