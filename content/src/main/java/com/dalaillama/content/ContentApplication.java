package com.dalaillama.content;


import com.dalaillama.content.service.SearchServiceImpl;
import org.springframework.ai.embedding.EmbeddingClient;
//import org.springframework.ai.vectorstore.PgVectorStore;
//import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.function.Function;
//import org.springframework.jdbc.core.JdbcTemplate;


@EnableJpaAuditing
@SpringBootApplication
public class ContentApplication {

	public static void main(String[] args) {
		SpringApplication.run(ContentApplication.class, args);
	}



	@Bean
	public Function<SearchServiceImpl.Request, SearchServiceImpl.Response> searchFunction(SearchServiceImpl searchService) {

		return (searchService::apply);
	}
/*
	@Bean
	public VectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingClient embeddingClient) {
		return new PgVectorStore(jdbcTemplate, embeddingClient);
	}
*/
}
