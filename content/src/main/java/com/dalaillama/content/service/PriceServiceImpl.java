package com.dalaillama.content.service;

import com.dalaillama.content.dto.PriceListRequestDto;
import com.dalaillama.content.dto.PriceListResponseDto;
import com.dalaillama.content.entity.PriceList;
import com.dalaillama.content.repository.PriceListRepository;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
public class PriceServiceImpl implements  PriceService {

    private final PriceListRepository priceListRepository;

    public PriceServiceImpl(PriceListRepository priceListRepository) {
        this.priceListRepository = priceListRepository;
    }


    @Override
    public PriceListResponseDto addPrice(PriceListRequestDto priceListRequestDto) {
        PriceList priceList = priceListDtoToModel(priceListRequestDto);
        this.priceListRepository.save(priceList);
        return priceListModelToDto(priceList);
    }

    @Override
    public List<PriceListResponseDto> getAllPrice() {
        return this.priceListRepository.findAll().stream().map(this::priceListModelToDto).toList();
    }


    private PriceList priceListDtoToModel(PriceListRequestDto priceListRequestDto) {
        PriceList priceList = new PriceList();
        priceList.setCredit(priceListRequestDto.getCredit());
        priceList.setPrice(priceListRequestDto.getPrice());
        priceList.setLlmId(priceListRequestDto.getLlmId());
        priceList.setToolId(priceListRequestDto.getToolId());
        priceList.setCurrencyId(priceListRequestDto.getCurrencyId());
        priceList.setCurrencySymbol(priceListRequestDto.getCurrencySymbol());
        return priceList;
    }

    private PriceListResponseDto priceListModelToDto(PriceList priceList) {
        PriceListResponseDto priceListResponseDto = new PriceListResponseDto();
        priceListResponseDto.setCredit(priceList.getCredit());
        priceListResponseDto.setPrice(priceList.getPrice());
        priceListResponseDto.setLlmId(priceList.getLlmId());
        priceListResponseDto.setToolId(priceList.getToolId());
        priceListResponseDto.setCurrencyId(priceList.getCurrencyId());
        priceListResponseDto.setCurrencySymbol(priceList.getCurrencySymbol());
        return priceListResponseDto;
    }
}
