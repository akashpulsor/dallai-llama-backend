-- Master data backing CastProfileQuickCreate's Gender dropdown (previously a free-text input,
-- which made the built-in-voice picker's gender matching a lossy prefix-guess -- see
-- ShotImagePromptBuilder.pronounFor's own comment on why free text is fragile there too).
--
-- Deliberately only MALE/FEMALE, not a broader identity vocabulary: the ONLY real downstream
-- consumer is llm-gateway's builtin_voice.gender column (a plain VARCHAR CHECK-constrained to
-- these exact two values today, since that's what the provider itself labels its voices with),
-- so a wider vocabulary here would be misleading -- it'd offer choices that can't map to a real
-- voice. CastProfile.gender itself stays a plain String column (no type change, no migration on
-- cast_profile) -- same "String column, frontend picker is the real constraint" pattern already
-- used by ProjectConfig.preferredResolution and ProjectConfig.preferredVoiceModel. If a broader
-- identity vocabulary is ever needed for narrative purposes, ScriptCharacter.gender (LLM-authored,
-- separately consumed) is the field to widen -- deliberately out of scope here.
CREATE TABLE gender_option (
    code   VARCHAR(16) PRIMARY KEY,
    label  VARCHAR(40) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO gender_option (code, label, active) VALUES
('MALE',   'Male',   TRUE),
('FEMALE', 'Female', TRUE);
