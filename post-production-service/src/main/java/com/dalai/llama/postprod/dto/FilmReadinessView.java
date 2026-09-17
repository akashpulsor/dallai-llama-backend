package com.dalai.llama.postprod.dto;

import java.util.List;

/**
 * Whether the film can be put together, and what is stopping it.
 *
 * <p>{@code missingShotRefs} is named rather than counted so the button can say which shots still
 * need generating. A disabled control with no reason attached is the thing a creator has to guess
 * their way around.
 */
public record FilmReadinessView(boolean ready, int totalShots, int readyShots, List<String> missingShotRefs) {
}
