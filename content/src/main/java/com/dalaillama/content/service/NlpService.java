package com.dalaillama.content.service;

import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.util.CoreMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Slf4j
@Service
public class NlpService {


    public Map<String, String> summarizeText(String title, String paragraphText){
        Map<String,String> summaryMap = new HashMap<>();

        Properties props = new Properties();
        props.setProperty("annotators", "tokenize, ssplit, pos, lemma, ner");
        StanfordCoreNLP pipeline = new StanfordCoreNLP(props);

        // Process paragraph through CoreNLP pipeline
        Annotation document = new Annotation(paragraphText);
        pipeline.annotate(document);
        // Extract sentences
        List<CoreMap> sentences = document.get(CoreAnnotations.SentencesAnnotation.class);

        // Generate summary for the paragraph
        StringBuilder summary = new StringBuilder();
        int summaryLength = 0;
        int maxLength = 200; // Maximum summary length in characters

        for (CoreMap sentence : sentences) {
            String sentenceText = sentence.get(CoreAnnotations.TextAnnotation.class);
            int sentenceLength = sentenceText.length();

            // Append sentence to summary if it doesn't exceed maxLength
            if (summaryLength + sentenceLength <= maxLength) {
                summary.append(sentenceText).append(" ");
                summaryLength += sentenceLength;
            } else {
                break; // Stop summarization if maxLength is reached
            }
        }

        summaryMap.put(title, summary.toString());
        return summaryMap;
    }
}
