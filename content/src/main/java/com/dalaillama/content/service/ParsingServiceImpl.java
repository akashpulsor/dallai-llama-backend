package com.dalaillama.content.service;

import com.dalaillama.content.dto.SearchResponse;
import com.dalaillama.content.dto.SearchResponseDto;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.internal.StringUtil;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


@Slf4j
@Service
public class ParsingServiceImpl implements ParsingService{

    @Override
    public List<SearchResponseDto> parseContent(SearchResponse searchResponse) {
        SearchResponse.OrganicResults[] organicResults = searchResponse.getOrganicResults();

        List<SearchResponseDto> searchResponseDtoList = new ArrayList<>();
        for (int i =0; i < organicResults.length; i++) {
            SearchResponseDto searchResponseDto = new SearchResponseDto();
            SearchResponse.OrganicResults organicResult = organicResults[0];
            SearchResponseDto.Content content = parseContent(organicResult.getLink());
            searchResponseDto.setContent(content);
            searchResponseDto.setOrganicResults(organicResult);
            searchResponseDtoList.add(searchResponseDto);
        }
        return searchResponseDtoList;
    }

    private SearchResponseDto.Content parseContent(String link) {
        SearchResponseDto.Content content = new SearchResponseDto.Content();
        try {
            Document doc = Jsoup.connect(link).get();
            content.setTitle(doc.title());
            StringBuilder sb = new StringBuilder();
            for (Element headline : doc.getAllElements()) {
                if(!StringUtil.isBlank(headline.text())
                && StringUtil.isAscii(headline.text()) && headline.text().length() > 500){
                    sb.append(headline.text());
                }
            }
            content.setBody(sb.toString());
            return content;
        } catch (IOException e) {
            log.info("Failed to parse  - {} - {}",link,e);
        }
        return  content;
    }

}
