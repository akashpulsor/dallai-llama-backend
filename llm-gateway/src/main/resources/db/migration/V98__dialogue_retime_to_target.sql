-- VIDEO_DIALOGUE_RETIME: rewrite a line so it takes a given length of time to say.
--
-- Two things are different here from V97 (VIDEO_DIALOGUE_FIT), and both came out of measuring the
-- live project rather than reasoning about it.
--
-- FIRST: this does not ask the model how long anything takes. V97 did, and it is not a question a
-- language model can answer. Asked about shot-01-003 -- "क्या आपको कभी ऐसा लगा है कि जवाब सबके पास
-- हैं, पर आपके लिए कोई नहीं?" -- it judged "about eighteen" seconds. The synthesized take runs
-- 4.32. Out by a factor of four, and every remedy sized from that number inherits the error.
--
-- The duration is something we already know: the line has been synthesized and the audio measured
-- with ffprobe. So the measurement is given TO the model, and the request becomes a ratio -- make
-- this take 60% as long as it currently does -- which is a judgement about text, and the kind of
-- judgement models are good at. The character budget beside it is arithmetic from the same
-- measurement (the line's own seconds-per-character), not a rule of thumb.
--
-- SECOND: the model is not asked to report a duration back either, for the same reason. What it
-- returns is text; the caller multiplies its length by the measured rate to predict the new
-- duration, and re-synthesizing the line is what settles it for real.
--
-- Both directions are supported, but they are not symmetrical and the prompt says so. Shortening is
-- ordinary editing. Lengthening is where this goes wrong if left unguarded: the instinct is to pad,
-- and padding is worse than the silence it replaces -- a character saying more than the writer meant
-- is a bigger problem than a shot that runs on. The measured project confirmed which case matters:
-- all eight takes overrun their shots and not one falls short, so shortening is the live path and
-- lengthening is there for completeness.
--
-- Never applied on its own. The rewrite is shown to the creator against their original and replaces
-- it only if they accept it. These are someone's words and the product itself, not a field to be
-- auto-corrected to length.

INSERT INTO prompt_template (task_key, version, content, active) VALUES (
'VIDEO_DIALOGUE_RETIME',
1,
'You are a script editor adjusting how long a line of dialogue takes to perform, without changing what it means.

The line is: {{dialogue}}

This line has been recorded. It takes {{currentSeconds}} seconds to speak in this production''s own voice -- that is measured from the audio, not an estimate, so treat it as fact. It needs to take about {{targetSeconds}} seconds instead.

That is {{percentOfOriginal}}% of its current length. Work to that proportion: do not try to judge seconds yourself, because speaking rate depends on the voice and the language and you cannot hear either. As a rough guide, a line of that length in this script runs to roughly {{charBudget}} characters, against the current {{currentChars}}. Treat it as an aim, not a limit.

Write in {{languageCode}}, the language the line is already in.

If the target is shorter than the current length, shorten it. Cut hedging, repetition and preamble before cutting substance. If the line genuinely cannot carry its point in the time available, get as close as you honestly can and say so in whatChanged rather than mangling it.

If the target is longer, lengthen it -- and be careful, because this is where rewriting does more harm than the problem it solves. Let the character finish the thought they already had: the beat before the point, the aside, the hesitation. Do NOT add information the scene does not contain, a new claim, or filler words. Do NOT make the character more articulate or more explanatory than they were. If the line cannot be lengthened without inventing something, return it close to the original and say so. A short line with silence after it is better than a padded one.

In either direction:
- Keep the meaning and the intent. This line is the product; it is not filler to be trimmed to length.
- Keep the same register and tone of voice. A formal line stays formal; a warm line stays warm.
- Keep it natural to say aloud. It is spoken, not read.
- Leave spellings that exist for the voice engine exactly as they are. Where a brand or address has been written out the way it should be pronounced -- for example "AstroNext dot AI" rather than "AstroNext.ai" -- keep that spelling; changing it changes how it is said.
- Make sure the line still lands its point before it ends. When a line is too long for its shot it is the END that gets cut, so the last thing said should be the thing that matters, not a trailing clause.

Answer with a single JSON object and nothing else:

{"direction": <"SHORTEN", "LENGTHEN" or "KEEP">, "rewritten": <your version of the line; the original unchanged if direction is KEEP>, "whatChanged": <one short sentence, in English, naming what you cut or added -- so the writer can see what the rewrite cost them>}',
true);
