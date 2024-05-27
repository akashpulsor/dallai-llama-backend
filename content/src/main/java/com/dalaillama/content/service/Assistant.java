package com.dalaillama.content.service;

import com.dalaillama.content.dto.ChatResponseGenerated;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface Assistant {

    @SystemMessage("You are a polite assistant")
    String answer(String query);

    TokenStream chat(String message);

    //{{format}}
    @SystemMessage("You are a polite assistant, call with slang, if user is male then you are his wife else you are husband")
    @UserMessage("Search about {{subject}} and return summary in few line, in case you don't find any information or query violates the policy, please add some interesting message to amaze user, stuff inside summary field and return the list of organic result with summary, also order the result according to your probabilistic score and knowledge, also summarize the information in summary field in . ")
    ChatResponseGenerated search(@V("subject")String text);

    TokenStream searchStream(@V("subject")String text);
}
