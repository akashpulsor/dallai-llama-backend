-- Face reference becomes optional to support the "AI-generated identity" flow -- a cast profile
-- for a fully AI-generated character has no uploaded face photo (the video model generates one
-- fresh per shot from the character's text description) and pairs with a built-in ElevenLabs
-- voice for reliable pronunciation. The whole downstream pipeline is already null-safe:
-- DialogueShotContextAssemblyStrategy passes whatever faceRefBucket/faceRefObjectKey the profile
-- has (nullable or not), ShotGenerationOrchestrator.saveReferences already skips CHARACTER_FACE
-- when either is null, and FalAiProvider.falEndpoint already routes to /text-to-video when no
-- reference_image_urls are present (same code path PRODUCT_HERO/MOTION_GRAPHIC shots have used
-- for months). Also cleans up the pre-existing NARRATOR "use a placeholder image" workaround
-- CastProfileType's javadoc calls out.
ALTER TABLE cast_profile ALTER COLUMN face_ref_bucket DROP NOT NULL;
ALTER TABLE cast_profile ALTER COLUMN face_ref_object_key DROP NOT NULL;
