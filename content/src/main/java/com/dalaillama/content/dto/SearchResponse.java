package com.dalaillama.content.dto;


import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Data
public class SearchResponse {

    @JsonProperty("organic_results")
    private OrganicResults[] organicResults;

    //@JsonProperty("search_information")
    //private SearchInformation searchInformation;

    //@JsonProperty("knowledge_graph")
    //private Object knowledgeGraph;

    @JsonProperty("twitter_results")
    private Object twitterResults;

    //@JsonProperty("related_questions")
    //private Object relatedQuestions;

    //@JsonProperty("search_metadata")
    //private SearchMetaData searchMetadata;

    @JsonProperty("top_stories")
    private List<TopStories> topStories;

    //@JsonProperty("search_parameters")
    //private SearchParameters searchParameters;

    @JsonProperty("top_stories_link")
    private String topStoriesLink;

    //@JsonProperty("top_stories_serpapi_link")
    //private String topStoriesSerpApiLink;

    //@JsonProperty("related_searches")
    //private List<RelatedSearch> relatedSearches;

    //private Pagination pagination;

    //@JsonProperty("serpapi_pagination")
    //private SerpPagination serpApiPagination;


    @Data
    public static class KnowledgeGraph {

        private String title;
        private String type;

        @JsonProperty("main_tab_text")
        private String mainTabText;

        private String kgmid;

        @JsonProperty("knowledge_graph_search_link")
        private String knowledgeGraphSearchLink;

        @JsonProperty("serpapi_knowledge_graph_search_link")
        private String serpapiKnowledgeGraphSearchLink;

        List<Tabs> tabs;
    }

    @Data
    public static class Tabs{
        private String text;
        private String si;

        private String link;

        @JsonProperty("serpapi_link")
        private String serpApiLink;
    }
    @Data
    public static class SearchInformation {
        @JsonProperty("query_displayed")
        private String queryDisplayed;

        @JsonProperty("organic_results_state")
        private String organicResultsState;

        @JsonProperty("spelling_fix")
        private String spellingFix;

        @JsonProperty("showing_results_for")
        private String showingResultsFor;
    }
    @Data
    public static class SearchParameters {

        @JsonIgnore
        @JsonProperty("query_displayed")
        private String queryDisplayed;

        @JsonIgnore
        @JsonProperty("organic_results_state")
        private String organicResultsState;

        @JsonIgnore
        @JsonProperty("spelling_fix")
        private String spellingFix;

        @JsonIgnore
        @JsonProperty("showing_results_for")
        private String showingResultsFor;

    }

    @Data
    public static class SearchMetaData {
        private String id;
        private String status;
        @JsonProperty("json_endpoint")
        private String jsonEndpoint;
        @JsonProperty("created_at")
        private String createdAt;
        @JsonProperty("processed_at")
        private String processedAt;
        @JsonProperty("google_url")
        private String googleUrl;
        @JsonProperty("raw_html_file")
        private String rawHtmlFile;
        @JsonProperty("total_time_taken")
        private int totalTimeTaken;
    }
    @Data
    public static class SerpPagination{
        private String current;

        @JsonProperty("next_link")
        private String nextLink;
        private String next;

        @JsonProperty("other_pages")
        private Map<String, String> otherPages;
    }

    @Data
    public static class Pagination{
        private String current;
        private String next;
        @JsonProperty("other_pages")
        private Map<String, String> otherPages;

    }

    @Data
    public static class RelatedSearch {

        @JsonProperty("block_position")
        private String blockPosition;
        private String query;
        private String link;

        @JsonProperty("serpapi_link")
        private String serpapiLink;
    }



    @Data
    public static class SerpApiPagination
    {
        private String next;

        private String current;

        @JsonProperty("next_link")
        private String nextLink;
    }


    @Data
    public static class TopStories {
        private String date;

        private String thumbnail;

        private String link;

        private String source;

        private String title;
    }
        @Data
    public static class Search_metadata {

        @JsonProperty("raw_html_file")
        private String rawHtmlFile;

        @JsonProperty("created_at")
        private String createdAt;

        @JsonProperty("processed_at")
        private String processedAt;

        private String id;

        @JsonProperty("total_time_taken")
        private int totalTimeTaken;

        @JsonProperty("google_url")
        private String googleUrl;

        @JsonProperty("json_endpoint")
        private String jsonEndPoint;

        private String status;
    }

    @Data
    public static class OrganicResults
    {
        private String snippet;

        @JsonProperty("redirect_link")
        private String redirectLink;

        private String thumbnail;

        @JsonProperty("displayed_link")
        private String displayedLink;

        private String favicon;

        @JsonProperty("snippet_highlighted_words")
        private String[] snippetHighlightedWords;

        private String link;

        private Sitelinks sitelinks;

        private String position;

        private String source;

        private String title;

    }


    @Data
    public static class Inline {
        private String link;

        private String title;
    }
    @Data
    public static  class Sitelinks
    {
        private Inline[] inline;

    }


}
