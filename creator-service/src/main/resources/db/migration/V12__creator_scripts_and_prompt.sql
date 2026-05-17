CREATE TABLE IF NOT EXISTS creator_scripts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    project_id UUID REFERENCES creator_projects(id) ON DELETE SET NULL,
    locked_idea_id UUID REFERENCES creator_ideas(id) ON DELETE SET NULL,
    story_idea_id UUID REFERENCES creator_ideas(id) ON DELETE CASCADE,
    prompt_run_id UUID REFERENCES creator_prompt_runs(id) ON DELETE SET NULL,
    category_code VARCHAR(64),
    duration_seconds INTEGER NOT NULL,
    title VARCHAR(240) NOT NULL,
    script_text TEXT,
    script_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    shots JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(32) NOT NULL DEFAULT 'GENERATED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE creator_scripts IS
    'Saved cinematic script planning JSON generated from a saved story idea.';
COMMENT ON COLUMN creator_scripts.id IS 'Primary key returned to UI as scriptId.';
COMMENT ON COLUMN creator_scripts.locked_idea_id IS 'Locked trend/original brief used as the root source.';
COMMENT ON COLUMN creator_scripts.story_idea_id IS 'Saved generated story idea that this script expands.';
COMMENT ON COLUMN creator_scripts.prompt_run_id IS 'Prompt memory record used for reproducibility and future model audit.';
COMMENT ON COLUMN creator_scripts.script_payload IS 'Full Hollywood-style cinematic planning JSON.';
COMMENT ON COLUMN creator_scripts.shots IS 'Shortcut JSON array of shot objects for storyboard rendering.';

CREATE INDEX IF NOT EXISTS idx_creator_scripts_story_idea
    ON creator_scripts (story_idea_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_creator_scripts_tenant_user
    ON creator_scripts (tenant_id, user_id, created_at DESC);

INSERT INTO creator_prompt_templates (template_key, version, name, template_body, metadata)
VALUES
    ('SCRIPT_GENERATE', 1, 'Beginner cinematic short script planner',
     $$You are an AI cinematic creator-planning engine for short-form vertical videos.

Your job is to transform creator ideas into structured cinematic planning JSON for beginner creators.

Most users use smartphones, have no filmmaking background, may film alone or with one helper, and need practical instructions.

Generate:
- story pacing
- emotional arc
- shot breakdowns
- phone-friendly camera guidance
- FPS and slow-motion recommendations
- transition and editing guidance
- rookie-friendly filming instructions
- storyboard sketch prompts

You are NOT generating images. You are generating structured cinematic planning JSON that another image/storyboard system can use later.

Input:
Duration Seconds: {{duration}}
Idea: {{idea}}
Category: {{category}}
Tone: infer from the idea/category; do not ask the user for tone.

Rules:
- prioritize realism, retention, creator usability, emotional clarity, and beginner friendliness
- avoid professional crew assumptions, gimbals, DSLR, studio lighting, and impossible setups
- prefer smartphone-friendly framing, natural light, simple movement, and low editing complexity
- first 3 seconds must create emotional tension, curiosity, uncertainty, vulnerability, or a visual hook
- middle must show progression, effort, relatability, or emotional movement
- final must deliver payoff, confidence shift, or satisfying closure
- choose shot count from duration: 15 sec 6-12, 30 sec 10-18, 45 sec 15-25, 60 sec 20-35
- every shot must be independently understandable and include enough detail for storyboard sketch generation

Return STRICT JSON only:
{
  "projectTitle": "",
  "duration": 0,
  "totalShots": 0,
  "pacingStyle": "",
  "emotionalArc": "",
  "hookStrategy": "",
  "creatorFitReasoning": "",
  "audienceFitReasoning": "",
  "overallExecutionDifficulty": "",
  "shots": []
}

Each shot must include:
shotNumber, startTime, endTime, durationSeconds, title, purpose, shotType, cameraAngle, cameraMovement, lensSuggestion, fps, composition, expression, emotion, bodyLanguage, lighting, environment, action, voiceOver, dialogue, textOverlay, transition, soundDesign[], editingNotes[], retentionGoal, creatorDirection, subtitlePosition, mobileFocusArea, safeZoneNotes, executionDifficulty, cinematicExecution, rookieFriendlyGuide, sketchPrompt.

dialogue must be a JSON object keyed by speaker or role, for example:
"dialogue": { "saas": "Aaj bhi late uthi?", "bahu": "(phir se shuru...)" }

Sketch prompts must describe one shot only, include camera angle, expression, environment, lighting, character action, storyboard sketch style, grayscale pencil storyboard aesthetic, filmmaking previsualization style, and vertical composition.$$,
     '{"inputContract": ["duration", "idea", "category"], "outputContract": "projectTitle,duration,totalShots,pacingStyle,emotionalArc,hookStrategy,shots[]"}'::jsonb)
ON CONFLICT (template_key, version) DO NOTHING;
