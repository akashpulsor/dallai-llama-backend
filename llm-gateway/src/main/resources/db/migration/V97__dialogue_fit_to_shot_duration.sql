-- VIDEO_DIALOGUE_FIT: decide whether a shot's line can be said in the time the shot has, and
-- shorten it when it cannot.
--
-- Generation is multimodal -- the model performs the dialogue -- so a line that takes longer to say
-- than the shot runs comes back cut off mid-word. shot-01-003 shipped that way: 3 seconds carrying a
-- line that needs about eighteen.
--
-- The two obvious repairs are both wrong. Stretching the shot to fit means a client who bought 60
-- seconds is charged for 90 because the writing ran long. Hurrying the delivery means speech nobody
-- can follow. The answer is to fix the plan before any money is spent: this runs at prepare time,
-- where a prompt call costs a fraction of a render, and the shot is only dispatched once the line
-- and the duration agree.
--
-- Judged by the model rather than by a character budget. Characters per second is not a constant --
-- Hindi, English and Tamil differ, and so do two lines of the same length with different syllable
-- counts -- so any fixed ratio is wrong for some project. The model is asked to read it aloud in its
-- head, which is the same judgement a director makes.
--
-- A two second overrun is left alone deliberately. Shots absorb that much without anyone noticing,
-- and rewriting a creator's words to save two seconds is a worse trade than the two seconds.
-- Shortening is meant to be rare; when it happens the meaning has to survive, because the line is
-- the product.

INSERT INTO prompt_template (task_key, version, content, active) VALUES (
'VIDEO_DIALOGUE_FIT',
1,
'You are a director checking whether a line of dialogue can be spoken in the time a shot runs for.

The shot is {{durationSeconds}} seconds long, planned at {{fps}} frames per second.
Write for a delivery this shot can contain: the duration says how much time there is, the frame rate says how that time is cut.
The line is: {{dialogue}}

Say it aloud in your head at a natural, unhurried pace -- the pace an audience would want to hear, not the fastest it could be read. Include the breaths and the beats a real delivery needs. Judge it in the language it is written in: syllable counts and speaking rates differ between languages, so do not reason in characters or words.

Then answer with a single JSON object and nothing else:

{"estimatedSeconds": <how many seconds a natural delivery takes, a number>, "fits": <true if it can be delivered naturally within the shot duration plus two seconds, otherwise false>, "fitted": <if fits is false, the line rewritten to be sayable within the shot duration; if fits is true, null>}

When rewriting:
- Keep the meaning and the intent. This line is the product; it is not filler to be trimmed to length.
- Keep the same language, register and tone of voice. A formal line stays formal; a warm line stays warm.
- Keep what the speaker is actually communicating. Cut hedging, repetition and preamble before cutting substance.
- Keep it natural to say aloud. It is spoken, not read.
- Do not add anything that was not already implied.
- If the line genuinely cannot be shortened without losing its point, return it as close to the original as you can and let estimatedSeconds tell the truth about how long it takes.',
true);
