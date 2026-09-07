-- Every child table that references shot(id) needs ON DELETE CASCADE for the "regenerate the
-- shot list" flow to work -- ShotListGenerationService#persistFromLlmResponse deletes every
-- shot for a project before writing the new set, and any dependent child row blocks that with
-- a FK violation. Confirmed live: a regenerate on a project that had already produced shot
-- images once failed with "update or delete on table 'shot' violates foreign key constraint
-- 'shot_image_shot_id_fkey' on table 'shot_image'", crashed the whole async shot-list job as
-- FAILED, and motion-graphic planning (chained after the successful shot persist) never ran.
--
-- generation_job / shot_background_music / shot_dialogue_beat already had CASCADE and were
-- already fine; the five below were the ones missing it. Consistent policy going forward:
-- deleting a shot deletes everything owned by that shot, since none of these child rows have
-- meaning without their parent.
ALTER TABLE camera_plan            DROP CONSTRAINT IF EXISTS camera_plan_shot_id_fkey;
ALTER TABLE camera_plan            ADD  CONSTRAINT camera_plan_shot_id_fkey
    FOREIGN KEY (shot_id) REFERENCES shot(id) ON DELETE CASCADE;

ALTER TABLE lighting_plan          DROP CONSTRAINT IF EXISTS lighting_plan_shot_id_fkey;
ALTER TABLE lighting_plan          ADD  CONSTRAINT lighting_plan_shot_id_fkey
    FOREIGN KEY (shot_id) REFERENCES shot(id) ON DELETE CASCADE;

ALTER TABLE motion_graphic_plan    DROP CONSTRAINT IF EXISTS motion_graphic_plan_shot_id_fkey;
ALTER TABLE motion_graphic_plan    ADD  CONSTRAINT motion_graphic_plan_shot_id_fkey
    FOREIGN KEY (shot_id) REFERENCES shot(id) ON DELETE CASCADE;

ALTER TABLE shot_image             DROP CONSTRAINT IF EXISTS shot_image_shot_id_fkey;
ALTER TABLE shot_image             ADD  CONSTRAINT shot_image_shot_id_fkey
    FOREIGN KEY (shot_id) REFERENCES shot(id) ON DELETE CASCADE;

ALTER TABLE shot_product_reference DROP CONSTRAINT IF EXISTS shot_product_reference_shot_id_fkey;
ALTER TABLE shot_product_reference ADD  CONSTRAINT shot_product_reference_shot_id_fkey
    FOREIGN KEY (shot_id) REFERENCES shot(id) ON DELETE CASCADE;
