-- PRE_PROD_MUSIC_PLAN v3: the score is built on a raga, so it has a melody of its own.
--
-- v2 planned a mood bed: genre, palette, a motif named in words. Scores came back as texture with
-- no tune. v3 asks the planner to choose a Hindustani or Carnatic raga that fits the film's
-- emotion, its characteristic phrase (the motif comes from it), and a taal -- three new identity
-- fields the composer leads the prompt with. It also asks for short field values: the composed
-- prompt has a hard 4,100-character limit at the music model.
--
-- Derived from v2 with replace() so every other line stays identical. Each replace() target
-- appears exactly once in v2.

UPDATE prompt_template SET active = false
 WHERE task_key = 'PRE_PROD_MUSIC_PLAN' AND active = true;

INSERT INTO prompt_template (task_key, version, content, active, created_at)
SELECT 'PRE_PROD_MUSIC_PLAN', 3,
       replace(replace(replace(content,
           'ONE MUSICAL IDENTITY',
           'RAGA FIRST
Build the score on Indian classical music. Choose ONE raga (Hindustani or Carnatic) whose rasa matches the film''s emotion -- e.g. Yaman or Kalyani for trust and warmth, Bhairav for dawn and devotion, Desh or Megh for hope and rain, Bhimpalasi for longing, Hamsadhwani or Mohanam for celebration, Kafi or Khamaj for playful warmth, Darbari for gravitas. The motif is a characteristic phrase of that raga (give it as sargam or aroha/avaroha), so the music has an inherent melody that every section carries. Choose a taal for the rhythm (e.g. Keherwa, Dadra, Rupak, Teentaal, Adi). Modern or fusion instrumentation is welcome; the melody stays in the raga. A section may change register, density or ornamentation of the raga, never leave it.

ONE MUSICAL IDENTITY'),
           '"overallEnergyRange": { "min": 0.0, "max": 1.0 }',
           '"overallEnergyRange": { "min": 0.0, "max": 1.0 },
    "raga": "e.g. Raag Yaman",
    "ragaPhrase": "the characteristic phrase / aroha-avaroha the motif uses",
    "taal": "e.g. Keherwa, 8 beats"'),
           'Set totalDurationSeconds to exactly the total duration given above.',
           'Keep every text value short -- one sentence, under 150 characters -- because the prompt composed from this plan has a hard 4100-character limit. Set totalDurationSeconds to exactly the total duration given above.'),
       true, now()
  FROM prompt_template
 WHERE task_key = 'PRE_PROD_MUSIC_PLAN' AND version = 2;
