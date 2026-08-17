INSERT INTO creator_prompt_templates (template_key, version, name, template_body, status)
VALUES
    ('PHONEME_PRONUNCIATION_GUIDE', 1, 'Auto-generate a pronunciation guide for dialogue in a given language',
     'The dialogue below is spoken in {{languageCode}}. Identify any words a text-to-speech engine tuned mainly for standard English is likely to mispronounce - especially romanized/transliterated words (e.g. Hinglish, romanized Hindi), proper nouns, brand names, and loanwords. For each one, provide a simple English-letter respelling that guides correct pronunciation.

Return strict JSON only, no markdown: {"pronunciationGuide": "term=respelling\nterm2=respelling2"}. One "term=respelling" pair per line. Only include terms that actually need help; return an empty string for "pronunciationGuide" if none do. Do not include terms already spelled the way they should be pronounced.

Dialogue:
{{dialogueText}}',
     'ACTIVE')
ON CONFLICT (template_key, version) DO NOTHING;
