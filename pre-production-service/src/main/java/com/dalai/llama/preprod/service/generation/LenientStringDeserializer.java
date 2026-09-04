package com.dalai.llama.preprod.service.generation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/** Same tolerance philosophy as {@link TolerantEnumParser}, for a different failure shape: the
 * model occasionally returns a nested object where a free-text field was asked for -- observed
 * once already, a full duplicate of the cinematography sub-object landed in a single shot's
 * {@code cinematicExecution} string field, almost certainly because that field's name reads close
 * enough to "cinematography" for the model to pattern-match the wrong shape onto it. Without this,
 * Jackson's default String deserializer throws on the very first such field and fails the entire
 * (real-money, up-to-90-second) generation call over one shot's one field. Registered on a copy of
 * the shared ObjectMapper (see ShotListGenerationService), not the app-wide bean -- this leniency
 * is specific to parsing LLM output, not real API request bodies elsewhere in the service. */
public class LenientStringDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonNode node = parser.getCodec().readTree(parser);
        // Text/number/boolean coerce to their natural string form, same as Jackson's default
        // String deserializer already does -- only an object/array (the actual malformed shape
        // seen in practice) drops to null instead of throwing.
        return node.isObject() || node.isArray() ? null : node.asText(null);
    }
}
